package com.dimadesu.bondbunny;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manages network-bonded SRTLA connections.
 *
 * <p>Handles dedicated per-transport ConnectivityManager callbacks, native UDP socket
 * creation/binding, virtual-IP file management, and the native SRTLA lifecycle.
 * Wake lock and Wi-Fi lock are acquired on {@link #start} and released on {@link #stop}.</p>
 *
 * <p>Designed to be usable from both an Android Service (bond-bunny) and a plain Kotlin
 * object (LifeStreamer's SrtlaManager) without importing any Service APIs.</p>
 */
public class SrtlaSender {

    private static final String TAG = "SrtlaSender";

    /** Callback for status and error events during {@link #start}. */
    public interface Listener {
        void onStatus(String message);
        void onError(String message);
    }

    private final Context context;
    private final ConnectivityManager connectivityManager;

    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback ethernetCallback;

    /** Virtual-IP → native socket FD, one entry per transport type. */
    private final Map<String, Integer> virtualConnections = new ConcurrentHashMap<>();

    /**
     * Maps "TYPE:networkHandle" → last-seen real IP.
     * Used to avoid redundant socket recreation when capabilities change.
     */
    private final Map<String, String> networkState = new ConcurrentHashMap<>();

    private CountDownLatch firstConnectionLatch = new CountDownLatch(1);

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public SrtlaSender(Context context) {
        this.context = context.getApplicationContext();
        this.connectivityManager =
                (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start SRTLA bonding.  Blocks until the native library has started (or failed).
     * Must be called from a background thread.
     *
     * @param srtlaHost  SRTLA receiver hostname or IP
     * @param srtlaPort  SRTLA receiver UDP port (as string)
     * @param listenPort Local UDP port for SRT traffic
     * @param listener   Optional status/error callbacks (may be null)
     */
    public void start(String srtlaHost, String srtlaPort, String listenPort, Listener listener) {
        acquireLocks();

        // Reset state for a clean start
        firstConnectionLatch = new CountDownLatch(1);
        virtualConnections.clear();
        networkState.clear();

        setupDedicatedNetworkCallbacks();

        // If callbacks didn't fire yet (networks already connected), recreate sockets manually
        if (virtualConnections.isEmpty()) {
            Log.i(TAG, "No callbacks fired yet, recreating sockets for existing networks");
            recreateNetworkSockets();
        }

        if (listener != null) listener.onStatus("Waiting for network connections...");
        waitForNetworkConnections();

        File ipsFile;
        try {
            ipsFile = createVirtualIpsFile();
        } catch (IOException e) {
            String err = "Failed to create IPs file: " + e.getMessage();
            Log.e(TAG, err, e);
            if (listener != null) listener.onError(err);
            releaseLocks();
            return;
        }

        if (listener != null) listener.onStatus("Starting SRTLA on port " + listenPort + "...");
        int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort,
                ipsFile.getAbsolutePath());

        if (result == 0) {
            if (NativeSrtlaJni.isRunningSrtlaNative()) {
                Log.i(TAG, "Native SRTLA started on port " + listenPort);
                if (listener != null) listener.onStatus("Running on port " + listenPort);
            } else {
                String err = "Native SRTLA start returned 0 but process is not running";
                Log.w(TAG, err);
                if (listener != null) listener.onError(err);
            }
        } else {
            String err = "Native SRTLA failed to start (code: " + result + ")";
            Log.e(TAG, err);
            if (listener != null) listener.onError(err);
        }
    }

    /**
     * Stop SRTLA bonding, tear down network callbacks, and release locks.
     * Safe to call multiple times.
     */
    public void stop() {
        NativeSrtlaJni.stopSrtlaNative();
        teardownDedicatedNetworkCallbacks();
        virtualConnections.clear();
        networkState.clear();
        releaseLocks();
    }

    // -------------------------------------------------------------------------
    // Lock management
    // -------------------------------------------------------------------------

    private void acquireLocks() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SRTLA::NetworkWakeLock");
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired");
        }

        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SRTLA::WifiLock");
            wifiLock.acquire();
            Log.i(TAG, "Wi-Fi lock acquired");
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Log.i(TAG, "Wi-Fi lock released");
        }
    }

    // -------------------------------------------------------------------------
    // Network callbacks
    // -------------------------------------------------------------------------

    private void setupDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Network callbacks not available on this Android version");
            return;
        }
        cellularCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_CELLULAR, "CELLULAR");
        wifiCallback     = registerNetworkCallback(NetworkCapabilities.TRANSPORT_WIFI,     "WIFI");
        ethernetCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_ETHERNET, "ETHERNET");
        Log.i(TAG, "Network callbacks registered");
    }

    private void teardownDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager != null) {
            unregisterCallback(cellularCallback, "cellular");
            unregisterCallback(wifiCallback,     "WiFi");
            unregisterCallback(ethernetCallback, "ethernet");
        }
        cellularCallback = null;
        wifiCallback     = null;
        ethernetCallback = null;
    }

    private void unregisterCallback(ConnectivityManager.NetworkCallback cb, String name) {
        if (cb == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(cb);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering " + name + " callback: " + e.getMessage());
        }
    }

    private ConnectivityManager.NetworkCallback registerNetworkCallback(int transport, String typeName) {
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                try {
                    handleNetworkAvailable(network, typeName);
                } catch (Exception e) {
                    Log.e(TAG, "Error in " + typeName + " onAvailable", e);
                }
            }

            @Override
            public void onLost(Network network) {
                handleNetworkLost(network, typeName);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                try {
                    String stateKey  = typeName + ":" + network;
                    String currentIP = getNetworkIP(network);
                    String prevIP    = networkState.get(stateKey);
                    if (currentIP != null && !currentIP.equals(prevIP)) {
                        Log.i(TAG, typeName + " IP changed: " + prevIP + " -> " + currentIP);
                        handleNetworkAvailable(network, typeName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in " + typeName + " onCapabilitiesChanged", e);
                }
            }
        };

        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(transport)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivityManager.requestNetwork(req, callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register " + typeName + " callback", e);
        }
        return callback;
    }

    private void recreateNetworkSockets() {
        if (connectivityManager == null) return;
        Network[] networks = connectivityManager.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                handleNetworkAvailable(network, "CELLULAR");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                handleNetworkAvailable(network, "WIFI");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                handleNetworkAvailable(network, "ETHERNET");
            }
        }
    }

    private synchronized void handleNetworkAvailable(Network network, String networkType) {
        try {
            String realIP = getNetworkIP(network);
            if (realIP == null) return;

            String virtualIP   = virtualIPForType(networkType);
            int    networkTypeId = networkTypeId(networkType);
            if (virtualIP == null) return;

            String stateKey   = networkType + ":" + network;
            String prevState  = networkState.get(stateKey);

            // Skip if same network+IP already set up
            if (realIP.equals(prevState) && virtualConnections.containsKey(virtualIP)) {
                Log.d(TAG, networkType + " socket already current, skipping");
                return;
            }

            // If a stale socket exists, just remove it (native owns the FD)
            if (virtualConnections.containsKey(virtualIP)) {
                Log.i(TAG, "Replacing stale " + networkType + " socket");
                virtualConnections.remove(virtualIP);
            }

            networkState.put(stateKey, realIP);
            int socketFD = createNetworkSocket(network);
            if (socketFD >= 0) {
                virtualConnections.put(virtualIP, socketFD);
                NativeSrtlaJni.setNetworkSocket(virtualIP, realIP, networkTypeId, socketFD);
                Log.i(TAG, networkType + " socket set up: " + virtualIP + " -> " + realIP + " (FD " + socketFD + ")");
                firstConnectionLatch.countDown();

                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    try {
                        createVirtualIpsFile();
                        NativeSrtlaJni.notifyNetworkChange();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to update IPs file after network change", e);
                    }
                }
            } else {
                Log.e(TAG, "Failed to create socket for " + networkType);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleNetworkAvailable error for " + networkType, e);
        }
    }

    private void handleNetworkLost(Network network, String networkType) {
        try {
            String virtualIP = virtualIPForType(networkType);
            if (virtualIP != null && virtualConnections.containsKey(virtualIP)) {
                virtualConnections.remove(virtualIP);
                networkState.remove(networkType + ":" + network);
                Log.i(TAG, networkType + " connection removed: " + virtualIP);

                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    try {
                        createVirtualIpsFile();
                        NativeSrtlaJni.notifyNetworkChange();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to update IPs file after network loss", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleNetworkLost error for " + networkType, e);
        }
    }

    // -------------------------------------------------------------------------
    // Socket helpers
    // -------------------------------------------------------------------------

    private int createNetworkSocket(Network network) {
        int socketFD = NativeSrtlaJni.createUdpSocketNative();
        if (socketFD < 0) {
            Log.e(TAG, "createUdpSocketNative returned " + socketFD);
            return -1;
        }

        try {
            java.io.FileDescriptor fd = new java.io.FileDescriptor();
            java.lang.reflect.Field fdField = java.io.FileDescriptor.class.getDeclaredField("descriptor");
            fdField.setAccessible(true);
            fdField.setInt(fd, socketFD);

            network.bindSocket(fd);

            // Detach from fdsan so native code can close the socket without triggering fdsan
            try {
                java.lang.reflect.Method setInt = java.io.FileDescriptor.class
                        .getDeclaredMethod("setInt$", int.class);
                setInt.setAccessible(true);
                setInt.invoke(fd, -1);
            } catch (Exception e) {
                Log.w(TAG, "Could not detach FD " + socketFD + " from fdsan: " + e.getMessage());
            }
            return socketFD;

        } catch (Exception e) {
            Log.w(TAG, "bindSocket failed for FD " + socketFD + ": " + e.getMessage());
            NativeSrtlaJni.closeSocketNative(socketFD);
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Network wait / IPs file
    // -------------------------------------------------------------------------

    private void waitForNetworkConnections() {
        try {
            if (firstConnectionLatch.await(2, TimeUnit.SECONDS)) {
                Log.i(TAG, "Ready: " + virtualConnections.size() + " connection(s)");
            } else {
                Log.w(TAG, "Timeout waiting for connections; proceeding with "
                        + virtualConnections.size() + " connection(s)");
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "Network wait interrupted", e);
        }
    }

    private File createVirtualIpsFile() throws IOException {
        File ipsFile = new File(context.getFilesDir(), "native_srtla_virtual_ips.txt");
        if (ipsFile.exists()) ipsFile.delete();

        try (FileWriter writer = new FileWriter(ipsFile, false)) {
            for (String virtualIP : virtualConnections.keySet()) {
                writer.write(virtualIP + "\n");
            }
            writer.flush();
        }

        Log.i(TAG, "IPs file: " + ipsFile.getAbsolutePath()
                + " (" + virtualConnections.size() + " entries)");
        return ipsFile;
    }

    // -------------------------------------------------------------------------
    // IP address detection
    // -------------------------------------------------------------------------

    private String getNetworkIP(Network network) {
        // 1. Try LinkProperties (fast, works for Wi-Fi)
        try {
            LinkProperties lp = connectivityManager.getLinkProperties(network);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "LinkProperties IP lookup failed", e);
        }

        // 2. Fallback: bind a DatagramSocket to get source address (works for Samsung cellular)
        return getNetworkIPFromSocket(network);
    }

    private String getNetworkIPFromSocket(Network network) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            network.bindSocket(socket);
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address
                    && !local.isLoopbackAddress()
                    && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("EPERM")) {
                // Samsung blocks DatagramSocket.bindSocket for cellular; use placeholder
                return "10.64.64.64";
            }
            Log.w(TAG, "getNetworkIPFromSocket failed: " + msg);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    private static String virtualIPForType(String networkType) {
        switch (networkType) {
            case "WIFI":     return "10.0.1.1";
            case "CELLULAR": return "10.0.2.1";
            case "ETHERNET": return "10.0.3.1";
            default:         return null;
        }
    }

    private static int networkTypeId(String networkType) {
        switch (networkType) {
            case "WIFI":     return 1;
            case "CELLULAR": return 2;
            case "ETHERNET": return 3;
            default:         return 0;
        }
    }
}
