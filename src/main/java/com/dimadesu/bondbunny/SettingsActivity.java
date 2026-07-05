package com.dimadesu.bondbunny;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Switch;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_SRTLA_HOST = "srtla_host";
    private static final String PREF_SRTLA_PORT = "srtla_port";
    private static final String PREF_MOBLINK_ENABLED = "moblink_enabled";
    private static final String PREF_MOBLINK_PASSWORD = "moblink_password";
    private static final String PREF_MOBLINK_PORT = "moblink_port";

    private EditText editListenPort;
    private EditText editServerHost;
    private EditText editServerPort;
    private Switch switchMoblinkEnabled;
    private EditText editMoblinkPassword;
    private EditText editMoblinkPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        editListenPort = findViewById(R.id.edit_listen_port);
        editServerHost = findViewById(R.id.edit_server_host);
        editServerPort = findViewById(R.id.edit_server_port);

        // Load saved settings
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedListenPort = prefs.getString(PREF_LISTEN_PORT, "6000");
        editListenPort.setText(savedListenPort);
        String savedHost = prefs.getString(PREF_SRTLA_HOST, "au.srt.belabox.net");
        String savedPort = prefs.getString(PREF_SRTLA_PORT, "5000");
        editServerHost.setText(savedHost);
        editServerPort.setText(savedPort);

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

        // Moblink settings
        switchMoblinkEnabled = findViewById(R.id.switch_moblink_enabled);
        editMoblinkPassword = findViewById(R.id.edit_moblink_password);
        editMoblinkPort = findViewById(R.id.edit_moblink_port);

        switchMoblinkEnabled.setChecked(prefs.getBoolean(PREF_MOBLINK_ENABLED, false));
        editMoblinkPassword.setText(prefs.getString(PREF_MOBLINK_PASSWORD, "1234"));
        editMoblinkPort.setText(prefs.getString(PREF_MOBLINK_PORT, "7788"));

        switchMoblinkEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(PREF_MOBLINK_ENABLED, isChecked).apply());

        editMoblinkPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(PREF_MOBLINK_PASSWORD, s.toString()).apply();
            }
        });

        editMoblinkPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(PREF_MOBLINK_PORT, s.toString().trim()).apply();
            }
        });
    }

}
