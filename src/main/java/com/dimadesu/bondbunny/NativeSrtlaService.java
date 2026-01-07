package com.dimadesu.bondbunny;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android foreground service for native SRTLA implementation
 */
public class NativeSrtlaService extends Service {
    private static final String TAG = "NativeSrtlaService";
    private static final int NOTIFICATION_ID = 1; // Same as startup notification - will update it
    public static final String CHANNEL_ID = "SRTLA_SERVICE_CHANNEL";
    
    // Native method for creating UDP socket
    private native int createUdpSocketNative();
    
    // Native method for closing socket
    private native void closeSocketNative(int socketFD);
    
    // Service state
    private static boolean isServiceRunning = false;
    private boolean isSrtlaRunning = false; // Add this variable to track SRTLA running state
    private String srtlaHost;
    private String srtlaPort;
    private String listenPort;
    
    // Network monitoring
    private ConnectivityManager connectivityManager;
    
    // Dedicated network callbacks for each transport type
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback ethernetCallback;
    
    // Virtual connection tracking
    private java.util.Map<String, Integer> virtualConnections = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Keep DatagramSocket references alive to prevent GC from closing the underlying FDs
    // The FDs are transferred to native code, but Java will close the socket if the DatagramSocket is GC'd
    private java.util.Map<String, DatagramSocket> activeDatagramSockets = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Track network ID + IP to avoid redundant socket recreation
    private java.util.Map<String, String> networkState = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Synchronization for waiting for first network connection
    private CountDownLatch firstConnectionLatch = new CountDownLatch(1);
    
    // Wakelock to keep CPU awake during network operations
    private PowerManager.WakeLock wakeLock;
    
    // Wi-Fi lock to maintain high-performance Wi-Fi
    private WifiManager.WifiLock wifiLock;
    
    // Native methods are accessed through NativeSrtlaJni wrapper
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Create notification channel
        createNotificationChannel(this);
        
        // Initialize network monitoring
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        setupDedicatedNetworkCallbacks();
        
        // Acquire wakelock to keep CPU awake during network operations
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SRTLA::NetworkWakeLock");
        wakeLock.acquire();
        Log.i(TAG, "WakeLock acquired");
        
        // Acquire Wi-Fi lock to maintain high-performance Wi-Fi
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SRTLA::WifiLock");
        wifiLock.acquire();
        Log.i(TAG, "Wi-Fi lock acquired");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "NativeSrtlaService onStartCommand");
        
        if (intent != null) {
            srtlaHost = intent.getStringExtra("srtla_host");
            srtlaPort = intent.getStringExtra("srtla_port");
            listenPort = intent.getStringExtra("listen_port");
            
            if (srtlaHost == null || srtlaPort == null || listenPort == null) {
                Log.e(TAG, "Missing required parameters");
                stopSelf();
                return START_NOT_STICKY;
            }
            
            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification("Starting native SRTLA..."));
            
            // Ensure sockets exist (callbacks may not fire again if networks already connected)
            // Only recreate if we have no sockets yet
            if (virtualConnections.isEmpty()) {
                Log.i(TAG, "No sockets detected, manually recreating for existing networks");
                recreateNetworkSockets();
            } else {
                Log.i(TAG, "Sockets already exist (" + virtualConnections.size() + "), skipping recreation");
            }
            
            // Start native SRTLA in background thread
            new Thread(this::startNativeSrtla).start();
        }
        
        return START_STICKY; // Restart if killed by system
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "NativeSrtlaService onDestroy");
        stopNativeSrtla();
        cleanupVirtualConnections();
        teardownDedicatedNetworkCallbacks();
        
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
        
        isServiceRunning = false;
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
    
    private void startNativeSrtla() {
        try {
            Log.i(TAG, "Starting native SRTLA process...");
            
            // Validate inputs before starting
            String validationError = validateSrtlaConfig();
            if (validationError != null) {
                handleStartupError(validationError);
                return;
            }
            
            // Wait for at least one network to be detected by dedicated callbacks
            updateNotification("Waiting for network connections...");
            waitForNetworkConnections();
            
            // Create IPs file with virtual IPs from detected networks
            File ipsFile = createVirtualIpsFile();
            
            // Update notification
            updateNotification("Starting service...");
            
            // Start native SRTLA
            int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort, ipsFile.getAbsolutePath());
            
            if (result == 0) {
                Log.i(TAG, "Native SRTLA started successfully");
                isSrtlaRunning = true;  // Set the flag when SRTLA starts successfully
                
                // Verify native state before marking as running
                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    isServiceRunning = true;
                    updateNotification("Service is running on port " + listenPort);
                } else {
                    Log.w(TAG, "Native SRTLA start returned 0 but process is not running");
                    updateNotification("Service failed to start. Native code returned 0");
                    stopSelf();
                }
            } else {
                Log.e(TAG, "Native SRTLA failed to start with code: " + result);
                updateNotification("Service failed to start (code: " + result + ")");
                stopSelf();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting native SRTLA", e);
            updateNotification("Error starting service: " + e.getMessage());
            stopSelf();
        }
    }
    
    private void stopNativeSrtla() {
        try {
            Log.i(TAG, "Stopping native SRTLA process...");
            
            int result = NativeSrtlaJni.stopSrtlaNative();
            if (result == 0) {
                Log.i(TAG, "Native SRTLA stop signal sent successfully");
                
                // Wait a moment for the native process to actually stop
                new Thread(() -> {
                    try {
                        Thread.sleep(1000); // Wait 1 second
                        
                        // Check if native process actually stopped
                        if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                            Log.i(TAG, "Native SRTLA process confirmed stopped");
                            // Post a dismissible notification when service stops
                            postStoppedNotification("Service stopped");
                        } else {
                            Log.w(TAG, "Native SRTLA process still running after stop signal");
                        }
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Stop monitoring interrupted", e);
                    }
                }).start();
                
            } else {
                Log.w(TAG, "Native SRTLA stop returned code: " + result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping native SRTLA", e);
        } finally {
            isServiceRunning = false;
            isSrtlaRunning = false; // Clear the flag when SRTLA stops
        }
    }
    
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
    
    private void cleanupVirtualConnections() {
        Log.i(TAG, "Cleaning up virtual connections...");
        
        for (java.util.Map.Entry<String, Integer> entry : virtualConnections.entrySet()) {
            int socketFD = entry.getValue();
            if (socketFD >= 0) {
                // Note: We don't close these sockets here because they were transferred
                // to native code ownership. The native code is responsible for closing them.
                // Attempting to close them here would cause fdsan crashes.
                Log.i(TAG, "Virtual connection " + entry.getKey() + " (FD: " + socketFD + ") - ownership transferred to native code");
            }
        }
        
        virtualConnections.clear();
        
        // Clear DatagramSocket references - native code now owns the FDs
        // We don't close these as native code will close the underlying FDs
        activeDatagramSockets.clear();
        
        Log.i(TAG, "Virtual connections cleanup complete - native code handles socket cleanup");
    }
    
    private File createVirtualIpsFile() throws IOException {
        File ipsFile = new File(getFilesDir(), "native_srtla_virtual_ips.txt");
        
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
    /**
     * Create a network-bound socket and return the file descriptor.
     * The DatagramSocket is stored in activeDatagramSockets to prevent GC from closing it.
     * 
     * @param network The network to bind the socket to
     * @param virtualIP The virtual IP to use as a key for storing the socket reference
     * @return The socket file descriptor, or -1 on failure
     */
    private int createNetworkSocket(Network network, String virtualIP) {
        try {
            // Use the proper Android API: DatagramSocket → Network.bindSocket → ParcelFileDescriptor
            // This is the standard approach for multi-network socket binding
            DatagramSocket datagramSocket = new DatagramSocket();
            
            // Bind the socket to the specific network
            // This MUST succeed for true multi-network routing
            network.bindSocket(datagramSocket);
            
            // Use ParcelFileDescriptor to properly extract and detach the FD
            // detachFd() transfers ownership to native code - Java will NOT close the FD
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(datagramSocket);
            int socketFD = pfd.detachFd();
            
            // CRITICAL: Store DatagramSocket to prevent GC from closing the socket!
            // Even after detachFd(), the DatagramSocket still has a reference to the underlying
            // socket and will close it when garbage collected. In release builds, GC is more
            // aggressive than debug builds, which is why debug works but release fails.
            // DO NOT close old sockets - native code owns those FDs!
            activeDatagramSockets.put(virtualIP, datagramSocket);
            
            Log.i(TAG, "Successfully created network-bound socket (FD: " + socketFD + ") for " + virtualIP);
            return socketFD;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create network-bound socket: " + e.getMessage(), e);
            return -1;
        }
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
     * Create a notification with default settings for running service
     * (non-dismissible, does not auto-cancel)
     * @return A non-dismissible Notification configured for running service (ongoing=true, autoCancel=false)
     */
    private Notification createNotification(String contentText) {
        return createNotification(contentText, true, false);
    }
    
    /**
     * Create a notification with customizable dismissibility
     * @param contentText The text to display in the notification
     * @param ongoing If true, notification cannot be dismissed by user (for running service)
     * @param autoCancel If true, notification dismisses when tapped (for stopped service)
     * @return The configured Notification object
     */
    private Notification createNotification(String contentText, boolean ongoing, boolean autoCancel) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bond Bunny")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void updateNotification(String contentText) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
    }
    
    private void postStoppedNotification(String contentText) {
        // Create a dismissible notification when service stops
        // Using same notification ID updates the existing notification in place
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, false, true));
    }
    
    private void broadcastError(String errorMessage) {
        Intent intent = new Intent("srtla-error");
        intent.putExtra("error_message", errorMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    private String validateSrtlaConfig() {
        if (srtlaHost == null || srtlaHost.trim().isEmpty()) {
            return "Hostname is empty";
        }
        
        try {
            int port = Integer.parseInt(srtlaPort);
            
            // Actually resolve the hostname to test DNS
            Log.i(TAG, "Testing DNS resolution for: " + srtlaHost);
            InetAddress address = InetAddress.getByName(srtlaHost);
            if (address == null) {
                return "Cannot resolve hostname: " + srtlaHost;
            }
            Log.i(TAG, "DNS resolution successful: " + srtlaHost + " -> " + address.getHostAddress());
            
        } catch (NumberFormatException e) {
            return "Invalid port number: " + srtlaPort;
        } catch (java.net.UnknownHostException e) {
            return "Cannot resolve hostname: " + srtlaHost + " (DNS lookup failed)";
        } catch (Exception e) {
            return "Invalid hostname or port: " + srtlaHost + ":" + srtlaPort + " (" + e.getMessage() + ")";
        }
        
        return null; // No error
    }
    
    private void handleStartupError(String errorMessage) {
        Log.e(TAG, "Cannot start native SRTLA. Settings validation error: " + errorMessage);
        broadcastError(errorMessage);
        updateNotification("Settings validation error: " + errorMessage);
        stopSelf();
    }
    
    // Static methods for external access
    public static boolean isServiceRunning() {
        // Check both service state and native state for accuracy
        try {
            return isServiceRunning && NativeSrtlaJni.isRunningSrtlaNative();
        } catch (Exception e) {
            Log.w(TAG, "Error checking native SRTLA state", e);
            return isServiceRunning;
        }
    }
    
    public static void startService(Context context, String srtlaHost, String srtlaPort, String listenPort) {
        Intent intent = new Intent(context, NativeSrtlaService.class);
        intent.putExtra("srtla_host", srtlaHost);
        intent.putExtra("srtla_port", srtlaPort);
        intent.putExtra("listen_port", listenPort);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
    
    public static void stopService(Context context) {
        Intent intent = new Intent(context, NativeSrtlaService.class);
        context.stopService(intent);
    }
    
    /**
     * Sync internal state with actual native state
     * Call this periodically to ensure consistency
     */
    public static void syncState() {
        try {
            boolean nativeRunning = NativeSrtlaJni.isRunningSrtlaNative();
            if (isServiceRunning && !nativeRunning) {
                Log.w(TAG, "State sync: Service thinks it's running but native is stopped");
                isServiceRunning = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error syncing state", e);
        }
    }
    
    /**
     * Get simple statistics from native SRTLA
     * @return formatted statistics string
     */
    public static String getNativeStats() {
        try {
            // Log.i(TAG, "getNativeStats called, isServiceRunning=" + isServiceRunning);
            
            if (!isServiceRunning || !NativeSrtlaJni.isRunningSrtlaNative()) {
                // Log.i(TAG, "Native SRTLA not running, returning default message");
                return "No native SRTLA connections";
            }
            
            // Log.i(TAG, "Calling optimized native stats function...");
            // Single JNI call instead of 4 separate calls - much more efficient!
            String nativeStats = NativeSrtlaJni.getAllStats();
            
            // Add timestamp to see if values change over time
            // Log.i(TAG, "Stats timestamp: " + System.currentTimeMillis());
            // Log.i(TAG, "Native stats result: " + nativeStats);
            
            return nativeStats;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting native stats", e);
            return "Error getting native stats: " + e.getMessage();
        }
    }
    
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
     * Manually recreate sockets for all currently available networks
     * This is needed when service restarts but callbacks don't fire (networks already connected)
     */
    private void recreateNetworkSockets() {
        Log.i(TAG, "Recreating network sockets for currently available networks");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.e(TAG, "ConnectivityManager not available");
            return;
        }
        
        // Get all currently active networks
        Network[] networks = cm.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
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
                    int socket = createNetworkSocket(network, virtualIP);
                    if (socket >= 0) {
                        virtualConnections.put(virtualIP, socket);
                        NativeSrtlaJni.setNetworkSocket(virtualIP, realIP, networkTypeId, socket);
                        Log.i(TAG, "DEDICATED: Successfully setup " + networkType + " connection: " + virtualIP + " -> " + realIP + " (socket: " + socket + ")");
                        
                        // Signal that we have our first connection
                        firstConnectionLatch.countDown();
                        
                        // Update virtual IPs file if service is running
                        if (isServiceRunning) {
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
                if (isServiceRunning) {
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
     * Create notification channel (API 26+)
     */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "SRTLA Service";
            String description = "Notifications for SRTLA service status";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}