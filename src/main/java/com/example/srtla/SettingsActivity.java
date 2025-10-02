package com.example.srtla;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";

    private EditText editListenPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        editListenPort = findViewById(R.id.edit_listen_port);

        // Load saved listen port or default
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedListenPort = prefs.getString(PREF_LISTEN_PORT, "6000");
        editListenPort.setText(savedListenPort);

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
    }
}
