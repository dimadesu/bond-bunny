package com.example.srtla;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import android.content.res.Configuration;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.app.PendingIntent;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1001;
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_SRTLA_HOST = "srtla_host";
    private static final String PREF_SRTLA_PORT = "srtla_port";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_STREAM_ID = "stream_id";
    
    private Button buttonStart;
    private Button buttonStop;
    private TextView textStatus;
    private TextView textNetworks;
    private TextView textConnectionStats;
    private ConnectionWindowView connectionWindowView;
    private Button buttonAbout;
    private Button buttonSettings;
    private Button buttonUrlBuilder;
    private Button buttonNativeSrtla;
    private boolean serviceRunning = false;
    private android.os.Handler uiHandler = new android.os.Handler();
    private Runnable statsUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        // Request notification permission at app start (Android 13+) so Start button doesn't trigger it
        checkAndRequestNotificationPermissionOnLaunch();
        
        buttonStart.setOnClickListener(v -> startSrtlaService());
        buttonStop.setOnClickListener(v -> stopSrtlaService());
        
        // Restore service state if activity was recreated
        checkServiceState();
    }
    
    /**
     * Check if the SRTLA service is actually running and update UI accordingly
     */
    private void checkServiceState() {
        // Check if the service is running
        serviceRunning = EnhancedSrtlaService.isServiceRunning();
        updateUI();
        
        if (serviceRunning) {
            startStatsUpdates();
        }
    }
    
    private void initViews() {
        buttonStart = findViewById(R.id.button_start);
        buttonStop = findViewById(R.id.button_stop);
        textStatus = findViewById(R.id.text_status);
        textConnectionStats = findViewById(R.id.text_connection_stats);
        connectionWindowView = findViewById(R.id.connection_window_view);
        buttonAbout = findViewById(R.id.button_about);
        buttonSettings = findViewById(R.id.button_settings);
        buttonUrlBuilder = findViewById(R.id.button_url_builder);
        buttonNativeSrtla = findViewById(R.id.button_native_srtla);
        
        // Set initial logging level for performance
        SrtlaLogger.setLogLevel(SrtlaLogger.LogLevel.PRODUCTION);
        
        // Add long-click listener to status text for changing log levels
        textStatus.setOnLongClickListener(v -> {
            cycleLogLevel();
            return true;
        });
        
        buttonAbout.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AboutActivity.class)));
        buttonSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        buttonUrlBuilder.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, UrlBuilderActivity.class)));
        buttonNativeSrtla.setOnClickListener(v -> toggleNativeSrtla());
        
        // Initialize native SRTLA UI state
        updateNativeSrtlaUI();
        
        // Load saved preferences or use default values
        loadPreferences();
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save current form values and service state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        outState.putString("srtla_host", prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net"));
        outState.putString("srtla_port", prefs.getString(PREF_SRTLA_PORT, "5000"));
        outState.putString("stream_id", getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_STREAM_ID, ""));
        outState.putBoolean("service_running", serviceRunning);
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // Restore form values
        if (savedInstanceState != null) {
            // Service state will be checked in onResume()
        }
    }

    private void startSrtlaService() {
        Log.i("MainActivity", "startSrtlaService() called");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String srtlaReceiverHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net").trim();
        String srtlaReceiverPort = prefs.getString(PREF_SRTLA_PORT, "5000").trim();
        String srtListenPort = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
        
        if (srtlaReceiverHost.isEmpty() || srtlaReceiverPort.isEmpty() || srtListenPort.isEmpty()) {
            Toast.makeText(this, "Please fill SRTLA receiver host, port, and SRT listen port", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save preferences when starting service
        savePreferences();
        
        // On Android 13+ we must request POST_NOTIFICATIONS permission before showing notifications

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ensure notification permission is available; the app requests it at launch.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Do not request here; direct the user to app notification settings instead
                Toast.makeText(this, "Notifications are disabled for this app. Please enable them in App Settings.", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent();
                    intent.setAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
                return;
            }
        }

        // Permission already granted or not required - start the service now
        startServiceNow(srtlaReceiverHost, srtlaReceiverPort, srtListenPort);
    }

    // Starts the service without requesting permissions (assumes caller has handled permission logic)
    private void startServiceNow(String srtlaReceiverHost, String srtlaReceiverPort, String srtListenPort) {
        Log.i("MainActivity", "startServiceNow() — starting EnhancedSrtlaService with " + srtlaReceiverHost + ":" + srtlaReceiverPort + " listening:" + srtListenPort);
        Intent serviceIntent = new Intent(this, EnhancedSrtlaService.class);
        serviceIntent.putExtra("srtla_receiver_host", srtlaReceiverHost);
        serviceIntent.putExtra("srtla_receiver_port", Integer.parseInt(srtlaReceiverPort));
        serviceIntent.putExtra("srt_listen_address", "0.0.0.0");  // Always listen on all interfaces
        serviceIntent.putExtra("srt_listen_port", Integer.parseInt(srtListenPort));

        startForegroundService(serviceIntent);

        serviceRunning = true;

        // Apply all current feature settings to the service from saved preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        EnhancedSrtlaService.setStickinessEnabled(prefs.getBoolean("stickiness_enabled", false));
        EnhancedSrtlaService.setQualityScoringEnabled(prefs.getBoolean("quality_scoring_enabled", true));
        EnhancedSrtlaService.setNetworkPriorityEnabled(prefs.getBoolean("network_priority_enabled", true));
        EnhancedSrtlaService.setExplorationEnabled(prefs.getBoolean("exploration_enabled", false));
        EnhancedSrtlaService.setClassicMode(prefs.getBoolean("classic_mode", false));

        updateUI();
        startStatsUpdates();

        Toast.makeText(this,
            "Port: " + srtListenPort + ". " + srtlaReceiverHost + ":" + srtlaReceiverPort,
            Toast.LENGTH_LONG).show();
    }
    
    private void stopSrtlaService() {
        Intent serviceIntent = new Intent(this, EnhancedSrtlaService.class);
        stopService(serviceIntent);
        
        serviceRunning = false;
        updateUI();
        stopStatsUpdates();
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
    }
    
    private void updateUI() {
        if (serviceRunning) {
            textStatus.setText("Status: Running");
            buttonStart.setEnabled(false);
            buttonStop.setEnabled(true);
        } else {
            textStatus.setText("Status: Stopped");
            buttonStart.setEnabled(true);
            buttonStop.setEnabled(false);
            textConnectionStats.setText("No active connections");
            connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
        }
    }
    
    
    private void startStatsUpdates() {
        statsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (serviceRunning) {
                    updateConnectionStats();
                    uiHandler.postDelayed(this, 1000); // Update every second
                }
            }
        };
        uiHandler.post(statsUpdateRunnable);
    }
    
    private void stopStatsUpdates() {
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
            textConnectionStats.setText("No active connections");
            connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
        }
    }
    
    private void updateConnectionStats() {
        String stats = EnhancedSrtlaService.getConnectionStatistics();
        
        // Add logging performance stats
        String logStats = SrtlaLogger.getPerformanceStats();
        String combinedStats = stats + "\n\n" + logStats;
        
        textConnectionStats.setText(combinedStats);
        
        // Update window visualization
        List<ConnectionWindowView.ConnectionWindowData> windowData = 
            EnhancedSrtlaService.getConnectionWindowData();
        connectionWindowView.updateConnectionData(windowData);
        
        // Periodically refresh native SRTLA UI state (handles crashes)
        updateNativeSrtlaUI();
    }
    
    /**
     * Cycle through logging levels on long press
     */
    private void cycleLogLevel() {
        SrtlaLogger.LogLevel current = SrtlaLogger.getLogLevel();
        SrtlaLogger.LogLevel next;
        
        switch (current) {
            case PRODUCTION:
                next = SrtlaLogger.LogLevel.DEVELOPMENT;
                break;
            case DEVELOPMENT:
                next = SrtlaLogger.LogLevel.DEBUG;
                break;
            case DEBUG:
                next = SrtlaLogger.LogLevel.TRACE;
                break;
            default:
                next = SrtlaLogger.LogLevel.PRODUCTION;
                break;
        }
        
        SrtlaLogger.setLogLevel(next);
        Toast.makeText(this, "Logging level: " + next + 
                      (next == SrtlaLogger.LogLevel.PRODUCTION ? " (Best Performance)" : 
                       next == SrtlaLogger.LogLevel.DEVELOPMENT ? " (Balanced)" : " (Verbose)"), 
                      Toast.LENGTH_SHORT).show();
        
        // Reset stats when changing levels
        SrtlaLogger.resetStats();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Always check service state when resuming (handles rotation, app switching, etc.)
        checkServiceState();
        // Also check native SRTLA state
        updateNativeSrtlaUI();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        stopStatsUpdates();
        // Save current form values when app is paused
        savePreferences();
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Algorithm preferences are now managed in SettingsActivity
    }
    
    private void savePreferences() {
        // Algorithm preferences are now managed in SettingsActivity
        // No longer saving preferences in MainActivity
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            // Only inform the user of the result when permission was requested
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
                Log.i("MainActivity", "POST_NOTIFICATIONS granted onRequestPermissionsResult");
                postStartupNotification();
            } else {
                Toast.makeText(this, "Notification permission denied. Foreground service notification may be suppressed.", Toast.LENGTH_LONG).show();
                Log.i("MainActivity", "POST_NOTIFICATIONS denied onRequestPermissionsResult");
                // Offer quick access to notification settings
                try {
                    Intent intent = new Intent();
                    intent.setAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        }
    }
    
    // Check notification permission at app launch and request if necessary.
    private void checkAndRequestNotificationPermissionOnLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS ) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_POST_NOTIFICATIONS
                );
                return;
            }
        }

        // Permission is granted or not required — post a lightweight startup notification that the
        // service will later update (same channel and notification id as the service).
        postStartupNotification();
    }

    // Post a small notification at app launch so the service can update it later using the same id.
    private void postStartupNotification() {
        try {
            final String channelId = EnhancedSrtlaService.CHANNEL_ID;
            final int notificationId = 1;

            // Ensure the shared notification channel exists. This is idempotent.
            EnhancedSrtlaService.createNotificationChannel(this);

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Bond Bunny started")
                .setContentText("Service is not running")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            nm.notify(notificationId, builder.build());
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to post startup notification", e);
        }
    }
    
    // Native SRTLA methods for testing
    static {
        try {
            System.loadLibrary("srtla_android");
        } catch (UnsatisfiedLinkError e) {
            Log.e("MainActivity", "Failed to load native SRTLA library", e);
        }
    }
    
    // Native methods calling Android-patched SRTLA
    public native int startSrtlaNative(String listenPort, String srtlaHost, 
                                      String srtlaPort, String ipsFile);
    public native int stopSrtlaNative();
    public native boolean isRunningSrtlaNative();
    
    /**
     * Check if native SRTLA is running with error handling
     */
    private boolean isNativeSrtlaRunning() {
        try {
            return isRunningSrtlaNative();
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to check native SRTLA state", e);
            return false; // Assume stopped on error
        }
    }
    
    private void toggleNativeSrtla() {
        if (isNativeSrtlaRunning()) {
            stopNativeSrtla();
        } else {
            startNativeSrtla();
        }
    }
    
    private void startNativeSrtla() {
        textStatus.setText("Starting native SRTLA process...");
        
        try {
            // Create fresh IPs file (overwrite any existing)
            java.io.File ipsFile = new java.io.File(getFilesDir(), "real_network_ips.txt");
            
            // Delete existing file to ensure clean start
            if (ipsFile.exists()) {
                ipsFile.delete();
                Log.i("MainActivity", "Deleted existing IPs file");
            }
            
            try (java.io.FileWriter writer = new java.io.FileWriter(ipsFile, false)) { // false = overwrite
                // Get actual network interface IPs
                java.util.List<String> networkIps = getRealNetworkIPs();
                if (networkIps.isEmpty()) {
                    // Fallback to some common private network ranges
                    writer.write("192.168.1.100\n");
                    writer.write("10.0.0.100\n");
                    Log.w("MainActivity", "No real network IPs found, using fallback IPs");
                } else {
                    for (String ip : networkIps) {
                        writer.write(ip + "\n");
                        Log.i("MainActivity", "Writing IP to file: " + ip);
                    }
                }
                writer.flush(); // Ensure data is written
            }
            
            Log.i("MainActivity", "Created IPs file: " + ipsFile.getAbsolutePath() + 
                  " (size: " + ipsFile.length() + " bytes)");
            
            int result = startSrtlaNative(
                // Listen port (SRT)
                "6000",
                // SRTLA host
                "au.srt.belabox.net",
                // SRTLA port
                "5000",
                // IPs file
                ipsFile.getAbsolutePath()
            );
            
            if (result == 0) {
                updateNativeSrtlaUI();
                textStatus.setText("✅ Native SRTLA process started");
            } else {
                String errorMsg = "❌ Native SRTLA Failed to Start\n\n";
                switch (result) {
                    case -1:
                        errorMsg += "Network or DNS resolution error";
                        break;
                    case -2:
                        errorMsg += "SRTLA receiver unreachable";
                        break;
                    default:
                        errorMsg += "Error code: " + result;
                        break;
                }
                textStatus.setText(errorMsg);
            }
            
        } catch (Exception e) {
            textStatus.setText("❌ Error starting native SRTLA: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA start error", e);
        }
    }
    
    private void stopNativeSrtla() {
        textStatus.setText("Stopping native SRTLA process...");
        
        try {
            int result = stopSrtlaNative();
            updateNativeSrtlaUI();
            
            if (result == 0) {
                textStatus.setText("✅ Native SRTLA Stopped");
            } else {
                textStatus.setText("⚠️ Native SRTLA stopped with code: " + result);
            }
            
        } catch (Exception e) {
            updateNativeSrtlaUI();
            textStatus.setText("❌ Error stopping native SRTLA: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA stop error", e);
        }
    }
    
    private void updateNativeSrtlaUI() {
        if (isNativeSrtlaRunning()) {
            buttonNativeSrtla.setText("Stop Native SRTLA");
            buttonNativeSrtla.setBackgroundColor(0xFFFF5722); // Red color
        } else {
            buttonNativeSrtla.setText("Start Native SRTLA");
            buttonNativeSrtla.setBackgroundColor(0xFF4CAF50); // Green color
        }
    }
    
    /**
     * Get real network interface IP addresses from the device
     */
    private java.util.List<String> getRealNetworkIPs() {
        java.util.List<String> ips = new java.util.ArrayList<>();
        
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = 
                    networkInterface.getInetAddresses();
                
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    
                    // Only IPv4, not loopback, not link-local
                    if (address instanceof java.net.Inet4Address && 
                        !address.isLoopbackAddress() && 
                        !address.isLinkLocalAddress()) {
                        
                        String ip = address.getHostAddress();
                        ips.add(ip);
                        Log.i("MainActivity", "Found network IP: " + ip + 
                              " on interface: " + networkInterface.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error getting network IPs", e);
        }
        
        return ips;
    }
    
    // Algorithm toggle methods moved to SettingsActivity
}
