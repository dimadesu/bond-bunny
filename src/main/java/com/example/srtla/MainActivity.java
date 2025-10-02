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
    private static final String PREF_STICKINESS_ENABLED = "stickiness_enabled";
    private static final String PREF_QUALITY_SCORING_ENABLED = "quality_scoring_enabled";
    private static final String PREF_NETWORK_PRIORITY_ENABLED = "network_priority_enabled";
    private static final String PREF_EXPLORATION_ENABLED = "exploration_enabled";
    private static final String PREF_CLASSIC_MODE = "classic_mode";
    
    private EditText editSrtlaReceiverHost;
    private EditText editSrtlaReceiverPort;
    private Button buttonStart;
    private Button buttonStop;
    private TextView textStatus;
    private TextView textNetworks;
    private TextView textConnectionStats;
    private ConnectionWindowView connectionWindowView;
    private Button buttonAbout;
    private Button buttonSettings;
    private Button buttonUrlBuilder;
    private Button buttonToggleStickiness;
    private Button buttonToggleQualityScoring;
    private Button buttonToggleNetworkPriority;
    private Button buttonToggleExploration;
    private Button buttonToggleClassicMode;
    private ConnectivityManager connectivityManager;
    private boolean serviceRunning = false;
    private boolean stickinessEnabled = true; // Default to enabled
    private boolean qualityScoringEnabled = true; // Default to enabled
    private boolean networkPriorityEnabled = true; // Default to enabled  
    private boolean explorationEnabled = true; // Default to enabled
    private boolean classicMode = false; // Default to enhanced mode
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
        buttonToggleStickiness = findViewById(R.id.button_toggle_stickiness);
        buttonToggleQualityScoring = findViewById(R.id.button_toggle_quality_scoring);
        buttonToggleNetworkPriority = findViewById(R.id.button_toggle_network_priority);
        buttonToggleExploration = findViewById(R.id.button_toggle_exploration);
        buttonToggleClassicMode = findViewById(R.id.button_toggle_classic_mode);
        
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

        // Set up advanced feature toggle buttons
        buttonToggleStickiness.setOnClickListener(v -> toggleConnectionStickiness());
        buttonToggleQualityScoring.setOnClickListener(v -> toggleQualityScoring());
        buttonToggleNetworkPriority.setOnClickListener(v -> toggleNetworkPriority());
        buttonToggleExploration.setOnClickListener(v -> toggleExploration());
        buttonToggleClassicMode.setOnClickListener(v -> toggleClassicMode());
        updateAdvancedFeatureButtons();
        
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

        // Apply all current feature settings to the service
        EnhancedSrtlaService.setStickinessEnabled(stickinessEnabled);
        EnhancedSrtlaService.setQualityScoringEnabled(qualityScoringEnabled);
        EnhancedSrtlaService.setNetworkPriorityEnabled(networkPriorityEnabled);
        EnhancedSrtlaService.setExplorationEnabled(explorationEnabled);
        EnhancedSrtlaService.setClassicMode(classicMode);

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
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        stopStatsUpdates();
        // Save current form values when app is paused
        savePreferences();
    }
    
//    @Override
//    public void onConfigurationChanged(Configuration newConfig) {
//        super.onConfigurationChanged(newConfig);
//        // Activity won't be destroyed, so we just need to update the network info
//        // which might change due to orientation (some devices switch networks)
//    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        stickinessEnabled = prefs.getBoolean(PREF_STICKINESS_ENABLED, false);
        qualityScoringEnabled = prefs.getBoolean(PREF_QUALITY_SCORING_ENABLED, true);
        networkPriorityEnabled = prefs.getBoolean(PREF_NETWORK_PRIORITY_ENABLED, true);
        explorationEnabled = prefs.getBoolean(PREF_EXPLORATION_ENABLED, false);
        classicMode = prefs.getBoolean(PREF_CLASSIC_MODE, false);
        
        updateAdvancedFeatureButtons();
    }
    
    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putBoolean(PREF_STICKINESS_ENABLED, stickinessEnabled);
        editor.putBoolean(PREF_QUALITY_SCORING_ENABLED, qualityScoringEnabled);
        editor.putBoolean(PREF_NETWORK_PRIORITY_ENABLED, networkPriorityEnabled);
        editor.putBoolean(PREF_EXPLORATION_ENABLED, explorationEnabled);
        editor.putBoolean(PREF_CLASSIC_MODE, classicMode);
        
        editor.apply();
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
                .setSmallIcon(R.mipmap.ic_launcher)
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
    
    private void toggleConnectionStickiness() {
        stickinessEnabled = !stickinessEnabled;
        updateAdvancedFeatureButtons();
        savePreferences(); // Save the setting
        
        // Apply the setting to the service if it's running
        if (serviceRunning) {
            EnhancedSrtlaService.setStickinessEnabled(stickinessEnabled);
            Toast.makeText(this, 
                stickinessEnabled ? "Connection stickiness enabled" : "Connection stickiness disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleQualityScoring() {
        qualityScoringEnabled = !qualityScoringEnabled;
        updateAdvancedFeatureButtons();
        savePreferences();
        
        if (serviceRunning) {
            EnhancedSrtlaService.setQualityScoringEnabled(qualityScoringEnabled);
            Toast.makeText(this, 
                qualityScoringEnabled ? "Quality-based scoring enabled" : "Quality-based scoring disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleNetworkPriority() {
        networkPriorityEnabled = !networkPriorityEnabled;
        updateAdvancedFeatureButtons();
        savePreferences();
        
        if (serviceRunning) {
            EnhancedSrtlaService.setNetworkPriorityEnabled(networkPriorityEnabled);
            Toast.makeText(this, 
                networkPriorityEnabled ? "Network priority scaling enabled" : "Network priority scaling disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleExploration() {
        explorationEnabled = !explorationEnabled;
        updateAdvancedFeatureButtons();
        savePreferences();
        
        if (serviceRunning) {
            EnhancedSrtlaService.setExplorationEnabled(explorationEnabled);
            Toast.makeText(this, 
                explorationEnabled ? "Connection exploration enabled" : "Connection exploration disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleClassicMode() {
        classicMode = !classicMode;
        updateAdvancedFeatureButtons();
        savePreferences();

        if (serviceRunning) {
            EnhancedSrtlaService.setClassicMode(classicMode);
            EnhancedSrtlaService.setQualityScoringEnabled(qualityScoringEnabled);
            EnhancedSrtlaService.setNetworkPriorityEnabled(networkPriorityEnabled);
            EnhancedSrtlaService.setExplorationEnabled(explorationEnabled);
            EnhancedSrtlaService.setStickinessEnabled(stickinessEnabled);
            
            Toast.makeText(this, 
                classicMode ? "Classic SRTLA algorithm enabled - all enhancements disabled" : 
                           "Enhanced Android mode enabled - all features restored", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void updateAdvancedFeatureButtons() {
        updateButtonState(buttonToggleStickiness, stickinessEnabled && !classicMode);
        updateButtonState(buttonToggleQualityScoring, qualityScoringEnabled && !classicMode);
        updateButtonState(buttonToggleNetworkPriority, networkPriorityEnabled && !classicMode);
        updateButtonState(buttonToggleExploration, explorationEnabled && !classicMode);
        updateButtonState(buttonToggleClassicMode, classicMode);
        
        // Disable individual feature buttons when in classic mode
        buttonToggleStickiness.setEnabled(!classicMode);
        buttonToggleQualityScoring.setEnabled(!classicMode);
        buttonToggleNetworkPriority.setEnabled(!classicMode);
        buttonToggleExploration.setEnabled(!classicMode);
    }
    
    private void updateButtonState(Button button, boolean enabled) {
        if (enabled) {
            button.setText("ON");
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(android.R.color.holo_green_light)));
        } else {
            button.setText("OFF");
            button.setBackgroundTintList(null);
        }
    }
}
