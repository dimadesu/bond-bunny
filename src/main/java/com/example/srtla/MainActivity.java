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
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import android.content.res.Configuration;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.view.View;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1001;
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static boolean hasPostedStartupNotification = false;
    private static final String PREF_SRTLA_HOST = "srtla_host";
    private static final String PREF_SRTLA_PORT = "srtla_port";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_STREAM_ID = "stream_id";
    
    private TextView textStatus;
    private TextView textError;
    private TextView textTotalBitrate;
    private LinearLayout connectionsContainer;
    private TextView textNoConnections;
    private Button buttonAbout;
    private Button buttonSettings;
    private Button buttonUrlBuilder;
    private Button buttonNativeSrtla;
    private boolean serviceRunning = false;
    private android.os.Handler uiHandler = new android.os.Handler();
    
    // Error receiver for service errors
    private BroadcastReceiver errorReceiver;
    
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
    
    // Add retry status receiver
    private final BroadcastReceiver retryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int retryCount = intent.getIntExtra("retry_count", 0);
            boolean isRetrying = intent.getBooleanExtra("is_retrying", false);
            boolean isConnected = intent.getBooleanExtra("is_connected", false);
            boolean isInitial = intent.getBooleanExtra("is_initial", false);
            
            runOnUiThread(() -> {
                TextView textTotalBitrate = findViewById(R.id.text_total_bitrate);
                
                if (isConnected) {
                    // Connected - hide retry status
                    textTotalBitrate.setVisibility(View.GONE);
                } else if (isRetrying) {
                    // Retrying
                    String message = String.format("Reconnecting... (attempt %d)", retryCount);
                    textTotalBitrate.setText(message);
                    textTotalBitrate.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                    textTotalBitrate.setVisibility(View.VISIBLE);
                } else if (isInitial) {
                    // Initial connection attempt
                    textTotalBitrate.setText("Connecting to SRTLA server...");
                    textTotalBitrate.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                    textTotalBitrate.setVisibility(View.VISIBLE);
                }
            });
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
        
        // Restore service state if activity was recreated
        checkServiceState();
    }
    
    /**
     * Check if the SRTLA service is actually running and update UI accordingly
     */
    private void checkServiceState() {
        // Check if the service is running
        serviceRunning = NativeSrtlaService.isServiceRunning();
        updateUI();
        
        if (serviceRunning || NativeSrtlaService.isServiceRunning()) {
            Log.i("MainActivity", "checkServiceState: Service running, starting stats updates");
            startStatsUpdates();
        } else {
            Log.i("MainActivity", "checkServiceState: No services running");
        }
    }
    
    private void initViews() {
        textStatus = findViewById(R.id.text_status);
        textError = findViewById(R.id.text_error);
        textTotalBitrate = findViewById(R.id.text_total_bitrate);
        connectionsContainer = findViewById(R.id.connections_container);
        textNoConnections = findViewById(R.id.text_no_connections);
        buttonAbout = findViewById(R.id.button_about);
        buttonSettings = findViewById(R.id.button_settings);
        buttonUrlBuilder = findViewById(R.id.button_url_builder);
        buttonNativeSrtla = findViewById(R.id.button_native_srtla);
        
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
    }
    
    private void updateUI() {
        if (serviceRunning) {
            textStatus.setText("Status: running");
        } else {
            textStatus.setText("Status: stopped");
            // Only clear connection stats if native SRTLA is also not running
            if (!NativeSrtlaService.isServiceRunning()) {
                Log.i("MainActivity", "updateUI: Clearing connection stats - no services running");
                clearConnectionsDisplay();
            } else {
                Log.i("MainActivity", "updateUI: Not clearing connection stats - native SRTLA still running");
            }
        }
    }
    
    private void clearConnectionsDisplay() {
        // Remove all dynamically added connection views
        connectionsContainer.removeAllViews();
        
        // Hide total bitrate when no connections
        textTotalBitrate.setVisibility(android.view.View.GONE);
        
        // Show the "no connections" message
        textNoConnections.setVisibility(android.view.View.VISIBLE);
        connectionsContainer.addView(textNoConnections);
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
                // Log.i("MainActivity", "Stats update tick - serviceRunning=" + serviceRunning + ", nativeRunning=" + NativeSrtlaService.isServiceRunning());
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
        }
        // Always clear stats when explicitly stopping updates
        Log.i("MainActivity", "Clearing stats display");
        clearConnectionsDisplay();
    }
    
    private void updateConnectionStats() {
        long currentTime = System.currentTimeMillis();
        
        // Check if native SRTLA is running and show its stats instead
        if (NativeSrtlaService.isServiceRunning()) {
            String nativeStats = NativeSrtlaService.getNativeStats();
            
            // Parse and display connection items
            parseAndDisplayConnections(nativeStats);
            
            textStatus.setText("✅ Service is running");
        }
        
        // Periodically refresh native SRTLA UI state (handles crashes)
        updateNativeSrtlaUI();
    }
    
    private void parseAndDisplayConnections(String statsText) {
        // Handle empty or no-connection state
        if (statsText == null || statsText.isEmpty() || !statsText.contains("Total bitrate:")) {
            // Don't clear if we're showing a retry/connecting status
            TextView textTotalBitrate = findViewById(R.id.text_total_bitrate);
            if (textTotalBitrate != null && textTotalBitrate.getVisibility() == View.VISIBLE) {
                String text = textTotalBitrate.getText().toString();
                if (text.contains("Connecting") || text.contains("Reconnecting")) {
                    // Keep the status message, just clear the connection list
                    connectionsContainer.removeAllViews();
                    textNoConnections.setVisibility(View.GONE);
                    return;
                }
            }
            
            clearConnectionsDisplay();
            return;
        }
        
        // Reset text color when connection is established
        textTotalBitrate.setTextColor(getResources().getColor(android.R.color.primary_text_dark));
        textStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        
        // Clear existing views
        connectionsContainer.removeAllViews();
        textNoConnections.setVisibility(android.view.View.GONE);
        
        // Parse the stats text to extract connection information
        // Format: "Total bitrate: X.X Mbps\n\nWIFI\n  Bitrate: ... \n  Window: ...\n  Packets in-flight: ...\n\n..."
        String[] sections = statsText.split("\n\n");
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        
        // Extract and display total bitrate from first section
        if (sections.length > 0 && sections[0].startsWith("Total bitrate:")) {
            textTotalBitrate.setText(sections[0]);
            textTotalBitrate.setVisibility(android.view.View.VISIBLE);
        }
        
        for (String section : sections) {
            if (section.trim().isEmpty() || section.startsWith("Total bitrate:")) {
                continue; // Skip empty sections and total bitrate line
            }
            
            // Parse connection section
            try {
                String[] lines = section.trim().split("\n");
                if (lines.length < 4) continue; // Need at least 4 lines: type, bitrate, window, in-flight
                
                // First line is network type
                String networkType = lines[0].trim();
                
                // Parse bitrate line: "  Bitrate: 45.2 Mbps 45%"
                String bitrateLine = lines[1].trim();
                String bitrate = "N/A";
                String load = "N/A";
                if (bitrateLine.startsWith("Bitrate:")) {
                    String bitrateData = bitrateLine.substring(8).trim(); // Remove "Bitrate:"
                    String[] bitrateParts = bitrateData.split(" ");
                    if (bitrateParts.length >= 2) {
                        bitrate = bitrateParts[0] + " " + bitrateParts[1]; // e.g., "45.2 Mbps"
                    }
                    if (bitrateParts.length >= 3) {
                        load = bitrateParts[2]; // e.g., "45%"
                    }
                }
                
                // Parse window line: "  Window: 15234"
                String windowLine = lines[2].trim();
                int windowSize = 0;
                if (windowLine.startsWith("Window:")) {
                    windowSize = Integer.parseInt(windowLine.substring(7).trim());
                }
                
                // Parse in-flight line: "  Packets in-flight: 125"
                String inFlightLine = lines[3].trim();
                int inFlight = 0;
                if (inFlightLine.startsWith("Packets in-flight:")) {
                    inFlight = Integer.parseInt(inFlightLine.substring(18).trim());
                }
                
                // Determine if connection is active (has bitrate > 0 or in-flight packets)
                boolean isActive = inFlight > 0 || (bitrate != null && !bitrate.equals("0.00 Mbps") && !bitrate.equals("N/A"));
                
                // Create connection item view
                android.view.View connectionView = inflater.inflate(R.layout.connection_item, connectionsContainer, false);
                
                // Set network type with display formatting
                TextView networkTypeView = connectionView.findViewById(R.id.connection_network_type);
                String displayName = networkType.equals("WIFI") ? "WI-FI" : networkType;
                networkTypeView.setText(displayName);
                
                // Set status
                TextView statusView = connectionView.findViewById(R.id.connection_status);
                if (isActive) {
                    statusView.setText("ACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#28a745"));
                } else {
                    statusView.setText("INACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#dc3545"));
                }
                
                // Set window bar
                WindowBarView windowBar = connectionView.findViewById(R.id.window_bar);
                windowBar.setWindowData(windowSize, isActive);
                
                // Set stats text
                TextView statsTextView = connectionView.findViewById(R.id.connection_stats_text);
                String statsDisplay = String.format(
                    "Bitrate: %s  %s\nPackets in-flight: %,d\nWindow: %,d / 60,000",
                    bitrate, load, inFlight, windowSize
                );
                statsTextView.setText(statsDisplay);
                
                // Add view to container
                connectionsContainer.addView(connectionView);
                
            } catch (Exception e) {
                Log.e("MainActivity", "Error parsing connection section: " + section, e);
            }
        }
        
        // If no connections were added, show the "no connections" message
        if (connectionsContainer.getChildCount() == 0) {
            clearConnectionsDisplay();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Register receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(errorReceiver, 
            new IntentFilter("srtla-error"));
        LocalBroadcastManager.getInstance(this).registerReceiver(networkChangeReceiver, 
            new IntentFilter("network-changed"));
        // Register retry status receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(retryReceiver,
            new IntentFilter("srtla-retry-status"));
        
        // Always check service state when resuming (handles rotation, app switching, etc.)
        checkServiceState();
        // Also check native SRTLA state
        updateNativeSrtlaUI();
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
        
        // Unregister error receiver
        try {
            if (errorReceiver != null) {
                unregisterReceiver(errorReceiver);
                Log.i("MainActivity", "Unregistered error receiver");
            }
        } catch (IllegalArgumentException e) {
            Log.w("MainActivity", "Error receiver was not registered");
        }
        
        // Unregister retry status receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(retryReceiver);
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Algorithm preferences are now managed in SettingsActivity
    }
    
    private void savePreferences() {
        // Algorithm preferences are now managed in SettingsActivity
        // No longer saving preferences in MainActivity
    }
    
    private void setupErrorReceiver() {
        errorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String errorMessage = intent.getStringExtra("error_message");
                if (errorMessage != null) {
                    showError(errorMessage);
                }
            }
        };
        
        IntentFilter errorFilter = new IntentFilter("com.example.srtla.ERROR");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(errorReceiver, errorFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(errorReceiver, errorFilter);
        }
        Log.i("MainActivity", "Registered error receiver");
    }
    
    private void showError(String errorMessage) {
        runOnUiThread(() -> {
            textError.setText(errorMessage);
            textError.setVisibility(TextView.VISIBLE);
            Log.e("MainActivity", "Showing error: " + errorMessage);
        });
    }
    
    private void clearError() {
        runOnUiThread(() -> {
            textError.setText("");
            textError.setVisibility(TextView.GONE);
        });
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
        // Only post once per app launch
        if (hasPostedStartupNotification) {
            return;
        }
        hasPostedStartupNotification = true;
        
        try {
            // Check for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission not granted, skipping startup notification");
                    return;
                }
            }
            
            final String channelId = NativeSrtlaService.CHANNEL_ID;
            final int notificationId = 1;

            // Ensure the shared notification channel exists. This is idempotent.
            NativeSrtlaService.createNotificationChannel(this);

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
    
    private void toggleNativeSrtla() {
        if (NativeSrtlaService.isServiceRunning()) {
            stopNativeSrtla();
        } else {
            startNativeSrtla();
        }
    }
    
    private void startNativeSrtla() {
        // Clear any previous error messages
        clearError();
        
        textStatus.setText("Starting service...");
        
        try {
            // Get configuration from preferences (similar to regular SRTLA service)
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String srtlaHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net").trim();
            String srtlaPort = prefs.getString(PREF_SRTLA_PORT, "5000").trim();
            String listenPort = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
            
            if (srtlaHost.isEmpty() || srtlaPort.isEmpty() || listenPort.isEmpty()) {
                showError("Please configure SRTLA settings first");
                textStatus.setText("❌ Please configure SRTLA settings first");
                return;
            }
            
            // Validate port numbers
            try {
                int srtlaPortNum = Integer.parseInt(srtlaPort);
                int listenPortNum = Integer.parseInt(listenPort);
                if (srtlaPortNum < 1 || srtlaPortNum > 65535 || listenPortNum < 1 || listenPortNum > 65535) {
                    showError("Port numbers must be between 1 and 65535");
                    textStatus.setText("❌ Invalid port number range");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid port number format");
                textStatus.setText("❌ Invalid port number format");
                return;
            }
            
            // Start the native SRTLA service
            NativeSrtlaService.startService(this, srtlaHost, srtlaPort, listenPort);
            
            textStatus.setText("⏳ Service starting...");
            Toast.makeText(this, "Native SRTLA service starting on port " + listenPort, Toast.LENGTH_LONG).show();
            
            // Poll service status intelligently instead of fixed delay
            pollServiceStartup(0);
            
        } catch (Exception e) {
            textStatus.setText("❌ Error starting service: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA service start error", e);
        }
    }
    
    /**
     * Poll service startup status with 500ms intervals
     * Times out after 3 seconds (6 attempts)
     */
    private void pollServiceStartup(int attemptCount) {
        final int maxAttempts = 6; // Maximum attempts (3 seconds total)
        
        if (attemptCount >= maxAttempts) {
            Log.i("MainActivity", "Service startup polling timed out after 3 seconds");
            textStatus.setText("❌ Service startup timed out");
            return;
        }
        
        // Check if service started successfully
        if (NativeSrtlaService.isServiceRunning()) {
            Log.i("MainActivity", "Service started successfully after " + attemptCount + " polling attempts");
            textStatus.setText("✅ Service is running");
            updateNativeSrtlaUI();
            startStatsUpdates();
            return;
        }
        
        // Check if service failed (error receiver would have been triggered)
        if (!textError.getText().toString().isEmpty()) {
            Log.i("MainActivity", "Service startup failed with error after " + attemptCount + " attempts");
            textStatus.setText("❌ Service failed to start");
            return;
        }
        
        // Continue polling - use 500ms intervals for 3 second timeout
        int delay = 500; // 500ms intervals (6 attempts = 3 seconds)
        
        uiHandler.postDelayed(() -> {
            Log.i("MainActivity", "Polling service startup - attempt " + (attemptCount + 1));
            pollServiceStartup(attemptCount + 1);
        }, delay);
    }
    
    private void stopNativeSrtla() {
        textStatus.setText("⏳ Stopping service...");
        
        try {
            NativeSrtlaService.stopService(this);
            Toast.makeText(this, "Native SRTLA service stopping", Toast.LENGTH_SHORT).show();
            
            // Stop stats updates and clear display immediately
            stopStatsUpdates();
            
            // Update UI after a short delay to allow service to stop
            uiHandler.postDelayed(() -> {
                updateNativeSrtlaUI();
                if (!NativeSrtlaService.isServiceRunning()) {
                    textStatus.setText("✅ Service stopped");
                } else {
                    textStatus.setText("⏳ Service stopping...");
                    // Try again after another delay
                    uiHandler.postDelayed(() -> {
                        updateNativeSrtlaUI();
                        if (!NativeSrtlaService.isServiceRunning()) {
                            textStatus.setText("✅ Service stopped");
                        } else {
                            textStatus.setText("⚠️ Service may still be running");
                        }
                    }, 2000);
                }
            }, 1500); // Wait 1.5 seconds for service to stop
            
        } catch (Exception e) {
            updateNativeSrtlaUI();
            textStatus.setText("❌ Error stopping service: " + e.getMessage());
            Log.e("MainActivity", "Native SRTLA service stop error", e);
        }
    }
    
    private void updateNativeSrtlaUI() {
        // Sync state before checking to ensure accuracy
        NativeSrtlaService.syncState();
        
        boolean isRunning = NativeSrtlaService.isServiceRunning();
        // Log.i("MainActivity", "updateNativeSrtlaUI: isRunning=" + isRunning);
        
        if (isRunning) {
            buttonNativeSrtla.setText("Stop Service");
            buttonNativeSrtla.setBackgroundColor(0xFFD32F2F); // Proper red color
            // Log.i("MainActivity", "UI updated to STOP state");
            
            // Update connection window visualization with native data
        } else {
            buttonNativeSrtla.setText("Start Service");
            buttonNativeSrtla.setBackgroundColor(0xFF4CAF50); // Green color
            
            // Clear connections display when not running
            clearConnectionsDisplay();
        }
    }
}

