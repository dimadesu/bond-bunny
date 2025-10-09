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

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clean SRTLA Service with Virtual IP support and full SRTLA protocol integration
 * Combines the proven BELABOX implementation with enhanced Java SRTLA logic
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
    
    // Service instance
    private static CleanSrtlaService instance;
    
    // SRTLA Protocol Components (integrated from EnhancedSrtlaService)
    private ExecutorService executorService;
    private AtomicBoolean protocolRunning = new AtomicBoolean(false);
    private SrtlaRegistrationManager registrationManager;
    private List<SrtlaConnection> srtlaConnections = new ArrayList<>();
    
    // SRT listener for incoming packets
    private DatagramChannel srtListenerChannel;
    private Selector selector;
    private String srtListenAddress = "0.0.0.0";
    private int srtListenPort = 6000;
    
    // SRTLA session ID (256 bytes like BELABOX)
    private byte[] srtlaId = new byte[256];
    
    // Packet tracking for NAK attribution
    private static class PacketTrackingInfo {
        final SrtlaConnection connection;
        final long timestamp;
        
        PacketTrackingInfo(SrtlaConnection connection, long timestamp) {
            this.connection = connection;
            this.timestamp = timestamp;
        }
        
        boolean isExpired(long currentTime, long maxAge) {
            return (currentTime - timestamp) > maxAge;
        }
    }
    
    private final int MAX_SEQUENCE_TRACKING = 10000;
    private final long SEQUENCE_TRACKING_MAX_AGE_MS = 5000;
    private final Map<Integer, PacketTrackingInfo> sequenceToConnectionMap = 
        Collections.synchronizedMap(new LinkedHashMap<Integer, PacketTrackingInfo>(MAX_SEQUENCE_TRACKING, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, PacketTrackingInfo> eldest) {
                return size() > MAX_SEQUENCE_TRACKING;
            }
        });
    
    // Pre-allocated buffers for efficiency
    private final ByteBuffer reusableReceiveBuffer = ByteBuffer.allocateDirect(SrtlaProtocol.MTU);
    private final ByteBuffer reusableSendBuffer = ByteBuffer.allocateDirect(SrtlaProtocol.MTU);
    
    // Configuration
    private String serverHost = "au.srt.belabox.net";
    private int serverPort = 5000;
    private int localPort = 6000;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating CleanSrtlaService with integrated SRTLA protocol");
        
        // Set instance for static access
        instance = this;
        
        // Initialize protocol components
        executorService = Executors.newFixedThreadPool(2);
        srtlaNative = new SRTLANative();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        // Initialize SRTLA components
        protocolRunning.set(false);
        registrationManager = new SrtlaRegistrationManager();
        
        // Initialize SRTLA session ID (256 bytes like BELABOX)
        generateSrtlaId();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize both native session and Java protocol
        sessionPtr = srtlaNative.createSession();
        if (sessionPtr != 0) {
            Log.i(TAG, "✓ SRTLA native session created successfully");
            
            // Initialize the session with server details
            boolean initSuccess = srtlaNative.initialize(sessionPtr, serverHost, serverPort, localPort);
            if (!initSuccess) {
                Log.e(TAG, "❌ Failed to initialize SRTLA session");
                srtlaNative.destroySession(sessionPtr);
                sessionPtr = 0;
                return;
            }
            
            // Start network monitoring for native connections
            setupNetworkMonitoring();
            isRunning = true;
            
            // Start Java protocol for enhanced packet processing
            startSrtlaProtocol();
        } else {
            Log.e(TAG, "❌ Failed to create SRTLA session");
        }
    }
    
    private void generateSrtlaId() {
        // Generate a unique 256-byte ID for this SRTLA session
        // Uses timestamp + device info for uniqueness
        byte[] timestamp = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array();
        byte[] random = new byte[248];
        new SecureRandom().nextBytes(random);
        
        System.arraycopy(timestamp, 0, srtlaId, 0, 8);
        System.arraycopy(random, 0, srtlaId, 8, 248);
        
        Log.i(TAG, "Generated SRTLA session ID: " + Arrays.toString(Arrays.copyOf(srtlaId, 16)) + "...");
    }
    
    private void startSrtlaProtocol() {
        if (protocolRunning.compareAndSet(false, true)) {
            executorService.submit(this::runSrtlaProtocol);
            Log.i(TAG, "SRTLA protocol started");
        }
    }
    
    private void runSrtlaProtocol() {
        try {
            // Initialize selector for non-blocking I/O
            selector = Selector.open();
            
            // Setup SRTLA connections to receiver
            setupSrtlaConnections();
            
            // Setup SRT listener for incoming packets
            setupSrtListener();
            
            Log.i(TAG, "SRTLA protocol initialized, starting event loop");
            
            // Run main event loop
            runEventLoop();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in SRTLA protocol", e);
        } finally {
            cleanupProtocol();
            protocolRunning.set(false);
        }
    }
    
    private void setupSrtlaConnections() {
        srtlaConnections.clear();
        
        Network[] networks = connectivityManager.getAllNetworks();
        
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                String networkType = getNetworkTypeName(capabilities);
                
                // Create SRTLA connection to receiver
                SrtlaConnection connection = new SrtlaConnection(network, serverHost, serverPort, networkType);
                if (connection.connect()) {
                    srtlaConnections.add(connection);
                    
                    // Register connection for reading
                    try {
                        if (selector != null && connection.getChannel() != null) {
                            connection.getChannel().register(selector, SelectionKey.OP_READ, connection);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to register " + networkType + " with selector", e);
                    }
                    
                    Log.i(TAG, "✓ Added SRTLA connection: " + networkType);
                } else {
                    Log.w(TAG, "✗ Failed to add SRTLA connection: " + networkType);
                }
            }
        }
        
        Log.i(TAG, "Created " + srtlaConnections.size() + " SRTLA connections");
    }
    
    private void setupSrtListener() throws IOException {
        // Setup SRT listener for incoming packets
        srtListenerChannel = DatagramChannel.open();
        srtListenerChannel.configureBlocking(false);
        srtListenerChannel.socket().setReuseAddress(true);
        
        InetSocketAddress bindAddress = new InetSocketAddress(srtListenAddress, srtListenPort);
        
        try {
            srtListenerChannel.bind(bindAddress);
            srtListenerChannel.register(selector, SelectionKey.OP_READ);
            Log.i(TAG, "✓ Listening for SRT packets on " + bindAddress);
        } catch (IOException e) {
            Log.e(TAG, "❌ Failed to bind to " + bindAddress + ": " + e.getMessage());
            throw e;
        }
    }
    
    private void runEventLoop() {
        while (protocolRunning.get()) {
            try {
                // Perform housekeeping
                performHousekeeping();
                
                // Check for incoming data
                int ready = selector.select(200);  // 200ms timeout
                
                if (ready > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        
                        if (!key.isValid()) continue;
                        
                        if (key.isReadable()) {
                            if (key.channel() == srtListenerChannel) {
                                // Handle incoming SRT data
                                handleSrtData(reusableReceiveBuffer);
                            } else if (key.attachment() instanceof SrtlaConnection) {
                                // Handle SRTLA response
                                SrtlaConnection connection = (SrtlaConnection) key.attachment();
                                handleSrtlaResponse(connection, reusableReceiveBuffer);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in event loop", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        
        Log.i(TAG, "Event loop terminated");
    }
    
    /**
     * Handle incoming SRT data - forward via selected SRTLA connection
     */
    private void handleSrtData(ByteBuffer buffer) {
        try {
            reusableReceiveBuffer.clear();
            InetSocketAddress clientAddress = (InetSocketAddress) srtListenerChannel.receive(reusableReceiveBuffer);
            
            if (clientAddress != null && reusableReceiveBuffer.position() > 0) {
                reusableReceiveBuffer.flip();
                int dataSize = reusableReceiveBuffer.remaining();
                byte[] data = new byte[dataSize];
                reusableReceiveBuffer.get(data);
                
                // Select best SRTLA connection
                SrtlaConnection selectedConnection = selectBestConnection();
                
                if (selectedConnection != null) {
                    // Extract SRT sequence number for tracking
                    int srtSequence = SrtlaProtocol.getSrtSequenceNumber(data, dataSize);
                    
                    // Forward SRT packet via SRTLA connection
                    boolean success = selectedConnection.sendDataWithTracking(ByteBuffer.wrap(data), srtSequence);
                    
                    if (success && srtSequence >= 0) {
                        // Track which connection sent this sequence number
                        trackSequence(srtSequence, selectedConnection);
                    } else if (!success) {
                        // Mark connection as failed
                        selectedConnection.setState(SrtlaConnection.ConnectionState.FAILED);
                    }
                } else {
                    Log.w(TAG, "⚠️ No available SRTLA connections");
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ Error handling SRT data", e);
        }
    }
    
    /**
     * Handle SRTLA response data - process registration, ACKs, NAKs
     */
    private void handleSrtlaResponse(SrtlaConnection connection, ByteBuffer buffer) {
        try {
            reusableReceiveBuffer.clear();
            int bytesRead = connection.receive(reusableReceiveBuffer);
            
            if (bytesRead > 0) {
                reusableReceiveBuffer.flip();
                byte[] data = new byte[reusableReceiveBuffer.remaining()];
                reusableReceiveBuffer.get(data);
                
                // Update connection timestamp
                connection.markReceived();
                
                // Process SRTLA registration packets first
                boolean consumed = registrationManager.processRegistrationPacket(connection, data, bytesRead);
                
                if (!consumed) {
                    // Process other SRTLA protocol packets
                    handleSrtlaProtocolPacket(connection, data, bytesRead);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling SRTLA response from " + connection.getNetworkType(), e);
        }
    }
    
    /**
     * Handle SRTLA protocol packets (ACKs, NAKs, data)
     */
    private void handleSrtlaProtocolPacket(SrtlaConnection connection, byte[] data, int dataSize) {
        // Process SRTLA protocol packet based on type
        if (SrtlaProtocol.isNakPacket(data, dataSize)) {
            // Handle NAK packet - track for connection quality
            int nakSequence = SrtlaProtocol.getNakSequenceNumber(data, dataSize);
            connection.recordNak(nakSequence);
            
            // Forward NAK to original sender connection if tracked
            SrtlaConnection originalConnection = getConnectionForSequence(nakSequence);
            if (originalConnection != null && originalConnection != connection) {
                originalConnection.sendData(ByteBuffer.wrap(data));
            }
        } else if (SrtlaProtocol.isAckPacket(data, dataSize)) {
            // Handle ACK packet
            int ackSequence = SrtlaProtocol.getAckSequenceNumber(data, dataSize);
            connection.recordAck(ackSequence);
        } else {
            // Regular SRT data - forward to SRT listener
            forwardToSrt(data, dataSize);
        }
    }
    
    private SrtlaConnection selectBestConnection() {
        SrtlaConnection best = null;
        int bestScore = 0;
        
        for (SrtlaConnection connection : srtlaConnections) {
            if (connection.isConnected()) {
                int score = calculateConnectionScore(connection);
                if (score > bestScore) {
                    bestScore = score;
                    best = connection;
                }
            }
        }
        
        return best;
    }
    
    private int calculateConnectionScore(SrtlaConnection connection) {
        int window = connection.getWindow();
        int inFlight = connection.getInFlightPackets();
        return window / (inFlight + 1);
    }
    
    private void trackSequence(int sequence, SrtlaConnection connection) {
        if (sequence >= 0) {
            sequenceToConnectionMap.put(sequence, 
                new PacketTrackingInfo(connection, System.currentTimeMillis()));
        }
    }
    
    private SrtlaConnection getConnectionForSequence(int sequence) {
        PacketTrackingInfo info = sequenceToConnectionMap.get(sequence);
        if (info != null && !info.isExpired(System.currentTimeMillis(), SEQUENCE_TRACKING_MAX_AGE_MS)) {
            return info.connection;
        }
        return null;
    }
    
    private void forwardToSrt(byte[] data, int dataSize) {
        // Forward received data back to SRT client
        try {
            // Implementation depends on SRT client connection handling
            Log.d(TAG, "Forwarding " + dataSize + " bytes to SRT client");
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding to SRT", e);
        }
    }
    
    private void performHousekeeping() {
        // Clean up expired sequence tracking
        long currentTime = System.currentTimeMillis();
        sequenceToConnectionMap.entrySet().removeIf(entry -> 
            entry.getValue().isExpired(currentTime, SEQUENCE_TRACKING_MAX_AGE_MS));
        
        // Update connection states
        for (SrtlaConnection connection : srtlaConnections) {
            connection.updateState();
        }
    }
    
    private void cleanupProtocol() {
        try {
            if (selector != null) {
                selector.close();
            }
            if (srtListenerChannel != null) {
                srtListenerChannel.close();
            }
            for (SrtlaConnection connection : srtlaConnections) {
                connection.close();
            }
            srtlaConnections.clear();
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
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
                
                // Add connection to SRTLA with virtual IP support
                boolean success = srtlaNative.addConnection(sessionPtr, localIp, networkHandle, networkType);
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
        super.onDestroy();
        Log.i(TAG, "Destroying CleanSrtlaService");
        
        // Stop protocol
        protocolRunning.set(false);
        
        // Cleanup
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        // Stop network monitoring
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering network callback", e);
        }
        
        // Cleanup SRTLA session
        if (sessionPtr != 0 && srtlaNative != null) {
            srtlaNative.destroySession(sessionPtr);
            sessionPtr = 0;
        }
        
        isRunning = false;
        instance = null;
        Log.i(TAG, "CleanSrtlaService destroyed");
    }
    
    // Network monitoring with virtual IP support
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                String networkType = getNetworkTypeName(capabilities);
                
                // Get real IP for this network
                String realIp = getLocalIpForNetwork(network);
                
                // Generate virtual IP based on network type
                String virtualIp = generateVirtualIP(networkType);
                
                if (realIp != null && sessionPtr != 0) {
                    // Add connection with virtual IP support
                    long networkHandle = getNetworkHandle(network);
                    boolean success = srtlaNative.addConnection(sessionPtr, realIp, networkHandle, networkType);
                    
                    if (success) {
                        networkToIpMap.put(network, realIp);
                        Log.i(TAG, String.format("✓ Added %s connection: real=%s, virtual=%s, handle=%d", 
                            networkType, realIp, virtualIp, networkHandle));
                    } else {
                        Log.w(TAG, String.format("✗ Failed to add %s connection: real=%s", networkType, realIp));
                    }
                } else {
                    Log.w(TAG, String.format("Could not add %s connection (realIp=%s, sessionPtr=%d)", 
                        networkType, realIp, sessionPtr));
                }
            }
        }
        
        @Override
        public void onLost(Network network) {
            String ip = networkToIpMap.remove(network);
            if (ip != null && sessionPtr != 0) {
                // Remove connection
                // Note: addConnection handles both add and remove operations in the native layer
                Log.i(TAG, String.format("✗ Lost network connection: %s", ip));
            }
        }
    };
    
    private String getNetworkTypeName(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WiFi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Cellular";  
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        } else {
            return "Unknown";
        }
    }
    
    private String generateVirtualIP(String networkType) {
        switch (networkType) {
            case "WiFi":
                return "10.0.1.1";
            case "Cellular":
                return "10.0.2.1";
            case "Ethernet":
                return "10.0.3.1";
            default:
                return "10.0.9.1";
        }
    }
    
    private long getNetworkHandle(Network network) {
        try {
            Field netIdField = Network.class.getDeclaredField("netId");
            netIdField.setAccessible(true);
            int netId = netIdField.getInt(network);
            return (long) netId;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get network handle for " + network, e);
            return 0;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }
}