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

    // Network monitoring
    private ConnectivityManager connectivityManager;

    // Dedicated network callbacks for each transport type
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback ethernetCallback;

    // Virtual connection tracking
    private Map<String, Integer> virtualConnections = new ConcurrentHashMap<>();

    // Track network ID + IP to avoid redundant socket recreation
    private Map<String, String> networkState = new ConcurrentHashMap<>();

    // Synchronization for waiting for first network connection
    private CountDownLatch firstConnectionLatch = new CountDownLatch(1);

    // Wakelock to keep CPU awake during network operations
    private PowerManager.WakeLock wakeLock;

    // Wi-Fi lock to maintain high-performance Wi-Fi
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

        // Reset state for a clean start (Service lifecycle handles this in NativeSrtlaService,
        // but SrtlaSender objects may be reused across start/stop cycles)
        firstConnectionLatch = new CountDownLatch(1);
        virtualConnections.clear();
        networkState.clear();

        setupDedicatedNetworkCallbacks();

        // Ensure sockets exist (callbacks may not fire again if networks already connected)
        // Only recreate if we have no sockets yet
        if (virtualConnections.isEmpty()) {
            Log.i(TAG, "No sockets detected, manually recreating for existing networks");
            recreateNetworkSockets();
        } else {
            Log.i(TAG, "Sockets already exist (" + virtualConnections.size() + "), skipping recreation");
        }

        // Wait for at least one network to be detected by dedicated callbacks
        if (listener != null) listener.onStatus("Waiting for network connections...");
        waitForNetworkConnections();

        // Create IPs file with virtual IPs from detected networks
        File ipsFile;
        try {
            ipsFile = createVirtualIpsFile();
        } catch (IOException e) {
            Log.e(TAG, "Error creating virtual IPs file", e);
            if (listener != null) listener.onError("Error starting service: " + e.getMessage());
            releaseLocks();
            return;
        }

        // Start native SRTLA
        if (listener != null) listener.onStatus("Starting service...");
        int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort,
                ipsFile.getAbsolutePath());

        if (result == 0) {
            Log.i(TAG, "Native SRTLA started successfully");

            // Verify native state before marking as running
            if (NativeSrtlaJni.isRunningSrtlaNative()) {
                if (listener != null) listener.onStatus("Service is running on port " + listenPort);
            } else {
                Log.w(TAG, "Native SRTLA start returned 0 but process is not running");
                if (listener != null) listener.onError("Service failed to start. Native code returned 0");
            }
        } else {
            Log.e(TAG, "Native SRTLA failed to start with code: " + result);
            if (listener != null) listener.onError("Service failed to start (code: " + result + ")");
        }
    }

    /**
     * Stop SRTLA bonding, tear down network callbacks, and release locks.
     * Safe to call multiple times.
     */
    public void stop() {
        NativeSrtlaJni.stopSrtlaNative();
        cleanupVirtualConnections();
        teardownDedicatedNetworkCallbacks();
        releaseLocks();
    }

    // -------------------------------------------------------------------------
    // Lock management
    // -------------------------------------------------------------------------

    private void acquireLocks() {
        // Acquire wakelock to keep CPU awake during network operations
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SRTLA::NetworkWakeLock");
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired");
        }

        // Acquire Wi-Fi lock to maintain high-performance Wi-Fi
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SRTLA::WifiLock");
            wifiLock.acquire();
            Log.i(TAG, "Wi-Fi lock acquired");
        }
    }

    private void releaseLocks() {
        // Release wakelock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }

        // Release Wi-Fi lock
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Log.i(TAG, "Wi-Fi lock released");
        }
    }

    // -------------------------------------------------------------------------
    // Network callbacks
    // -------------------------------------------------------------------------

    /**
     * Set up dedicated network callbacks for each transport type
     * This ensures we detect all networks even on Samsung devices where getAllNetworks() might miss some
     */
    private void setupDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Dedicated network callbacks not available on this Android version");
            return;
        }

        Log.i(TAG, "Setting up dedicated network callbacks...");

        // Create and register callbacks for each network type
        cellularCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_CELLULAR, "CELLULAR");
        wifiCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_WIFI, "WIFI");
        ethernetCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_ETHERNET, "ETHERNET");

        Log.i(TAG, "Dedicated network callbacks setup complete");
    }

    /**
     * Clean up dedicated network callbacks
     */
    private void teardownDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (cellularCallback != null) {
                    connectivityManager.unregisterNetworkCallback(cellularCallback);
                    Log.i(TAG, "Unregistered dedicated cellular callback");
                }
                if (wifiCallback != null) {
                    connectivityManager.unregisterNetworkCallback(wifiCallback);
                    Log.i(TAG, "Unregistered dedicated WiFi callback");
                }
                if (ethernetCallback != null) {
                    connectivityManager.unregisterNetworkCallback(ethernetCallback);
                    Log.i(TAG, "Unregistered dedicated ethernet callback");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering dedicated network callbacks", e);
            }

            cellularCallback = null;
            wifiCallback = null;
            ethernetCallback = null;
        }
    }

    /**
     * Create and register a network callback for a specific transport type
     */
    private ConnectivityManager.NetworkCallback registerNetworkCallback(int transportType, String networkTypeName) {
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                try {
                    Log.i(TAG, "DEDICATED: Got " + networkTypeName.toLowerCase() + " network available: " + network);
                    handleDedicatedNetworkAvailable(network, networkTypeName, "");
                } catch (Exception e) {
                    Log.e(TAG, "Error in dedicated " + networkTypeName.toLowerCase() + " callback", e);
                }
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "DEDICATED: Lost " + networkTypeName.toLowerCase() + " network: " + network);
                handleDedicatedNetworkLost(network, networkTypeName);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                try {
                    // This fires when network capabilities change (e.g., mobile data toggled back on)
                    Log.i(TAG, "DEDICATED: " + networkTypeName + " network capabilities changed: " + network);

                    // Only recreate socket if network state actually changed (different IP or first time)
                    String stateKey = networkTypeName + ":" + network.toString();
                    String currentIP = getNetworkIP(network);
                    String previousIP = networkState.get(stateKey);

                    if (currentIP != null && !currentIP.equals(previousIP)) {
                        Log.i(TAG, "DEDICATED: " + networkTypeName + " IP changed: " + previousIP + " -> " + currentIP);
                        handleDedicatedNetworkAvailable(network, networkTypeName, "");
                    } else {
                        Log.d(TAG, "DEDICATED: " + networkTypeName + " capabilities changed but IP unchanged, skipping recreation");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in " + networkTypeName.toLowerCase() + " capabilities changed callback", e);
                }
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(transportType)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivityManager.requestNetwork(request, callback);
            Log.i(TAG, "Registered dedicated " + networkTypeName + " network callback");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register " + networkTypeName.toLowerCase() + " callback", e);
        }

        return callback;
    }

    /**
     * Manually recreate sockets for all currently available networks
     * This is needed when service restarts but callbacks don't fire (networks already connected)
     */
    private void recreateNetworkSockets() {
        Log.i(TAG, "Recreating network sockets for currently available networks");
        if (connectivityManager == null) {
            Log.e(TAG, "ConnectivityManager not available");
            return;
        }

        // Get all currently active networks
        Network[] networks = connectivityManager.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps == null) continue;

            // Skip VPN networks
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;

            // Check each transport type and recreate socket
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                Log.i(TAG, "Found existing CELLULAR network, recreating socket");
                handleDedicatedNetworkAvailable(network, "CELLULAR", "");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Log.i(TAG, "Found existing WIFI network, recreating socket");
                handleDedicatedNetworkAvailable(network, "WIFI", "");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                Log.i(TAG, "Found existing ETHERNET network, recreating socket");
                handleDedicatedNetworkAvailable(network, "ETHERNET", "");
            }
        }
    }

    /**
     * Handle when a dedicated network callback detects an available network
     */
    private synchronized void handleDedicatedNetworkAvailable(Network network, String networkType, String operatorName) {
        try {
            Log.i(TAG, "DEDICATED: Handling " + networkType + " network: " + network);
            String realIP = getNetworkIP(network);
            Log.i(TAG, "DEDICATED: Real IP for " + networkType + ": " + (realIP != null ? realIP : "NULL"));

            if (realIP != null) {
                String virtualIP = getVirtualIPForNetworkType(networkType);
                int networkTypeId = getNetworkTypeId(networkType);

                Log.i(TAG, "DEDICATED: Virtual IP: " + virtualIP + ", already registered: " + virtualConnections.containsKey(virtualIP));

                if (virtualIP != null) {
                    // Track network state to prevent duplicate creations
                    String stateKey = networkType + ":" + network.toString();
                    String previousState = networkState.get(stateKey);
                    String currentState = realIP;

                    // Skip if this exact network+IP combination already exists
                    if (currentState.equals(previousState) && virtualConnections.containsKey(virtualIP)) {
                        Log.d(TAG, "DEDICATED: Socket already exists for " + networkType + " with same IP, skipping");
                        return;
                    }

                    // If already registered, remove old socket first (network may have changed)
                    if (virtualConnections.containsKey(virtualIP)) {
                        Log.i(TAG, "DEDICATED: Re-creating socket for " + networkType + " (network reconnected/changed)");
                        virtualConnections.remove(virtualIP);
                    }

                    Log.i(TAG, "DEDICATED: Creating socket for " + networkType + " network: " + virtualIP + " -> " + realIP);
                    networkState.put(stateKey, currentState);
                    int socket = createNetworkSocket(network);
                    if (socket >= 0) {
                        virtualConnections.put(virtualIP, socket);
                        NativeSrtlaJni.setNetworkSocket(virtualIP, realIP, networkTypeId, socket);
                        Log.i(TAG, "DEDICATED: Successfully setup " + networkType + " connection: " + virtualIP + " -> " + realIP + " (socket: " + socket + ")");

                        // Signal that we have our first connection
                        firstConnectionLatch.countDown();

                        // Update virtual IPs file if service is running
                        if (NativeSrtlaJni.isRunningSrtlaNative()) {
                            try {
                                createVirtualIpsFile();
                                NativeSrtlaJni.notifyNetworkChange();
                                Log.i(TAG, "DEDICATED: Updated virtual IPs file and notified native code of " + networkType + " network change");
                            } catch (Exception e) {
                                Log.w(TAG, "Error updating virtual IPs file after dedicated network change", e);
                            }
                        }
                    } else {
                        Log.e(TAG, "DEDICATED: Failed to create socket for " + networkType + " (returned " + socket + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling dedicated " + networkType + " network available", e);
        }
    }

    /**
     * Handle when a dedicated network callback detects a lost network
     */
    private void handleDedicatedNetworkLost(Network network, String networkType) {
        try {
            String virtualIP = getVirtualIPForNetworkType(networkType);

            if (virtualIP != null && virtualConnections.containsKey(virtualIP)) {
                Log.i(TAG, "DEDICATED: Removing " + networkType + " connection: " + virtualIP);
                // Note: Don't close socket here - native code owns it
                virtualConnections.remove(virtualIP);

                // Clean up network state tracking
                String stateKey = networkType + ":" + network.toString();
                networkState.remove(stateKey);

                // Update virtual IPs file if service is running
                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    try {
                        createVirtualIpsFile();
                        NativeSrtlaJni.notifyNetworkChange();
                        Log.i(TAG, "DEDICATED: Updated virtual IPs file and notified native code of " + networkType + " network loss");
                    } catch (Exception e) {
                        Log.w(TAG, "Error updating virtual IPs file after dedicated network loss", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling dedicated " + networkType + " network lost", e);
        }
    }

    // -------------------------------------------------------------------------
    // Socket helpers
    // -------------------------------------------------------------------------

    private int createNetworkSocket(Network network) {
        try {
            // Create a native UDP socket using JNI
            int socketFD = NativeSrtlaJni.createUdpSocketNative();
            if (socketFD < 0) {
                Log.e(TAG, "Failed to create native UDP socket");
                return -1;
            }

            // Bind the socket to the specific network using FileDescriptor
            java.io.FileDescriptor fd = new java.io.FileDescriptor();

            // Use reflection to set the file descriptor value
            try {
                java.lang.reflect.Field fdField = java.io.FileDescriptor.class.getDeclaredField("descriptor");
                fdField.setAccessible(true);
                fdField.setInt(fd, socketFD);

                // Now bind the FileDescriptor to the network
                network.bindSocket(fd);

                // Detach the FileDescriptor from fdsan tracking since we're transferring ownership
                // to native code. This prevents fdsan crashes when native code closes the socket.
                try {
                    // Use reflection to call FileDescriptor.setInt$(-1) to detach from fdsan
                    java.lang.reflect.Method setIntMethod = java.io.FileDescriptor.class.getDeclaredMethod("setInt$", int.class);
                    setIntMethod.setAccessible(true);
                    setIntMethod.invoke(fd, -1);
                    Log.i(TAG, "Detached FD " + socketFD + " from fdsan tracking for native ownership");
                } catch (Exception fdDetachEx) {
                    Log.w(TAG, "Could not detach FD from fdsan (may cause crashes): " + fdDetachEx.getMessage());
                }

                Log.i(TAG, "Successfully bound and transferred socket FD " + socketFD + " to native code");
                return socketFD;

            } catch (Exception reflectionEx) {
                Log.w(TAG, "Reflection approach failed, cleaning up native socket", reflectionEx);

                // If reflection failed, we can't properly bind the native socket
                // Close the native socket to prevent FD leaks
                NativeSrtlaJni.closeSocketNative(socketFD);

                Log.e(TAG, "Failed to bind native socket to network - socket closed");
                return -1;
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to create and bind network socket: " + e.getMessage(), e);
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Network wait / IPs file
    // -------------------------------------------------------------------------

    /**
     * Wait for network connections to be detected by dedicated callbacks
     * This ensures we have at least one network before starting native SRTLA
     * Simple polling loop until at least 1 connection available
     */
    private void waitForNetworkConnections() {
        Log.i(TAG, "Waiting for connections before starting");

        try {
            // Wait up to 2 seconds for first connection
            if (firstConnectionLatch.await(2, TimeUnit.SECONDS)) {
                Log.i(TAG, "Ready to start. " + virtualConnections.size() + " connections available");
            } else {
                Log.w(TAG, "Timeout waiting for connections, starting anyway with " + virtualConnections.size() + " connections");
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "Network wait interrupted", e);
        }
    }

    private File createVirtualIpsFile() throws IOException {
        File ipsFile = new File(context.getFilesDir(), "native_srtla_virtual_ips.txt");

        // Delete existing file
        if (ipsFile.exists()) {
            ipsFile.delete();
            Log.i(TAG, "Deleted existing virtual IPs file");
        }

        try (FileWriter writer = new FileWriter(ipsFile, false)) {
            // Write only virtual IPs that have active connections
            for (String virtualIP : virtualConnections.keySet()) {
                writer.write(virtualIP + "\n");
                Log.i(TAG, "Writing active virtual IP to file: " + virtualIP);
            }
            writer.flush();
        }

        Log.i(TAG, "Created virtual IPs file: " + ipsFile.getAbsolutePath() +
              " (size: " + ipsFile.length() + " bytes, " + virtualConnections.size() + " virtual IPs)");

        return ipsFile;
    }

    private void cleanupVirtualConnections() {
        Log.i(TAG, "Cleaning up virtual connections...");

        for (Map.Entry<String, Integer> entry : virtualConnections.entrySet()) {
            int socketFD = entry.getValue();
            if (socketFD >= 0) {
                // Note: We don't close these sockets here because they were transferred
                // to native code ownership. The native code is responsible for closing them.
                // Attempting to close them here would cause fdsan crashes.
                Log.i(TAG, "Virtual connection " + entry.getKey() + " (FD: " + socketFD + ") - ownership transferred to native code");
            }
        }

        virtualConnections.clear();
        networkState.clear();
        Log.i(TAG, "Virtual connections cleanup complete - native code handles socket cleanup");
    }

    // -------------------------------------------------------------------------
    // IP address detection
    // -------------------------------------------------------------------------

    private String getNetworkIP(Network network) {
        try {
            // First try getting IP from LinkProperties (works for WiFi and some cellular)
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }

            // Fallback: Try socket binding method (works for Samsung cellular)
            Log.i(TAG, "LinkProperties didn't provide IPv4, trying socket binding method");
            String ipFromSocket = getNetworkIPFromSocket(network);
            if (ipFromSocket != null) {
                Log.i(TAG, "Got IP via socket binding: " + ipFromSocket);
                return ipFromSocket;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network IP", e);
        }
        return null;
    }

    /**
     * Get IP address from a network by creating a test socket
     * This works even for cellular networks on Samsung devices
     */
    private String getNetworkIPFromSocket(Network network) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            // Samsung requires CHANGE_NETWORK_STATE for bindSocket on DatagramSocket
            // But we CAN bind FileDescriptor sockets (native sockets) successfully
            // So as a workaround, use a placeholder IP for cellular networks
            network.bindSocket(socket);
            // Connect to a public server to force socket to select a source address
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress localAddress = socket.getLocalAddress();

            if (localAddress != null && localAddress instanceof Inet4Address &&
                    !localAddress.isLoopbackAddress() && !localAddress.isAnyLocalAddress()) {
                return localAddress.getHostAddress();
            }
        } catch (Exception e) {
            // Check if it's a permission error (EPERM from Samsung devices)
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("EPERM")) {
                // Samsung doesn't allow bindSocket for cellular networks
                // Use a placeholder IP - the native socket binding works fine
                Log.i(TAG, "Using placeholder IP for network (bindSocket EPERM blocked by Samsung)");
                return "10.64.64.64"; // Placeholder that indicates "cellular with unknown IP"
            }
            Log.w(TAG, "getNetworkIPFromSocket failed: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Get virtual IP for a given network type
     */
    private String getVirtualIPForNetworkType(String networkType) {
        switch (networkType) {
            case "WIFI":
                return "10.0.1.1";
            case "CELLULAR":
                return "10.0.2.1";
            case "ETHERNET":
                return "10.0.3.1";
            default:
                return null;
        }
    }

    /**
     * Get network type ID for a given network type
     */
    private int getNetworkTypeId(String networkType) {
        switch (networkType) {
            case "WIFI":
                return 1;
            case "CELLULAR":
                return 2;
            case "ETHERNET":
                return 3;
            default:
                return 0;
        }
    }
}
