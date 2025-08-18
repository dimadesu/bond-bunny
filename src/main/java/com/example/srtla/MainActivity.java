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
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import android.content.res.Configuration;

public class MainActivity extends Activity {
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
    private EditText editSrtListenPort;
    private EditText editStreamId;
    private Button buttonStart;
    private Button buttonStop;
    private TextView textStatus;
    private TextView textNetworks;
    private TextView textConnectionStats;
    private ConnectionWindowView connectionWindowView;
    private TextView textSrtUrlLocalhost;
    private TextView textSrtUrlWifi;
    private Button buttonCopyLocalhost;
    private Button buttonCopyWifi;
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
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        
        buttonStart.setOnClickListener(v -> startSrtlaService());
        buttonStop.setOnClickListener(v -> stopSrtlaService());
        
        // Restore service state if activity was recreated
        checkServiceState();
        
        updateNetworkInfo();
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
        editSrtlaReceiverHost = findViewById(R.id.edit_server_host);
        editSrtlaReceiverPort = findViewById(R.id.edit_server_port);
        editSrtListenPort = findViewById(R.id.edit_listen_port);
        editStreamId = findViewById(R.id.edit_stream_id);
        buttonStart = findViewById(R.id.button_start);
        buttonStop = findViewById(R.id.button_stop);
        textStatus = findViewById(R.id.text_status);
        textNetworks = findViewById(R.id.text_networks);
        textConnectionStats = findViewById(R.id.text_connection_stats);
        connectionWindowView = findViewById(R.id.connection_window_view);
        textSrtUrlLocalhost = findViewById(R.id.text_srt_url_localhost);
        textSrtUrlWifi = findViewById(R.id.text_srt_url_wifi);
        buttonCopyLocalhost = findViewById(R.id.button_copy_localhost);
        buttonCopyWifi = findViewById(R.id.button_copy_wifi);
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
        
        // Set up copy button listeners
        buttonCopyLocalhost.setOnClickListener(v -> copyToClipboard("Localhost SRT URL", textSrtUrlLocalhost.getText().toString()));
        buttonCopyWifi.setOnClickListener(v -> copyToClipboard("WiFi SRT URL", textSrtUrlWifi.getText().toString()));
        
        // Set up stickiness toggle button
        buttonToggleStickiness.setOnClickListener(v -> toggleConnectionStickiness());
        updateStickinessButtonText();
        
        // Set up advanced feature toggle buttons
        buttonToggleQualityScoring.setOnClickListener(v -> toggleQualityScoring());
        buttonToggleNetworkPriority.setOnClickListener(v -> toggleNetworkPriority());
        buttonToggleExploration.setOnClickListener(v -> toggleExploration());
        buttonToggleClassicMode.setOnClickListener(v -> toggleClassicMode());
        updateAdvancedFeatureButtons();
        
        // Add text watchers to update SRT URLs when port or stream ID changes
        TextWatcher urlUpdateWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                updateSrtUrls();
            }
        };
        
        editSrtListenPort.addTextChangedListener(urlUpdateWatcher);
        editStreamId.addTextChangedListener(urlUpdateWatcher);
        
        // Load saved preferences or use default values
        loadPreferences();
        
        // Initial SRT URL update
        updateSrtUrls();
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save current form values and service state
        outState.putString("srtla_host", editSrtlaReceiverHost.getText().toString());
        outState.putString("srtla_port", editSrtlaReceiverPort.getText().toString());
        outState.putString("listen_port", editSrtListenPort.getText().toString());
        outState.putString("stream_id", editStreamId.getText().toString());
        outState.putBoolean("service_running", serviceRunning);
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // Restore form values
        if (savedInstanceState != null) {
            editSrtlaReceiverHost.setText(savedInstanceState.getString("srtla_host", "au.srt.belabox.net"));
            editSrtlaReceiverPort.setText(savedInstanceState.getString("srtla_port", "5000"));
            editSrtListenPort.setText(savedInstanceState.getString("listen_port", "6000"));
            editStreamId.setText(savedInstanceState.getString("stream_id", ""));
            // Service state will be checked in onResume()
        }
    }

    private void startSrtlaService() {
        String srtlaReceiverHost = editSrtlaReceiverHost.getText().toString().trim();
        String srtlaReceiverPort = editSrtlaReceiverPort.getText().toString().trim();
        String srtListenPort = editSrtListenPort.getText().toString().trim();
        
        if (srtlaReceiverHost.isEmpty() || srtlaReceiverPort.isEmpty() || srtListenPort.isEmpty()) {
            Toast.makeText(this, "Please fill SRTLA receiver host, port, and SRT listen port", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save preferences when starting service
        savePreferences();
        
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
            "Android SRTLA Sender Started\n" +
            "• Listening for SRT on 0.0.0.0:" + srtListenPort + "\n" +
            "• Forwarding to " + srtlaReceiverHost + ":" + srtlaReceiverPort, 
            Toast.LENGTH_LONG).show();
    }
    
    private void stopSrtlaService() {
        Intent serviceIntent = new Intent(this, EnhancedSrtlaService.class);
        stopService(serviceIntent);
        
        serviceRunning = false;
        updateUI();
        stopStatsUpdates();
        Toast.makeText(this, "Enhanced SRTLA Service Stopped", Toast.LENGTH_SHORT).show();
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
    
    private void updateNetworkInfo() {
        StringBuilder networkInfo = new StringBuilder("IP Addresses of this device:\n");
        
        // Get actual device IP addresses
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            int interfaceCount = 0;
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                            interfaceCount++;
                            String interfaceName = networkInterface.getDisplayName();
                            String ipAddress = address.getHostAddress();
                            
                            networkInfo.append("• ").append(ipAddress);
                            
                            // Try to identify the network type
                            if (interfaceName.toLowerCase().contains("wlan") || 
                                interfaceName.toLowerCase().contains("wifi")) {
                                networkInfo.append(" (WiFi)");
                            } else if (interfaceName.toLowerCase().contains("rmnet") || 
                                      interfaceName.toLowerCase().contains("mobile") ||
                                      interfaceName.toLowerCase().contains("cellular")) {
                                networkInfo.append(" (Cellular)");
                            } else if (interfaceName.toLowerCase().contains("eth")) {
                                networkInfo.append(" (Ethernet)");
                            } else {
                                networkInfo.append(" (").append(interfaceName).append(")");
                            }

                            networkInfo.append("\n");
                        }
                    }
                }
            }
            
            if (interfaceCount == 0) {
                networkInfo.append("No external IP addresses found\n");
            }
            
        } catch (Exception e) {
            networkInfo.append("Error discovering addresses: ").append(e.getMessage()).append("\n");
        }
        
        // Add Android ConnectivityManager info
        networkInfo.append("\nConnected Networks:\n");
        Network[] networks = connectivityManager.getAllNetworks();
        int availableNetworks = 0;
        
        for (Network network : networks) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                availableNetworks++;
                
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    networkInfo.append("• WiFi Network (Internet)\n");
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    networkInfo.append("• Cellular Network (Internet)\n");
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    networkInfo.append("• Ethernet Network (Internet)\n");
                }
            }
        }
        
        if (availableNetworks == 0) {
            networkInfo.append("• No internet networks available\n");
        }
        
        textNetworks.setText(networkInfo.toString());
        
        // Update SRT URLs when network info changes (WiFi IP might have changed)
        updateSrtUrls();
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
        updateNetworkInfo();
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
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Activity won't be destroyed, so we just need to update the network info
        // which might change due to orientation (some devices switch networks)
        updateNetworkInfo();
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        // Load saved values or use defaults
        String savedHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net");
        String savedSrtlaPort = prefs.getString(PREF_SRTLA_PORT, "5000");
        String savedListenPort = prefs.getString(PREF_LISTEN_PORT, "6000");
        String savedStreamId = prefs.getString(PREF_STREAM_ID, "");
        stickinessEnabled = prefs.getBoolean(PREF_STICKINESS_ENABLED, false);
        qualityScoringEnabled = prefs.getBoolean(PREF_QUALITY_SCORING_ENABLED, true);
        networkPriorityEnabled = prefs.getBoolean(PREF_NETWORK_PRIORITY_ENABLED, true);
        explorationEnabled = prefs.getBoolean(PREF_EXPLORATION_ENABLED, false);
        classicMode = prefs.getBoolean(PREF_CLASSIC_MODE, false);
        
        editSrtlaReceiverHost.setText(savedHost);
        editSrtlaReceiverPort.setText(savedSrtlaPort);
        editSrtListenPort.setText(savedListenPort);
        editStreamId.setText(savedStreamId);
        updateStickinessButtonText();
        updateAdvancedFeatureButtons();
    }
    
    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString(PREF_SRTLA_HOST, editSrtlaReceiverHost.getText().toString().trim());
        editor.putString(PREF_SRTLA_PORT, editSrtlaReceiverPort.getText().toString().trim());
        editor.putString(PREF_LISTEN_PORT, editSrtListenPort.getText().toString().trim());
        editor.putString(PREF_STREAM_ID, editStreamId.getText().toString().trim());
        editor.putBoolean(PREF_STICKINESS_ENABLED, stickinessEnabled);
        editor.putBoolean(PREF_QUALITY_SCORING_ENABLED, qualityScoringEnabled);
        editor.putBoolean(PREF_NETWORK_PRIORITY_ENABLED, networkPriorityEnabled);
        editor.putBoolean(PREF_EXPLORATION_ENABLED, explorationEnabled);
        editor.putBoolean(PREF_CLASSIC_MODE, classicMode);
        
        editor.apply();
    }
    
    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "📋 " + label + " copied to clipboard", Toast.LENGTH_SHORT).show();
    }
    
    private void updateSrtUrls() {
        String port = editSrtListenPort.getText().toString().trim();
        if (port.isEmpty()) {
            port = "6000"; // Default port
        }
        
        String streamId = editStreamId.getText().toString().trim();
        String streamIdParam = streamId.isEmpty() ? "" : "?streamid=" + streamId;
        
        // Update localhost URL
        String localhostUrl = "srt://localhost:" + port + streamIdParam;
        textSrtUrlLocalhost.setText(localhostUrl);
        
        // Update WiFi URL with actual WiFi IP if available
        String wifiIp = getWifiIpAddress();
        String wifiUrl = wifiIp != null ? "srt://" + wifiIp + ":" + port + streamIdParam : "srt://192.168.1.xxx:" + port + streamIdParam;
        textSrtUrlWifi.setText(wifiUrl);
    }
    
    private String getWifiIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                            String interfaceName = networkInterface.getDisplayName().toLowerCase();
                            String ipAddress = address.getHostAddress();
                            
                            // Look for WiFi interfaces
                            if (interfaceName.contains("wlan") || interfaceName.contains("wifi")) {
                                return ipAddress;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore exceptions and return null
        }
        return null;
    }
    
    private void toggleConnectionStickiness() {
        stickinessEnabled = !stickinessEnabled;
        updateStickinessButtonText();
        savePreferences(); // Save the setting
        
        // Apply the setting to the service if it's running
        if (serviceRunning) {
            EnhancedSrtlaService.setStickinessEnabled(stickinessEnabled);
            Toast.makeText(this, 
                stickinessEnabled ? "Connection stickiness enabled" : "Connection stickiness disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateStickinessButtonText() {
        if (stickinessEnabled) {
            buttonToggleStickiness.setText("ON");
            buttonToggleStickiness.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(android.R.color.holo_green_light)));
        } else {
            buttonToggleStickiness.setText("OFF");
            buttonToggleStickiness.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(android.R.color.holo_red_light)));
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
        
        updateAdvancedFeatureButtons();
        
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
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(android.R.color.holo_red_light)));
        }
    }
}
