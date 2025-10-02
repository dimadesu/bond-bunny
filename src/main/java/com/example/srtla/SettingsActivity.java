package com.example.srtla;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_SRTLA_HOST = "srtla_host";
    private static final String PREF_SRTLA_PORT = "srtla_port";
    private static final String PREF_STICKINESS_ENABLED = "stickiness_enabled";
    private static final String PREF_QUALITY_SCORING_ENABLED = "quality_scoring_enabled";
    private static final String PREF_NETWORK_PRIORITY_ENABLED = "network_priority_enabled";
    private static final String PREF_EXPLORATION_ENABLED = "exploration_enabled";
    private static final String PREF_CLASSIC_MODE = "classic_mode";

    private EditText editListenPort;
    private EditText editServerHost;
    private EditText editServerPort;
    private Button buttonToggleStickiness;
    private Button buttonToggleQualityScoring;
    private Button buttonToggleNetworkPriority;
    private Button buttonToggleExploration;
    private Button buttonToggleClassicMode;

    private boolean stickinessEnabled;
    private boolean qualityScoringEnabled;
    private boolean networkPriorityEnabled;
    private boolean explorationEnabled;
    private boolean classicMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        editListenPort = findViewById(R.id.edit_listen_port);
        editServerHost = findViewById(R.id.edit_server_host);
        editServerPort = findViewById(R.id.edit_server_port);
        buttonToggleStickiness = findViewById(R.id.button_toggle_stickiness);
        buttonToggleQualityScoring = findViewById(R.id.button_toggle_quality_scoring);
        buttonToggleNetworkPriority = findViewById(R.id.button_toggle_network_priority);
        buttonToggleExploration = findViewById(R.id.button_toggle_exploration);
        buttonToggleClassicMode = findViewById(R.id.button_toggle_classic_mode);

        // Load saved listen port or default
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedListenPort = prefs.getString(PREF_LISTEN_PORT, "6000");
        editListenPort.setText(savedListenPort);
        String savedHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net");
        String savedPort = prefs.getString(PREF_SRTLA_PORT, "5000");
        editServerHost.setText(savedHost);
        editServerPort.setText(savedPort);

        // Load algorithm settings
        stickinessEnabled = prefs.getBoolean(PREF_STICKINESS_ENABLED, false);
        qualityScoringEnabled = prefs.getBoolean(PREF_QUALITY_SCORING_ENABLED, true);
        networkPriorityEnabled = prefs.getBoolean(PREF_NETWORK_PRIORITY_ENABLED, true);
        explorationEnabled = prefs.getBoolean(PREF_EXPLORATION_ENABLED, false);
        classicMode = prefs.getBoolean(PREF_CLASSIC_MODE, false);
        updateAdvancedFeatureButtons();

        // Persist changes as user types
        editListenPort.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREF_LISTEN_PORT, s.toString().trim());
                editor.apply();
            }
        });

        // Persist server host changes
        editServerHost.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREF_SRTLA_HOST, s.toString().trim());
                editor.apply();
            }
        });

        // Persist server port changes
        editServerPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREF_SRTLA_PORT, s.toString().trim());
                editor.apply();
            }
        });

        // Set up advanced feature toggle buttons
        buttonToggleStickiness.setOnClickListener(v -> toggleConnectionStickiness());
        buttonToggleQualityScoring.setOnClickListener(v -> toggleQualityScoring());
        buttonToggleNetworkPriority.setOnClickListener(v -> toggleNetworkPriority());
        buttonToggleExploration.setOnClickListener(v -> toggleExploration());
        buttonToggleClassicMode.setOnClickListener(v -> toggleClassicMode());
    }

    private void toggleConnectionStickiness() {
        stickinessEnabled = !stickinessEnabled;
        updateAdvancedFeatureButtons();
        saveAlgorithmPreferences();
        
        // Apply the setting to the service if it's running
        if (EnhancedSrtlaService.isServiceRunning()) {
            EnhancedSrtlaService.setStickinessEnabled(stickinessEnabled);
            Toast.makeText(this, 
                stickinessEnabled ? "Connection stickiness enabled" : "Connection stickiness disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleQualityScoring() {
        qualityScoringEnabled = !qualityScoringEnabled;
        updateAdvancedFeatureButtons();
        saveAlgorithmPreferences();
        
        if (EnhancedSrtlaService.isServiceRunning()) {
            EnhancedSrtlaService.setQualityScoringEnabled(qualityScoringEnabled);
            Toast.makeText(this, 
                qualityScoringEnabled ? "Quality-based scoring enabled" : "Quality-based scoring disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleNetworkPriority() {
        networkPriorityEnabled = !networkPriorityEnabled;
        updateAdvancedFeatureButtons();
        saveAlgorithmPreferences();
        
        if (EnhancedSrtlaService.isServiceRunning()) {
            EnhancedSrtlaService.setNetworkPriorityEnabled(networkPriorityEnabled);
            Toast.makeText(this, 
                networkPriorityEnabled ? "Network priority scaling enabled" : "Network priority scaling disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleExploration() {
        explorationEnabled = !explorationEnabled;
        updateAdvancedFeatureButtons();
        saveAlgorithmPreferences();
        
        if (EnhancedSrtlaService.isServiceRunning()) {
            EnhancedSrtlaService.setExplorationEnabled(explorationEnabled);
            Toast.makeText(this, 
                explorationEnabled ? "Connection exploration enabled" : "Connection exploration disabled", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleClassicMode() {
        classicMode = !classicMode;
        updateAdvancedFeatureButtons();
        saveAlgorithmPreferences();

        if (EnhancedSrtlaService.isServiceRunning()) {
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

    private void saveAlgorithmPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(PREF_STICKINESS_ENABLED, stickinessEnabled);
        editor.putBoolean(PREF_QUALITY_SCORING_ENABLED, qualityScoringEnabled);
        editor.putBoolean(PREF_NETWORK_PRIORITY_ENABLED, networkPriorityEnabled);
        editor.putBoolean(PREF_EXPLORATION_ENABLED, explorationEnabled);
        editor.putBoolean(PREF_CLASSIC_MODE, classicMode);
        editor.apply();
    }
}
