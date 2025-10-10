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
import android.util.Log;
import androidx.core.app.NotificationCompat;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.net.Inet4Address;
import java.net.InetAddress;
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
    
    // Service state
    private static boolean isServiceRunning = false;
    private String srtlaHost;
    private String srtlaPort;
    private String listenPort;
    
    // Network monitoring
    private ConnectivityManager connectivityManager;
    private NetworkCallback networkCallback;
    
    // Native methods are accessed through NativeSrtlaJni wrapper
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Use the same notification channel as EnhancedSrtlaService
        EnhancedSrtlaService.createNotificationChannel(this);
        
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
            
            // Setup Application-Level Virtual IPs
            setupVirtualConnections();
            
            // Create IPs file with virtual IPs instead of real IPs
            File ipsFile = createVirtualIpsFile();
            
            // Update notification
            updateNotification("Starting native SRTLA...");
            
            // Start native SRTLA
            int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort, ipsFile.getAbsolutePath());
            
            if (result == 0) {
                Log.i(TAG, "Native SRTLA started successfully");
                // Verify native state before marking as running
                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    isServiceRunning = true;
                    updateNotification("Native SRTLA running on port " + listenPort);
                } else {
                    Log.w(TAG, "Native SRTLA start returned 0 but process is not running");
                    updateNotification("Native SRTLA failed to start");
                    stopSelf();
                }
            } else {
                Log.e(TAG, "Native SRTLA failed to start with code: " + result);
                updateNotification("Native SRTLA failed to start (code: " + result + ")");
                stopSelf();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting native SRTLA", e);
            updateNotification("Native SRTLA error: " + e.getMessage());
            stopSelf();
        }
    }
    
    private void stopNativeSrtla() {
        try {
            Log.i(TAG, "Stopping native SRTLA process...");
            updateNotification("Stopping native SRTLA...");
            
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
                            updateNotification("Native SRTLA stopped");
                        } else {
                            Log.w(TAG, "Native SRTLA process still running after stop signal");
                            updateNotification("Native SRTLA stopping...");
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
                                    NativeSrtlaJni.setNetworkSocket("10.0.1.1", realIP, 1, wifiSocket);
                                    Log.i(TAG, "Setup virtual WiFi connection: 10.0.1.1 -> " + realIP);
                                }
                            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                // Setup Cellular virtual connection
                                int cellularSocket = createNetworkSocket(network);
                                if (cellularSocket >= 0) {
                                    NativeSrtlaJni.setNetworkSocket("10.0.2.1", realIP, 2, cellularSocket);
                                    Log.i(TAG, "Setup virtual Cellular connection: 10.0.2.1 -> " + realIP);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up virtual connections", e);
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
            // Write virtual IPs instead of real IPs
            writer.write("10.0.1.1\n");  // WiFi virtual IP
            writer.write("10.0.2.1\n");  // Cellular virtual IP
            writer.flush();
        }
        
        Log.i(TAG, "Created virtual IPs file: " + ipsFile.getAbsolutePath() + 
              " (size: " + ipsFile.length() + " bytes)");
        
        return ipsFile;
    }
    
    private int createNetworkSocket(Network network) {
        try {
            java.net.DatagramSocket socket = new java.net.DatagramSocket();
            network.bindSocket(socket);
            
            // Extract the native socket file descriptor
            java.lang.reflect.Field field = socket.getClass().getDeclaredField("impl");
            field.setAccessible(true);
            Object impl = field.get(socket);
            
            java.lang.reflect.Field fdField = impl.getClass().getDeclaredField("fd");
            fdField.setAccessible(true);
            java.io.FileDescriptor fd = (java.io.FileDescriptor) fdField.get(impl);
            
            java.lang.reflect.Field intField = java.io.FileDescriptor.class.getDeclaredField("descriptor");
            intField.setAccessible(true);
            int socketFD = intField.getInt(fd);
            
            Log.i(TAG, "Created network-bound socket, FD: " + socketFD);
            return socketFD;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create network socket", e);
            return -1;
        }
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
    

    
    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, EnhancedSrtlaService.CHANNEL_ID)
            .setContentTitle("Bond Bunny")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void updateNotification(String contentText) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
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
            Log.i(TAG, "getNativeStats called, isServiceRunning=" + isServiceRunning);
            
            if (!isServiceRunning || !NativeSrtlaJni.isRunningSrtlaNative()) {
                Log.i(TAG, "Native SRTLA not running, returning default message");
                return "No native SRTLA connections";
            }
            
            Log.i(TAG, "Calling optimized native stats function...");
            // Single JNI call instead of 4 separate calls - much more efficient!
            String nativeStats = NativeSrtlaJni.getAllStats();
            
            // Add timestamp to see if values change over time
            Log.i(TAG, "Stats timestamp: " + System.currentTimeMillis());
            Log.i(TAG, "Native stats result: " + nativeStats);
            
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
            // Re-create the IPs file with current network interfaces
            File ipsFile = createNetworkIpsFile();
            Log.i(TAG, "Updated IPs file with current network interfaces");
            
            // Notify native SRTLA about the network change
            NativeSrtlaJni.notifyNetworkChange();
            Log.i(TAG, "Notified native SRTLA about network change");
            
            // Update notification to show network change
            updateNotification("Network changed - updating connections...");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling network change", e);
        }
    }
}