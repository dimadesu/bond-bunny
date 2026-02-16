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
import android.widget.LinearLayout;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class UrlBuilderActivity extends Activity {
    private static final String PREFS_NAME = "SrtlaAppPrefs";
    private static final String PREF_LISTEN_PORT = "listen_port";
    private static final String PREF_STREAM_ID = "stream_id";

    /** Interface name prefixes considered cellular (excluded from display). */
    private static final String[] CELLULAR_PREFIXES = {
        "rmnet", "ccmni", "wwan", "clat", "v4-rmnet"
    };

    private UrlItemView urlLocalhost;
    private LinearLayout networkUrlsContainer;
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
        networkUrlsContainer = findViewById(R.id.network_urls_container);
        editStreamId = findViewById(R.id.edit_stream_id);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        urlLocalhost.setLabel("localhost");

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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String port = prefs.getString(PREF_LISTEN_PORT, "6000").trim();
        String streamId = prefs.getString(PREF_STREAM_ID, "").trim();
        String streamParam = streamId.isEmpty() ? "" : "?streamid=" + streamId;

        // Localhost is always shown
        String localUrl = "srt://localhost:" + port + streamParam;
        urlLocalhost.setUrl(localUrl);

        // Collect all non-loopback, non-cellular IPv4 interfaces
        List<InterfaceInfo> interfaces = getActiveInterfaces();

        // Rebuild the dynamic container
        networkUrlsContainer.removeAllViews();
        for (InterfaceInfo info : interfaces) {
            UrlItemView item = new UrlItemView(this);
            item.setLabel(info.name);
            item.setUrl("srt://" + info.ip + ":" + port + streamParam);
            networkUrlsContainer.addView(item);
        }
    }

    /**
     * Returns a list of active network interfaces with their IPv4 addresses,
     * excluding loopback and cellular interfaces.
     */
    private List<InterfaceInfo> getActiveInterfaces() {
        List<InterfaceInfo> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getDisplayName();
                if (isCellularInterface(name)) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        result.add(new InterfaceInfo(name, address.getHostAddress()));
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private boolean isCellularInterface(String interfaceName) {
        String lower = interfaceName.toLowerCase();
        for (String prefix : CELLULAR_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static class InterfaceInfo {
        final String name;
        final String ip;

        InterfaceInfo(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
    }
}
