package com.dimadesu.bondbunny;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_SRTLA_HOST = "srtla_host";
    private static final String PREF_SRTLA_PORT = "srtla_port";

    private EditText editListenPort;
    private EditText editServerHost;
    private EditText editServerPort;

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
    }

}
