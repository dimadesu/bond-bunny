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
import java.util.ArrayList;
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
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;

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
    
    // Network change receiver
    private BroadcastReceiver networkChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.i("MainActivity", "Network change received: " + reason);
            
            // Immediately update stats when network changes
            updateConnectionStats();
        }
    };
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
        
        if (serviceRunning || NativeSrtlaService.isServiceRunning()) {
            Log.i("MainActivity", "checkServiceState: Service running, starting stats updates");
            startStatsUpdates();
        } else {
            Log.i("MainActivity", "checkServiceState: No services running");
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
            // Only clear connection stats if native SRTLA is also not running
            if (!NativeSrtlaService.isServiceRunning()) {
                Log.i("MainActivity", "updateUI: Clearing connection stats - no services running");
                textConnectionStats.setText("No active connections");
                connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
            } else {
                Log.i("MainActivity", "updateUI: Not clearing connection stats - native SRTLA still running");
            }
        }
    }
    
    
    private void startStatsUpdates() {
        Log.i("MainActivity", "Starting stats updates");
        
        // Stop any existing stats updates first to avoid multiple concurrent loops
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
        }
        
        statsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                Log.i("MainActivity", "Stats update tick - serviceRunning=" + serviceRunning + ", nativeRunning=" + NativeSrtlaService.isServiceRunning());
                if (serviceRunning || NativeSrtlaService.isServiceRunning()) {
                    updateConnectionStats();
                    uiHandler.postDelayed(this, 1000); // Update every second
                } else {
                    Log.i("MainActivity", "No services running, stopping stats updates");
                }
            }
        };
        uiHandler.post(statsUpdateRunnable);
    }
    
    private void stopStatsUpdates() {
        Log.i("MainActivity", "stopStatsUpdates called - serviceRunning=" + serviceRunning + ", nativeRunning=" + NativeSrtlaService.isServiceRunning());
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
            // Only clear stats if no services are running
            if (!serviceRunning && !NativeSrtlaService.isServiceRunning()) {
                Log.i("MainActivity", "Clearing stats display - no services running");
                textConnectionStats.setText("No active connections");
                connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
            } else {
                Log.i("MainActivity", "Not clearing stats - service still running");
            }
        }
    }
    
    private void updateConnectionStats() {
        long currentTime = System.currentTimeMillis();
        Log.i("MainActivity", "updateConnectionStats called at " + currentTime);
        
        // Check if native SRTLA is running and show its stats instead
        if (NativeSrtlaService.isServiceRunning()) {
            Log.i("MainActivity", "Native SRTLA service is running, getting native stats");
            String nativeStats = NativeSrtlaService.getNativeStats();
            Log.i("MainActivity", "Native stats: " + nativeStats);
            textConnectionStats.setText(nativeStats);
            
            // Also update the status to show we're getting stats (for visibility test)
            textStatus.setText("✅ Native SRTLA running - Stats updating at " + (currentTime % 100000));
            
            // Clear the connection window view for native SRTLA (simplified UI)
            connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
        } else {
            // Show Java SRTLA stats
            String stats = EnhancedSrtlaService.getConnectionStatistics();
            
            // Add logging performance stats
            String logStats = SrtlaLogger.getPerformanceStats();
            String combinedStats = stats + "\n\n" + logStats;
            
            textConnectionStats.setText(combinedStats);
            
            // Update window visualization
            List<ConnectionWindowView.ConnectionWindowData> windowData = 
                EnhancedSrtlaService.getConnectionWindowData();
            connectionWindowView.updateConnectionData(windowData);
        }
        
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
        
        // Register network change receiver
        IntentFilter networkFilter = new IntentFilter("com.example.srtla.NETWORK_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkChangeReceiver, networkFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(networkChangeReceiver, networkFilter);
        }
        Log.i("MainActivity", "Registered network change receiver");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Don't stop stats updates if services are still running
        if (!serviceRunning && !NativeSrtlaService.isServiceRunning()) {
            Log.i("MainActivity", "onPause: No services running, stopping stats updates");
            stopStatsUpdates();
        } else {
            Log.i("MainActivity", "onPause: Services still running, keeping stats updates active");
        }
        // Save current form values when app is paused
        savePreferences();
        
        // Unregister network change receiver
        try {
            unregisterReceiver(networkChangeReceiver);
            Log.i("MainActivity", "Unregistered network change receiver");
        } catch (IllegalArgumentException e) {
            Log.w("MainActivity", "Network change receiver was not registered");
        }
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
    
    // Native SRTLA is now handled by NativeSrtlaService using NativeSrtlaJni
    
    private void toggleNativeSrtla() {
        if (NativeSrtlaService.isServiceRunning()) {
            stopNativeSrtla();
        } else {
            startNativeSrtla();
        }
    }
    
    private void startNativeSrtla() {
        textStatus.setText("Starting native SRTLA service...");
        
        try {
            // Get configuration from preferences (similar to regular SRTLA service)
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String srtlaHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net").trim();
            String srtlaPort = prefs.getString(PREF_SRTLA_PORT, "5000").trim();
            String listenPort = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
            
            if (srtlaHost.isEmpty() || srtlaPort.isEmpty() || listenPort.isEmpty()) {
                textStatus.setText("❌ Please configure SRTLA settings first");
                return;
            }
            
            // Start the native SRTLA service
            NativeSrtlaService.startService(this, srtlaHost, srtlaPort, listenPort);
            
            textStatus.setText("⏳ Native SRTLA service starting...");
            Toast.makeText(this, "Native SRTLA service starting on port " + listenPort, Toast.LENGTH_LONG).show();
            
            // Update UI after a short delay to allow service to start
            uiHandler.postDelayed(() -> {
                Log.i("MainActivity", "Delayed callback: checking native SRTLA status");
                updateNativeSrtlaUI();
                if (NativeSrtlaService.isServiceRunning()) {
                    Log.i("MainActivity", "Native SRTLA is running, starting stats updates");
                    textStatus.setText("✅ Native SRTLA service running");
                    // Start stats updates for native SRTLA
                    startStatsUpdates();
                } else {
                    Log.i("MainActivity", "Native SRTLA is not running");
                    textStatus.setText("❌ Native SRTLA service failed to start");
                }
            }, 2000); // Wait 2 seconds for service to start
            
        } catch (Exception e) {
            textStatus.setText("❌ Error starting native SRTLA service: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA service start error", e);
        }
    }
    
    private void stopNativeSrtla() {
        textStatus.setText("⏳ Stopping native SRTLA service...");
        
        try {
            NativeSrtlaService.stopService(this);
            Toast.makeText(this, "Native SRTLA service stopping", Toast.LENGTH_SHORT).show();
            
            // Update UI after a short delay to allow service to stop
            uiHandler.postDelayed(() -> {
                updateNativeSrtlaUI();
                if (!NativeSrtlaService.isServiceRunning()) {
                    textStatus.setText("✅ Native SRTLA service stopped");
                } else {
                    textStatus.setText("⏳ Native SRTLA service stopping...");
                    // Try again after another delay
                    uiHandler.postDelayed(() -> {
                        updateNativeSrtlaUI();
                        if (!NativeSrtlaService.isServiceRunning()) {
                            textStatus.setText("✅ Native SRTLA service stopped");
                        } else {
                            textStatus.setText("⚠️ Native SRTLA service may still be running");
                        }
                    }, 2000);
                }
            }, 1500); // Wait 1.5 seconds for service to stop
            
        } catch (Exception e) {
            updateNativeSrtlaUI();
            textStatus.setText("❌ Error stopping native SRTLA service: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA service stop error", e);
        }
    }
    
    private void updateNativeSrtlaUI() {
        // Sync state before checking to ensure accuracy
        NativeSrtlaService.syncState();
        
        boolean isRunning = NativeSrtlaService.isServiceRunning();
        Log.i("MainActivity", "updateNativeSrtlaUI: isRunning=" + isRunning);
        
        if (isRunning) {
            buttonNativeSrtla.setText("Stop Native SRTLA");
            buttonNativeSrtla.setBackgroundColor(0xFFFF5722); // Red color
            Log.i("MainActivity", "UI updated to STOP state");
            
            // Update connection window visualization with native data
            updateNativeConnectionWindows();
        } else {
            buttonNativeSrtla.setText("Start Native SRTLA");
            buttonNativeSrtla.setBackgroundColor(0xFF4CAF50); // Green color
            Log.i("MainActivity", "UI updated to START state");
            
            // Clear connection windows when not running
            connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
        }
    }
    
    private void updateNativeConnectionWindows() {
        try {
            // Get native connection data
            ConnectionBitrateData[] nativeConnections = NativeSrtlaJni.getAllConnectionBitrates();
            
            // Convert to ConnectionWindowData format (keeping it simple)
            List<ConnectionWindowView.ConnectionWindowData> windowData = new ArrayList<>();
            
            for (ConnectionBitrateData conn : nativeConnections) {
                // Use actual native data for accurate visualization
                ConnectionWindowView.ConnectionWindowData data = new ConnectionWindowView.ConnectionWindowData(
                    conn.connectionType,           // networkType (WIFI, CELLULAR, etc.)
                    conn.windowSize,               // window (actual native window size in packets)
                    conn.inFlightPackets,          // inFlightPackets (actual native in-flight count)
                    conn.loadPercentage,           // score (using load percentage)
                    conn.bitrateMbps > 0.1,        // isActive (active if bitrate > 0.1 Mbps)
                    false,                         // isSelected (keep simple, always false)
                    0,                             // rtt (0 since we don't have real RTT data)
                    "ACTIVE",                      // state (keep simple)
                    conn.bitrateMbps * 1000000     // bitrateBps (convert Mbps to bps)
                );
                windowData.add(data);
            }
            
            // Update the connection window view
            connectionWindowView.updateConnectionData(windowData);
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating native connection windows", e);
            // Fallback to empty data on error
            connectionWindowView.updateConnectionData(new java.util.ArrayList<>());
        }
    }
    
    // Network IP detection is now handled by NativeSrtlaService
    
    // Algorithm toggle methods moved to SettingsActivity
}
