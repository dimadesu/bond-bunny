package com.example.srtla;

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
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

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
    private String srtlaHost;
    private String srtlaPort;
    private String listenPort;
    
    // Network monitoring
    private ConnectivityManager connectivityManager;
    private NetworkCallback networkCallback;
    
    // Virtual connection tracking
    private java.util.Map<String, Integer> virtualConnections = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Native methods are accessed through NativeSrtlaJni wrapper
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Create notification channel
        createNotificationChannel(this);
        
        // Initialize network monitoring
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        setupNetworkMonitoring();
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
        teardownNetworkMonitoring();
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
            
            // Setup Application-Level Virtual IPs
            setupVirtualConnections();
            
            // Create IPs file with virtual IPs instead of real IPs
            File ipsFile = createVirtualIpsFile();
            
            // Update notification
            updateNotification("Starting service...");
            
            // Start native SRTLA
            int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort, ipsFile.getAbsolutePath());
            
            if (result == 0) {
                Log.i(TAG, "Native SRTLA started successfully");
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
        }
    }
    
    private File createNetworkIpsFile() throws IOException {
        File ipsFile = new File(getFilesDir(), "native_srtla_ips.txt");
        
        // Delete existing file
        if (ipsFile.exists()) {
            ipsFile.delete();
            Log.i(TAG, "Deleted existing IPs file");
        }
        
        List<String> networkIps = getRealNetworkIPs();
        if (networkIps.isEmpty()) {
            throw new RuntimeException("No network interfaces found - device may not be connected to any networks");
        }
        
        try (FileWriter writer = new FileWriter(ipsFile, false)) {
            for (String ip : networkIps) {
                writer.write(ip + "\n");
                Log.i(TAG, "Writing IP to file: " + ip);
            }
            writer.flush();
        }
        
        Log.i(TAG, "Created IPs file: " + ipsFile.getAbsolutePath() + 
              " (size: " + ipsFile.length() + " bytes)");
        
        return ipsFile;
    }
    
    private void setupVirtualConnections() {
        Log.i(TAG, "Setting up Application-Level Virtual IP connections...");
        
        try {
            // Clean up old virtual connections
            cleanupVirtualConnections();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network[] networks = connectivityManager.getAllNetworks();
                
                for (Network network : networks) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && 
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        
                        String realIP = getNetworkIP(network);
                        if (realIP != null) {
                            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                // Setup WiFi virtual connection
                                int wifiSocket = createNetworkSocket(network);
                                if (wifiSocket >= 0) {
                                    virtualConnections.put("10.0.1.1", wifiSocket);
                                    NativeSrtlaJni.setNetworkSocket("10.0.1.1", realIP, 1, wifiSocket);
                                    Log.i(TAG, "Setup virtual WiFi connection: 10.0.1.1 -> " + realIP + " (socket: " + wifiSocket + ")");
                                }
                            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                // Setup Cellular virtual connection
                                int cellularSocket = createNetworkSocket(network);
                                if (cellularSocket >= 0) {
                                    virtualConnections.put("10.0.2.1", cellularSocket);
                                    NativeSrtlaJni.setNetworkSocket("10.0.2.1", realIP, 2, cellularSocket);
                                    Log.i(TAG, "Setup virtual Cellular connection: 10.0.2.1 -> " + realIP + " (socket: " + cellularSocket + ")");
                                }
                            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                // Setup Ethernet virtual connection
                                int ethernetSocket = createNetworkSocket(network);
                                if (ethernetSocket >= 0) {
                                    virtualConnections.put("10.0.3.1", ethernetSocket);
                                    NativeSrtlaJni.setNetworkSocket("10.0.3.1", realIP, 3, ethernetSocket);
                                    Log.i(TAG, "Setup virtual Ethernet connection: 10.0.3.1 -> " + realIP + " (socket: " + ethernetSocket + ")");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up virtual connections", e);
        }
        
        Log.i(TAG, "Virtual connections setup complete. Active connections: " + virtualConnections.size());
    }
    
    private void cleanupVirtualConnections() {
        Log.i(TAG, "Cleaning up old virtual connections...");
        
        for (java.util.Map.Entry<String, Integer> entry : virtualConnections.entrySet()) {
            int socketFD = entry.getValue();
            try {
                // Close the socket file descriptor using native close
                if (socketFD >= 0) {
                    closeSocketNative(socketFD);
                    Log.i(TAG, "Closed socket FD " + socketFD + " for virtual IP " + entry.getKey());
                }
            } catch (Exception e) {
                Log.w(TAG, "Error closing socket FD " + socketFD, e);
            }
        }
        
        virtualConnections.clear();
        Log.i(TAG, "Virtual connections cleanup complete");
    }
    
    private void updateVirtualConnections() {
        Log.i(TAG, "Updating virtual connections (keeping existing where possible)...");
        
        try {
            // Get current network state
            java.util.Set<String> currentVirtualIPs = new java.util.HashSet<>();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network[] networks = connectivityManager.getAllNetworks();
                
                for (Network network : networks) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && 
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        
                        String realIP = getNetworkIP(network);
                        if (realIP != null) {
                            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                currentVirtualIPs.add("10.0.1.1");
                            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                currentVirtualIPs.add("10.0.2.1");
                            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                currentVirtualIPs.add("10.0.3.1");
                            }
                        }
                    }
                }
            }
            
            // Remove virtual connections that are no longer valid
            java.util.Iterator<java.util.Map.Entry<String, Integer>> iterator = virtualConnections.entrySet().iterator();
            while (iterator.hasNext()) {
                java.util.Map.Entry<String, Integer> entry = iterator.next();
                if (!currentVirtualIPs.contains(entry.getKey())) {
                    Log.i(TAG, "Removing virtual connection for " + entry.getKey());
                    closeSocketNative(entry.getValue());
                    iterator.remove();
                }
            }
            
            // Add new virtual connections for networks we don't have yet
            for (String virtualIP : currentVirtualIPs) {
                if (!virtualConnections.containsKey(virtualIP)) {
                    Log.i(TAG, "Adding new virtual connection for " + virtualIP);
                    // Re-setup this specific virtual connection
                    setupSpecificVirtualConnection(virtualIP);
                }
            }
            
            // Update the virtual IPs file
            createVirtualIpsFile();
            
            Log.i(TAG, "Virtual connections update complete. Active: " + virtualConnections.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating virtual connections", e);
        }
    }
    
    private void setupSpecificVirtualConnection(String virtualIP) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network[] networks = connectivityManager.getAllNetworks();
                
                for (Network network : networks) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && 
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        
                        String realIP = getNetworkIP(network);
                        if (realIP != null) {
                            boolean isWiFiRequest = virtualIP.equals("10.0.1.1");
                            boolean isCellularRequest = virtualIP.equals("10.0.2.1");
                            boolean isEthernetRequest = virtualIP.equals("10.0.3.1");
                            boolean isWiFiNetwork = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                            boolean isCellularNetwork = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                            boolean isEthernetNetwork = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                            
                            if ((isWiFiRequest && isWiFiNetwork) || (isCellularRequest && isCellularNetwork) || (isEthernetRequest && isEthernetNetwork)) {
                                int socket = createNetworkSocket(network);
                                if (socket >= 0) {
                                    virtualConnections.put(virtualIP, socket);
                                    int networkType = isWiFiNetwork ? 1 : (isCellularNetwork ? 2 : 3);
                                    NativeSrtlaJni.setNetworkSocket(virtualIP, realIP, networkType, socket);
                                    Log.i(TAG, "Setup virtual connection: " + virtualIP + " -> " + realIP + " (socket: " + socket + ")");
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up virtual connection for " + virtualIP, e);
        }
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
    
    private int createNetworkSocket(Network network) {
        try {
            // Create a native UDP socket using JNI
            int socketFD = createUdpSocketNative();
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
                
                Log.i(TAG, "Successfully bound native socket FD " + socketFD + " to network " + network);
                return socketFD;
                
            } catch (Exception reflectionEx) {
                Log.w(TAG, "Reflection approach failed, trying alternative", reflectionEx);
                
                // Alternative: Create a DatagramSocket and bind it to the network
                java.net.DatagramSocket socket = new java.net.DatagramSocket();
                network.bindSocket(socket);
                
                // The socket is now bound to the network, but we return our native FD
                // Note: This means the native socket may not be bound, but the network is selected
                socket.close();
                
                Log.i(TAG, "Used DatagramSocket binding approach for socket FD " + socketFD);
                return socketFD;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create and bind network socket: " + e.getMessage(), e);
            return -1;
        }
    }
    

    
    private java.net.InetAddress getNetworkLocalAddress(Network network) {
        try {
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    java.net.InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network local address", e);
        }
        return null;
    }
    
    private void storeNetworkSocket(Network network, int socketFD) {
        // Store the network-socket mapping for potential future use
        // For now, just log it - the socket FD will be passed to native code
        Log.i(TAG, "Stored mapping: Network " + network + " -> Socket FD " + socketFD);
    }
    
    private String getNetworkIP(Network network) {
        try {
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network IP", e);
        }
        return null;
    }
    
    private List<String> getRealNetworkIPs() {
        List<String> ips = new ArrayList<>();
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use ConnectivityManager to get only actually connected networks
                Network[] networks = connectivityManager.getAllNetworks();
                
                for (Network network : networks) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && 
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        
                        // Get the network's interface
                        android.net.LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                        if (linkProperties != null) {
                            for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                                java.net.InetAddress address = linkAddress.getAddress();
                                if (address instanceof Inet4Address && 
                                    !address.isLoopbackAddress() && 
                                    !address.isLinkLocalAddress()) {
                                    
                                    String ip = address.getHostAddress();
                                    String interfaceName = linkProperties.getInterfaceName();
                                    
                                    // Determine network type
                                    String networkType = "unknown";
                                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                        networkType = "WiFi";
                                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                        networkType = "Cellular";
                                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                        networkType = "Ethernet";
                                    }
                                    
                                    ips.add(ip);
                                    Log.i(TAG, "Found active network IP: " + ip + 
                                          " on interface: " + interfaceName + 
                                          " type: " + networkType);
                                }
                            }
                        }
                    }
                }
            } else {
                // Fallback for older Android versions
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    
                    // Skip loopback and inactive interfaces
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        continue;
                    }
                    
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        
                        // Only IPv4, not loopback, not link-local
                        if (address instanceof Inet4Address && 
                            !address.isLoopbackAddress() && 
                            !address.isLinkLocalAddress()) {
                            
                            String ip = address.getHostAddress();
                            ips.add(ip);
                            Log.i(TAG, "Found network IP (fallback): " + ip + 
                                  " on interface: " + networkInterface.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network IPs", e);
        }
        
        Log.i(TAG, "Total active network IPs found: " + ips.size());
        return ips;
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
        Intent intent = new Intent("com.example.srtla.ERROR");
        intent.putExtra("error_message", errorMessage);
        sendBroadcast(intent);
    }
    
    private String validateSrtlaConfig() {
        if (srtlaHost == null || srtlaHost.trim().isEmpty()) {
            return "Hostname is empty";
        }
        
        try {
            int port = Integer.parseInt(srtlaPort);
            InetSocketAddress testAddress = new InetSocketAddress(srtlaHost, port);
            if (testAddress.isUnresolved()) {
                return "Cannot resolve hostname: " + srtlaHost;
            }
        } catch (NumberFormatException e) {
            return "Invalid port number: " + srtlaPort;
        } catch (Exception e) {
            return "Invalid hostname or port: " + srtlaHost + ":" + srtlaPort;
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
     * Set up network monitoring to detect WiFi/cellular changes
     */
    private void setupNetworkMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new NetworkCallback();
            
            // Monitor all networks (WiFi, cellular, etc.)
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            
            connectivityManager.registerNetworkCallback(builder.build(), networkCallback);
            Log.i(TAG, "Network monitoring enabled");
        } else {
            Log.w(TAG, "Network monitoring not available on this Android version");
        }
    }
    
    /**
     * Clean up network monitoring
     */
    private void teardownNetworkMonitoring() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                Log.i(TAG, "Network monitoring disabled");
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering network callback", e);
            }
            networkCallback = null;
        }
    }
    
    /**
     * Network callback to detect connectivity changes
     */
    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            Log.i(TAG, "Network available: " + network);
            if (isServiceRunning) {
                handleNetworkChange("Network available");
            }
        }
        
        @Override
        public void onLost(Network network) {
            Log.i(TAG, "Network lost: " + network);
            if (isServiceRunning) {
                handleNetworkChange("Network lost");
            }
        }
        
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            Log.i(TAG, "Network capabilities changed: " + network);
            if (isServiceRunning) {
                handleNetworkChange("Network capabilities changed");
            }
        }
    }
    
    /**
     * Handle network changes by updating SRTLA connections
     */
    private void handleNetworkChange(String reason) {
        Log.i(TAG, "Handling network change: " + reason);
        
        try {
            // Check if SRTLA is actively running before disrupting connections
            boolean srtlaRunning = isServiceRunning && NativeSrtlaJni.isRunningSrtlaNative();
            
            if (srtlaRunning) {
                // For running SRTLA, use conservative update approach
                Log.i(TAG, "SRTLA is running - using conservative virtual connection update");
                updateVirtualConnections();
                NativeSrtlaJni.notifyNetworkChange();
            } else {
                // SRTLA not running - safe to recreate virtual connections from scratch
                Log.i(TAG, "SRTLA not running - recreating virtual connections from scratch");
                setupVirtualConnections();
            }
            
            Log.i(TAG, "Completed network change handling");
            
            // Don't update notification text during network changes while service is running
            // This keeps the notification stable and less confusing for users
            
            // Broadcast network change to update UI immediately
            Intent networkChangeIntent = new Intent("com.example.srtla.NETWORK_CHANGED");
            networkChangeIntent.putExtra("reason", reason);
            sendBroadcast(networkChangeIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling network change", e);
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