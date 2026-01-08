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
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.DatagramChannel;
import java.net.StandardProtocolFamily;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static boolean isListening = false;  // True when waiting for SRT stream
    private static String statusMessage = "";  // Current status for UI display
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
    
    // Track network ID + IP to avoid redundant socket recreation
    private java.util.Map<String, String> networkState = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Synchronization for waiting for first network connection
    private CountDownLatch firstConnectionLatch = new CountDownLatch(1);
    
    // Wakelock to keep CPU awake during network operations
    private PowerManager.WakeLock wakeLock;
    
    // Wi-Fi lock to maintain high-performance Wi-Fi
    private WifiManager.WifiLock wifiLock;
    
    // SRT listener thread and state
    private Thread srtListenerThread;
    private AtomicBoolean shouldStopListener = new AtomicBoolean(false);
    private DatagramSocket srtListenerSocket;
    private static final int SRT_IDLE_TIMEOUT_MS = 5000; // Stop SRTLA after 5 seconds of no SRT data
    
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
            
            // Start SRT listener thread - SRTLA will start when SRT connects
            startSrtListener();
        }
        
        return START_STICKY; // Restart if killed by system
    }
    
    /**
     * Start the SRT listener thread.
     * This thread waits for SRT data on the listen port, and starts/stops SRTLA accordingly.
     * When SRT data arrives → start SRTLA
     * When SRT data stops for SRT_IDLE_TIMEOUT_MS → stop SRTLA, go back to listening
     */
    private void startSrtListener() {
        shouldStopListener.set(false);
        isListening = true;  // Service is now active (listening mode)
        
        srtListenerThread = new Thread(() -> {
            Log.i(TAG, "SRT listener thread started");
            
            // Validate config first
            String validationError = validateSrtlaConfig();
            if (validationError != null) {
                handleStartupError(validationError);
                return;
            }
            
            while (!shouldStopListener.get()) {
                try {
                    // Wait for network connections
                    updateNotification("Waiting for network...");
                    waitForNetworkConnections();
                    
                    // Wait for SRT to connect
                    updateNotification("Waiting for SRT stream on port " + listenPort + "...");
                    Log.i(TAG, "Waiting for SRT stream on port " + listenPort);
                    
                    if (!waitForSrtConnection()) {
                        // Listener was stopped or error occurred
                        break;
                    }
                    
                    // SRT connected - start SRTLA
                    Log.i(TAG, "SRT stream detected, starting SRTLA...");
                    startSrtlaForStream();
                    
                    // Wait for SRTLA to finish (will stop when SRT disconnects)
                    waitForSrtlaToStop();
                    
                    Log.i(TAG, "SRTLA stopped, returning to SRT listener mode");
                    
                } catch (Exception e) {
                    if (!shouldStopListener.get()) {
                        Log.e(TAG, "Error in SRT listener loop", e);
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            
            Log.i(TAG, "SRT listener thread exiting");
        }, "SRTListenerThread");
        
        srtListenerThread.start();
    }
    
    /**
     * Wait for SRT connection by listening on the port.
     * Returns true when SRT data is detected, false if stopped or error.
     */
    private boolean waitForSrtConnection() {
        int port = Integer.parseInt(listenPort);
        int retryCount = 0;
        final int MAX_BIND_RETRIES = 10;  // Increased retries
        final int BIND_RETRY_DELAY_MS = 500;  // Faster retries
        
        // If native SRTLA is still running from a previous session, wait for it to stop
        if (NativeSrtlaJni.isRunningSrtlaNative()) {
            Log.w(TAG, "Native SRTLA still running, waiting for it to stop...");
            int waitCount = 0;
            while (NativeSrtlaJni.isRunningSrtlaNative() && waitCount < 10 && !shouldStopListener.get()) {
                try {
                    Thread.sleep(500);
                    waitCount++;
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (NativeSrtlaJni.isRunningSrtlaNative()) {
                Log.e(TAG, "Native SRTLA still running after waiting, forcing stop");
                NativeSrtlaJni.stopSrtlaNative();
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }
        
        while (retryCount < MAX_BIND_RETRIES && !shouldStopListener.get()) {
            try {
                Log.d(TAG, "Creating socket for port " + port + " (attempt " + (retryCount + 1) + ")");
                
                // Use DatagramChannel with explicit IPv4 protocol family
                DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
                srtListenerSocket = channel.socket();
                srtListenerSocket.setReuseAddress(true);
                
                // Bind to IPv4 0.0.0.0
                java.net.InetSocketAddress bindAddr = new java.net.InetSocketAddress("0.0.0.0", port);
                Log.d(TAG, "Binding to address: " + bindAddr);
                srtListenerSocket.bind(bindAddr);
                srtListenerSocket.setSoTimeout(1000);
                
                Log.i(TAG, "Socket bound to " + srtListenerSocket.getLocalAddress() + ":" + srtListenerSocket.getLocalPort());
                
                statusMessage = "⏳ Waiting for SRT stream on port " + port + "...";
                updateNotification(statusMessage);
                Log.i(TAG, "Listening for SRT on port " + port);
                
                byte[] buffer = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                
                int waitCount = 0;
                while (!shouldStopListener.get()) {
                    try {
                        srtListenerSocket.receive(packet);
                        // Got data - SRT is connecting!
                        Log.i(TAG, "Received SRT packet from " + packet.getAddress() + ":" + packet.getPort() + 
                              " (size: " + packet.getLength() + ")");
                        srtListenerSocket.close();
                        srtListenerSocket = null;
                        return true;
                    } catch (SocketTimeoutException e) {
                        // Timeout - log every 10 seconds to show we're still waiting
                        waitCount++;
                        if (waitCount % 10 == 0) {
                            Log.d(TAG, "Still waiting for SRT... (" + waitCount + " seconds)");
                        }
                    }
                }
                
                // If we exit the loop because shouldStopListener was set, close and return
                Log.i(TAG, "SRT listener stopped by request");
                if (srtListenerSocket != null && !srtListenerSocket.isClosed()) {
                    srtListenerSocket.close();
                    srtListenerSocket = null;
                }
                return false;
                
            } catch (java.net.BindException e) {
                retryCount++;
                String errorMsg = "Port " + port + " in use, retry " + retryCount + "/" + MAX_BIND_RETRIES;
                Log.w(TAG, errorMsg);
                statusMessage = "⚠️ " + errorMsg;
                updateNotification(statusMessage);
                
                if (retryCount < MAX_BIND_RETRIES) {
                    try {
                        Thread.sleep(BIND_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    String finalError = "❌ Cannot bind to port " + port + " - port in use";
                    statusMessage = finalError;
                    updateNotification(finalError);
                    broadcastError("Port " + port + " is already in use. Stop other apps using this port.");
                }
            } catch (Exception e) {
                if (!shouldStopListener.get()) {
                    String errorMsg = "❌ Error: " + e.getMessage();
                    Log.e(TAG, "Error waiting for SRT connection", e);
                    statusMessage = errorMsg;
                    updateNotification(errorMsg);
                    broadcastError("Failed to listen for SRT: " + e.getMessage());
                }
                break;
            } finally {
                if (srtListenerSocket != null && !srtListenerSocket.isClosed()) {
                    srtListenerSocket.close();
                    srtListenerSocket = null;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Start SRTLA for the current streaming session.
     */
    private void startSrtlaForStream() {
        try {
            Log.i(TAG, "Starting SRTLA for stream...");
            statusMessage = "🔗 Connecting to SRTLA server...";
            updateNotification(statusMessage);
            
            // Create IPs file with virtual IPs from detected networks
            File ipsFile = createVirtualIpsFile();
            
            // Start native SRTLA
            int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort, ipsFile.getAbsolutePath());
            
            if (result == 0) {
                Log.i(TAG, "SRTLA started successfully for stream");
                isSrtlaRunning = true;
                isServiceRunning = true;
                statusMessage = "✅ Streaming on port " + listenPort;
                updateNotification(statusMessage);
            } else {
                Log.e(TAG, "SRTLA failed to start with code: " + result);
                statusMessage = "❌ SRTLA failed to start (code: " + result + ")";
                updateNotification(statusMessage);
                broadcastError("SRTLA failed to start with error code: " + result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting SRTLA for stream", e);
            updateNotification("Error: " + e.getMessage());
        }
    }
    
    /**
     * Wait for SRTLA to stop (when SRT disconnects or error occurs).
     * Also monitors for idle (no data) and stops SRTLA if idle for SRT_IDLE_TIMEOUT_MS.
     */
    private void waitForSrtlaToStop() {
        long startTime = System.currentTimeMillis();
        long lastDataTime = System.currentTimeMillis();
        boolean hadDataBefore = false;
        
        try {
            while (isSrtlaRunning && !shouldStopListener.get()) {
                // Check if native SRTLA is still running
                if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                    Log.i(TAG, "SRTLA native process stopped");
                    break;
                }
                
                // Check if we have active data flow by looking at bitrates
                double[] bitrates = NativeSrtlaJni.getConnectionBitrates();
                double totalBitrate = 0;
                if (bitrates != null) {
                    for (double br : bitrates) {
                        totalBitrate += br;
                    }
                }
                boolean hasData = totalBitrate > 0;
                
                if (hasData) {
                    lastDataTime = System.currentTimeMillis();
                    if (!hadDataBefore) {
                        Log.i(TAG, "SRT data flow detected (" + (totalBitrate / 1000) + " kbps)");
                        hadDataBefore = true;
                    }
                } else {
                    // No data currently flowing
                    long idleTime;
                    String reason;
                    
                    if (hadDataBefore) {
                        // Had data before, now idle - use last data time
                        idleTime = System.currentTimeMillis() - lastDataTime;
                        reason = "SRT stream stopped";
                    } else {
                        // Never had data - use start time (give longer timeout for initial connection)
                        idleTime = System.currentTimeMillis() - startTime;
                        reason = "No SRT data received";
                    }
                    
                    // Log idle status periodically
                    if (idleTime > 0 && idleTime % 2000 < 500) {
                        Log.d(TAG, "Idle check: " + (idleTime / 1000) + "s, hadData=" + hadDataBefore + ", bitrate=" + totalBitrate);
                    }
                    
                    if (idleTime >= SRT_IDLE_TIMEOUT_MS) {
                        Log.i(TAG, reason + " for " + (idleTime / 1000) + " seconds, stopping SRTLA");
                        statusMessage = "⏸️ " + reason + ", returning to listening mode";
                        updateNotification(statusMessage);
                        
                        // Stop native SRTLA
                        NativeSrtlaJni.stopSrtlaNative();
                        
                        // Wait briefly for it to stop
                        Thread.sleep(500);
                        break;
                    }
                }
                
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Wait for SRTLA interrupted");
        }
        
        isSrtlaRunning = false;
        isServiceRunning = false;
    }
    
    private void stopSrtListener() {
        Log.i(TAG, "Stopping SRT listener...");
        shouldStopListener.set(true);
        isListening = false;
        
        // Close the listener socket to unblock receive()
        if (srtListenerSocket != null && !srtListenerSocket.isClosed()) {
            srtListenerSocket.close();
        }
        
        // Wait for listener thread to finish
        if (srtListenerThread != null && srtListenerThread.isAlive()) {
            try {
                srtListenerThread.join(2000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for SRT listener thread");
            }
        }
        srtListenerThread = null;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "NativeSrtlaService onDestroy");
        stopSrtListener();
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
    
    private void stopNativeSrtla() {
        try {
            Log.i(TAG, "Stopping native SRTLA process...");
            
            int result = NativeSrtlaJni.stopSrtlaNative();
            if (result == 0) {
                Log.i(TAG, "Native SRTLA stop signal sent successfully");
                
                // Wait synchronously for the native process to stop (up to 3 seconds)
                int waitCount = 0;
                while (NativeSrtlaJni.isRunningSrtlaNative() && waitCount < 6) {
                    try {
                        Thread.sleep(500);
                        waitCount++;
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                
                if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                    Log.i(TAG, "Native SRTLA process confirmed stopped");
                    postStoppedNotification("Service stopped");
                } else {
                    Log.w(TAG, "Native SRTLA process still running after stop signal");
                }
                
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
     * Uses ParcelFileDescriptor.detachFd() to properly transfer FD ownership to native code.
     */
    private int createNetworkSocket(Network network) {
        try {
            // Use the proper Android API: DatagramSocket → Network.bindSocket → ParcelFileDescriptor
            DatagramSocket datagramSocket = new DatagramSocket();
            
            // Bind the socket to the specific network
            network.bindSocket(datagramSocket);
            
            // Use ParcelFileDescriptor to properly extract and detach the FD
            // detachFd() transfers ownership to native code - Java will NOT close the FD
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(datagramSocket);
            int socketFD = pfd.detachFd();
            
            Log.i(TAG, "Successfully created network-bound socket (FD: " + socketFD + ")");
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
        // Service is "running" if we're listening for SRT OR actively streaming
        if (isListening) {
            return true;  // Waiting for SRT stream
        }
        // Check both service state and native state for accuracy
        try {
            return isServiceRunning && NativeSrtlaJni.isRunningSrtlaNative();
        } catch (Exception e) {
            Log.w(TAG, "Error checking native SRTLA state", e);
            return isServiceRunning;
        }
    }
    
    /**
     * Get current status message for UI display
     */
    public static String getStatusMessage() {
        return statusMessage;
    }
    
    /**
     * Check if service is in "waiting for SRT" mode
     */
    public static boolean isWaitingForSrt() {
        return isListening && !NativeSrtlaJni.isRunningSrtlaNative();
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
                    int socket = createNetworkSocket(network);
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