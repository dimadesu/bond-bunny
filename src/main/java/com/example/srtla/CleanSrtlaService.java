package com.example.srtla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Clean SRTLA Service using the proven BELABOX implementation
 * Much simpler and more reliable than the previous complex implementation
 */
public class CleanSrtlaService extends Service {
    private static final String TAG = "CleanSrtlaService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "SRTLA_SERVICE_CHANNEL";
    
    // SRTLA session management
    private SRTLANative srtlaNative;
    private long sessionPtr = 0;
    private volatile boolean isRunning = false;
    
    // Network management
    private ConnectivityManager connectivityManager;
    private final Map<Network, String> networkToIpMap = new ConcurrentHashMap<>();
    
    // Configuration
    private String serverHost = "au.srt.belabox.net";
    private int serverPort = 5000;
    private int localPort = 6000;
    
    // Service state
    private static volatile CleanSrtlaService instance = null;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "CleanSrtlaService created");
        
        instance = this;
        srtlaNative = new SRTLANative();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "CleanSrtlaService start command received");
        
        if (intent != null) {
            String action = intent.getAction();
            if ("START_SRTLA".equals(action)) {
                startSrtlaSession(intent);
            } else if ("STOP_SRTLA".equals(action)) {
                stopSrtlaSession();
            }
        }
        
        return START_STICKY;
    }
    
    private void startSrtlaSession(Intent intent) {
        if (isRunning) {
            Log.w(TAG, "SRTLA session already running");
            return;
        }
        
        // Extract configuration from intent
        if (intent.hasExtra("server_host")) {
            serverHost = intent.getStringExtra("server_host");
        }
        if (intent.hasExtra("server_port")) {
            serverPort = intent.getIntExtra("server_port", 5000);
        }
        if (intent.hasExtra("local_port")) {
            localPort = intent.getIntExtra("local_port", 6000);
        }
        
        Log.i(TAG, "Starting SRTLA session: " + serverHost + ":" + serverPort + " -> local:" + localPort);
        
        // Create SRTLA session
        sessionPtr = srtlaNative.createSession();
        if (sessionPtr == 0) {
            Log.e(TAG, "Failed to create SRTLA session");
            return;
        }
        
        // Initialize session
        boolean success = srtlaNative.initialize(sessionPtr, serverHost, serverPort, localPort);
        if (!success) {
            Log.e(TAG, "Failed to initialize SRTLA session");
            srtlaNative.destroySession(sessionPtr);
            sessionPtr = 0;
            return;
        }
        
        isRunning = true;
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Set up network monitoring
        setupNetworkMonitoring();
        
        Log.i(TAG, "SRTLA session started successfully");
    }
    
    private void stopSrtlaSession() {
        if (!isRunning) {
            Log.w(TAG, "SRTLA session not running");
            return;
        }
        
        Log.i(TAG, "Stopping SRTLA session");
        
        isRunning = false;
        
        // Clean up network monitoring
        cleanupNetworkMonitoring();
        
        // Shutdown SRTLA session
        if (sessionPtr != 0) {
            srtlaNative.shutdown(sessionPtr);
            srtlaNative.destroySession(sessionPtr);
            sessionPtr = 0;
        }
        
        // Stop foreground service
        stopForeground(true);
        
        Log.i(TAG, "SRTLA session stopped");
    }
    
    private void setupNetworkMonitoring() {
        // Monitor WiFi networks
        NetworkRequest.Builder wifiBuilder = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        
        connectivityManager.registerNetworkCallback(wifiBuilder.build(), new NetworkCallback("WiFi"));
        
        // Monitor Cellular networks
        NetworkRequest.Builder cellularBuilder = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        
        connectivityManager.registerNetworkCallback(cellularBuilder.build(), new NetworkCallback("Cellular"));
        
        Log.i(TAG, "Network monitoring started");
    }
    
    private void cleanupNetworkMonitoring() {
        // Remove all connections
        for (Network network : networkToIpMap.keySet()) {
            String localIp = networkToIpMap.get(network);
            if (sessionPtr != 0 && localIp != null) {
                srtlaNative.removeConnection(sessionPtr, localIp);
            }
        }
        
        networkToIpMap.clear();
        
        Log.i(TAG, "Network monitoring cleaned up");
    }
    
    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        private final String networkType;
        
        public NetworkCallback(String networkType) {
            this.networkType = networkType;
        }
        
        @Override
        public void onAvailable(Network network) {
            if (!isRunning || sessionPtr == 0) return;
            
            try {
                // Get network properties
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps == null) return;
                
                // Get local IP address for this network
                String localIp = getLocalIpForNetwork(network);
                if (localIp == null) {
                    Log.w(TAG, "Could not determine local IP for " + networkType + " network");
                    return;
                }
                
                // Get network handle for binding
                long networkHandle = network.getNetworkHandle();
                
                // Add connection to SRTLA
                boolean success = srtlaNative.addConnection(sessionPtr, localIp, networkHandle);
                if (success) {
                    networkToIpMap.put(network, localIp);
                    Log.i(TAG, "Added " + networkType + " connection: " + localIp + " (handle=" + networkHandle + ")");
                } else {
                    Log.e(TAG, "Failed to add " + networkType + " connection: " + localIp);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error adding " + networkType + " network", e);
            }
        }
        
        @Override
        public void onLost(Network network) {
            String localIp = networkToIpMap.remove(network);
            if (localIp != null && sessionPtr != 0) {
                srtlaNative.removeConnection(sessionPtr, localIp);
                Log.i(TAG, "Removed " + networkType + " connection: " + localIp);
            }
        }
    }
    
    private String getLocalIpForNetwork(Network network) {
        try {
            // Use LinkProperties to get the actual local IP address for this network
            android.net.LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    java.net.InetAddress address = linkAddress.getAddress();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String localIp = address.getHostAddress();
                        Log.d(TAG, "Found local IP for network: " + localIp);
                        return localIp;
                    }
                }
            }
            
            // Fallback: try to get IP through socket binding (if LinkProperties fails)
            java.net.Socket socket = network.getSocketFactory().createSocket();
            try {
                socket.connect(new java.net.InetSocketAddress("8.8.8.8", 53), 3000);
                String localIp = socket.getLocalAddress().getHostAddress();
                Log.d(TAG, "Found local IP via socket binding: " + localIp);
                return localIp;
            } finally {
                socket.close();
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Could not determine local IP for network: " + e.getMessage());
        }
        
        return null;
    }
    
    // Public API for getting connection stats
    public int getActiveConnectionCount() {
        if (sessionPtr == 0) return 0;
        return srtlaNative.getActiveConnectionCount(sessionPtr);
    }
    
    public String[] getConnectionStats() {
        if (sessionPtr == 0) return new String[0];
        return srtlaNative.getConnectionStats(sessionPtr);
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public static CleanSrtlaService getInstance() {
        return instance;
    }
    
    // Notification management
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SRTLA Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("SRTLA connection bonding service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        int activeConnections = getActiveConnectionCount();
        String contentText = activeConnections > 0 ? 
                activeConnections + " connections active" : 
                "Starting connections...";
        
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bond Bunny SRTLA")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "CleanSrtlaService destroyed");
        stopSrtlaSession();
        instance = null;
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }
}