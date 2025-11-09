package com.dimadesu.bondbunny;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

public class UrlBuilderActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_STREAM_ID = "stream_id";

    private UrlItemView urlLocalhost;
    private UrlItemView urlWifi;
    private UrlItemView urlEthernet;
    private EditText editStreamId;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private android.os.Handler updateHandler = new android.os.Handler();
    private Runnable updateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_builder);

        urlLocalhost = findViewById(R.id.url_localhost);
        urlWifi = findViewById(R.id.url_wifi);
        urlEthernet = findViewById(R.id.url_ethernet);
        editStreamId = findViewById(R.id.edit_stream_id);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        // Set labels
        urlLocalhost.setLabel("Localhost");
        urlWifi.setLabel("Wi-Fi");
        urlEthernet.setLabel("Ethernet");

        // Hide Wi-Fi and Ethernet initially
        urlWifi.hide();
        urlEthernet.hide();

        // Initial update
        updateNetworkInfo();

        // Register a network callback to update UI when network changes
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> updateNetworkInfo());
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> updateNetworkInfo());
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                runOnUiThread(() -> updateNetworkInfo());
            }
        };

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // Some devices/OS versions may throw; fallback to onResume updates
        }

        // Load and show saved stream id
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedStream = prefs.getString(PREF_STREAM_ID, "");
        editStreamId.setText(savedStream);
        editStreamId.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(PREF_STREAM_ID, s.toString()).apply();
                updateNetworkInfo();
            }
        });

        // Start periodic updates
        startPeriodicUpdates();
    }

    private void startPeriodicUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNetworkInfo();
                updateHandler.postDelayed(this, 2000); // Update every 2 seconds
            }
        };
        updateHandler.post(updateRunnable);
    }

    private void stopPeriodicUpdates() {
        if (updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPeriodicUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPeriodicUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPeriodicUpdates();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
    }

    private void updateNetworkInfo() {
        // Update URLs
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String port = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
        String streamId = prefs.getString(PREF_STREAM_ID, "").trim();
        String streamParam = streamId.isEmpty() ? "" : "?streamid=" + streamId;

        String localUrl = "srt://localhost:" + port + streamParam;
        urlLocalhost.setUrl(localUrl);

        // Update Wi-Fi URL (show/hide dynamically)
        String wifiIp = getWifiIpAddress();
        if (wifiIp != null) {
            String wifiUrl = "srt://" + wifiIp + ":" + port + streamParam;
            urlWifi.setUrl(wifiUrl);
            urlWifi.show();
        } else {
            urlWifi.hide();
        }

        // Update Ethernet URL (show/hide dynamically)
        String ethernetIp = getEthernetIpAddress();
        if (ethernetIp != null) {
            String ethernetUrl = "srt://" + ethernetIp + ":" + port + streamParam;
            urlEthernet.setUrl(ethernetUrl);
            urlEthernet.show();
        } else {
            urlEthernet.hide();
        }
    }

    private String getWifiIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                            String interfaceName = networkInterface.getDisplayName().toLowerCase();
                            String ipAddress = address.getHostAddress();
                            if (interfaceName.contains("wlan") || interfaceName.contains("wifi")) {
                                return ipAddress;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String getEthernetIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                            String interfaceName = networkInterface.getDisplayName().toLowerCase();
                            String ipAddress = address.getHostAddress();
                            if (interfaceName.contains("eth") || interfaceName.contains("usb")) {
                                return ipAddress;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
