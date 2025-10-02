package com.example.srtla;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class UrlBuilderActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_STREAM_ID = "stream_id";

    private TextView textLocal;
    private TextView textWifi;
    private TextView textNetworks;
    private EditText editStreamId;
    private Button buttonCopyLocalhost;
    private Button buttonCopyWifi;
    private ConnectivityManager connectivityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_builder);

        textLocal = findViewById(R.id.text_srt_url_localhost);
        textWifi = findViewById(R.id.text_srt_url_wifi);
        textNetworks = findViewById(R.id.text_networks);
        editStreamId = findViewById(R.id.edit_stream_id);
        buttonCopyLocalhost = findViewById(R.id.button_copy_localhost);
        buttonCopyWifi = findViewById(R.id.button_copy_wifi);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        // Initial update
        updateNetworkInfo();

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

        textLocal.setOnClickListener(v -> copyToClipboard(textLocal.getText().toString()));
        textWifi.setOnClickListener(v -> copyToClipboard(textWifi.getText().toString()));

        buttonCopyLocalhost.setOnClickListener(v -> {
            copyToClipboard(textLocal.getText().toString());
            Toast.makeText(this, "Localhost URL copied", Toast.LENGTH_SHORT).show();
        });

        buttonCopyWifi.setOnClickListener(v -> {
            copyToClipboard(textWifi.getText().toString());
            Toast.makeText(this, "WiFi URL copied", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNetworkInfo();
    }

    private void updateNetworkInfo() {
        StringBuilder networkInfo = new StringBuilder("IP Addresses of this device:\n");

        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces();
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
                            if (interfaceName.toLowerCase().contains("wlan") || interfaceName.toLowerCase().contains("wifi")) {
                                networkInfo.append(" (WiFi)");
                            } else if (interfaceName.toLowerCase().contains("rmnet") || interfaceName.toLowerCase().contains("mobile") || interfaceName.toLowerCase().contains("cellular")) {
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

        // Connectivity manager networks
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

        // Update URLs
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String port = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
        String streamId = prefs.getString(PREF_STREAM_ID, "").trim();
        String streamParam = streamId.isEmpty() ? "" : "?streamid=" + streamId;

        String localUrl = "srt://localhost:" + port + streamParam;
        textLocal.setText(localUrl);

        String wifiIp = getWifiIpAddress();
        String wifiUrl = wifiIp != null ? "srt://" + wifiIp + ":" + port + streamParam : "srt://192.168.1.xxx:" + port + streamParam;
        textWifi.setText(wifiUrl);
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

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SRT URL", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Copied URL to clipboard", Toast.LENGTH_SHORT).show();
    }
}
