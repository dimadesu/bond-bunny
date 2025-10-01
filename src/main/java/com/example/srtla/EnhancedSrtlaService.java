package com.example.srtla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced SRTLA Service with full protocol support
 * Based on srtla_send.c functionality
 */
public class EnhancedSrtlaService extends Service {
    private static final String TAG = "EnhancedSrtlaService";
    public static final String CHANNEL_ID = "BondBunnyNotificationChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int HOUSEKEEPING_INTERVAL_MS = 1000;  // Dialed down from 250ms to 1000ms for conservative recovery
    
    // Window management constants (from Swift SRTLA implementation)
    private static final int WINDOW_STABLE_MINIMUM = 10;
    private static final int WINDOW_STABLE_MAXIMUM = 20;
    private static final int WINDOW_MULTIPLY = 1000;
    
    // Static reference for statistics sharing
    private static EnhancedSrtlaService instance;
    
    private ConnectivityManager connectivityManager;
    private PowerManager.WakeLock wakeLock;
    private ExecutorService executorService;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private NotificationManagerCompat notificationManagerCompat;
    
    // Configuration - Android app replaces srtla_send
    private String srtlaReceiverHost;  // Where to send SRTLA packets (like srtla_rec)
    private int srtlaReceiverPort;
    private String srtListenAddress = "0.0.0.0";  // Listen for SRT packets
    private int srtListenPort = 6000;  // Port to receive SRT from srt-live-transmit
    private String srtStreamId;
    
    // SRTLA Sender Components (Android app replaces srtla_send.c)
    private SrtlaRegistrationManager registrationManager;
    private List<SrtlaConnection> srtlaConnections = new ArrayList<>();
    
    // Global sequence tracking for proper NAK attribution (CRITICAL FIX)
    // Enhanced with LRU cache and timestamp tracking for accurate attribution
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
    
    // Use LinkedHashMap with access-order for proper LRU eviction
    // Optimized capacity: 6Mbps stream = ~570 pps, with 5s buffer = ~2,850 packets per connection
    // Support 2 connections = ~5,700 packets, round up to 10,000 for safety
    // Memory: 10K entries × 64 bytes = 640 KB (vs 50K entries = 3.2 MB)
    // Rationale: 99.9% of NAKs arrive within 2-3 RTTs (~400ms), 5s covers all realistic cases
    private final int MAX_SEQUENCE_TRACKING = 10000;
    private final long SEQUENCE_TRACKING_MAX_AGE_MS = 5000; // 5 seconds (reduced from 30s)
    
    @SuppressWarnings("serial")
    private final Map<Integer, PacketTrackingInfo> sequenceToConnectionMap = 
        Collections.synchronizedMap(new LinkedHashMap<Integer, PacketTrackingInfo>(MAX_SEQUENCE_TRACKING, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, PacketTrackingInfo> eldest) {
                // Auto-remove oldest entries when capacity is exceeded
                return size() > MAX_SEQUENCE_TRACKING;
            }
        });
    
    private long lastSequenceMapCleanup = 0;
    private static final long SEQUENCE_MAP_CLEANUP_INTERVAL_MS = 5000; // Clean up every 5 seconds
    
    private DatagramChannel srtListenChannel;  // Listen for SRT packets (like listenfd in srtla_send.c)
    private Selector selector;
    
    // Track SRT client address for bidirectional communication  
    private InetSocketAddress lastSrtClientAddress;
    
    // Housekeeping
    private long lastHousekeeping = 0;
    private long allFailedAt = 0;
    
    // Multi-connection load balancing
    private int roundRobinIndex = 0;
    
    // Connection stickiness to reduce switching frequency
    private long lastConnectionSwitch = 0;
    private static final long MIN_SWITCH_INTERVAL_MS = 500; // Minimum 500ms between switches
    private static boolean stickinessEnabled = true; // Default enabled, can be toggled via UI
    private static boolean qualityScoringEnabled = true; // Enable NAK-based quality scoring
    private static boolean networkPriorityEnabled = true; // Enable network type priority scaling
    private static boolean explorationEnabled = true; // Enable connection exploration
    private static boolean classicMode = false; // Classic SRTLA algorithm mode (disables all enhancements)
    private SrtlaConnection lastSelectedConnection = null;
    
    // Smart reconnection management (moblink-inspired)
    private SrtlaReconnectionManager reconnectionManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        instance = this;
        
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnhancedSrtlaService::WakeLock");
        
        executorService = Executors.newCachedThreadPool();
        registrationManager = new SrtlaRegistrationManager();
        
        // Initialize smart reconnection manager with callback
        reconnectionManager = new SrtlaReconnectionManager(new SrtlaReconnectionManager.ReconnectionCallback() {
            @Override
            public void onReconnectAttempt(String networkType, String reason) {
                SrtlaLogger.info(TAG, "Reconnection attempt: " + networkType + " - " + reason);
            }
            
            @Override
            public boolean attemptReconnection(String networkType) {
                return attemptNetworkReconnection(networkType);
            }
            
            @Override
            public void onReconnectResult(String networkType, boolean success) {
                SrtlaLogger.logReconnection(networkType, "Callback result", success);
                if (success) {
                    SrtlaLogger.info(TAG, "Successfully reconnected to " + networkType);
                }
            }
        });
        
        // Set up registration callback for REG2 broadcasting
        registrationManager.setCallback(new SrtlaRegistrationManager.RegistrationCallback() {
            @Override
            public void broadcastReg2ToAllConnections(byte[] srtlaId) {
                sendReg2ToAllConnections(srtlaId);
            }
        });
        
        createNotificationChannel(getApplicationContext());
        notificationManagerCompat = NotificationManagerCompat.from(this);

        Log.i(TAG, "Enhanced SRTLA Service created with protocol support");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            srtlaReceiverHost = intent.getStringExtra("srtla_receiver_host");
            srtlaReceiverPort = intent.getIntExtra("srtla_receiver_port", 5000);
            srtListenAddress = intent.getStringExtra("srt_listen_address");
            srtListenPort = intent.getIntExtra("srt_listen_port", 2222);
            
            try {
                Notification notif = createNotification();
                if (notif == null) {
                    Log.w(TAG, "createNotification() returned null - posting empty placeholder");
                    Intent mainIntent = new Intent(this, MainActivity.class);
                    PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Bond Bunny")
                        .setContentText("Service running")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .build();
                }

                startForeground(NOTIFICATION_ID, notif);
                // Ensure the notification is posted/updated via the compatibility manager as well
                Log.i(TAG, "startForeground called - posting/updating notification via NotificationManagerCompat");
                try {
                    updateNotification();
                } catch (Exception e) {
                    Log.w(TAG, "updateNotification() failed", e);
                }
            } catch (Exception e) {
                // Log but continue - we don't want a notification failure to crash the service
                Log.e(TAG, "Failed to start foreground notification", e);
            }
            
            if (isRunning.compareAndSet(false, true)) {
                Log.i(TAG, "Starting Android SRTLA Sender - listening for SRT on port " + srtListenPort + 
                    ", forwarding to " + srtlaReceiverHost + ":" + srtlaReceiverPort);
                startSrtlaSender();
            }
        }
        
        return START_STICKY;
    }
    
    private void startSrtlaSender() {
        executorService.submit(() -> {
            try {
                wakeLock.acquire();
                
                Log.i(TAG, "Starting SRTLA Sender Mode - listening for SRT on " + srtListenAddress + ":" + srtListenPort);
                
                // Setup selector first (needed by both SRT listener and SRTLA connections)
                selector = Selector.open();
                
                // Setup network connections to SRTLA receiver (like srtla_send.c connecting to srtla_addr)
                setupSrtlaConnections();
                
                // Setup SRT listener (like srtla_send.c listenfd)
                setupSrtListener();
                
                runEventLoop();
                
            } catch (Exception e) {
                Log.e(TAG, "Error in SRTLA sender", e);
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        });
    }
    
    private void setupSrtlaConnections() {
        srtlaConnections.clear();
        
        Network[] networks = connectivityManager.getAllNetworks();
        
        // Connect to ALL available networks with weighted load balancing (like srtla_send.c)
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                String networkType = getNetworkTypeName(capabilities);
                
                // Connect to SRTLA receiver (like srtla_send.c connecting to srtla_addr)
                SrtlaConnection connection = new SrtlaConnection(network, srtlaReceiverHost, srtlaReceiverPort, networkType);
                if (connection.connect()) {
                    srtlaConnections.add(connection);
                    
                    // Register connection for reading (matches srtla_send.c add_active_fd)
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
        
        Log.i(TAG, "Created " + srtlaConnections.size() + " SRTLA connections to receiver");
    }
    
    private void setupSrtListener() throws IOException {
        // Selector is already created in startSrtlaSender()
        
        // Setup SRT listener (equivalent to srtla_send.c listenfd)
        srtListenChannel = DatagramChannel.open();
        srtListenChannel.configureBlocking(false);
        
        // Enable SO_REUSEADDR to help with port binding
        srtListenChannel.socket().setReuseAddress(true);
        
        InetSocketAddress bindAddress = new InetSocketAddress(srtListenAddress, srtListenPort);
        
        try {
            srtListenChannel.bind(bindAddress);
            srtListenChannel.register(selector, SelectionKey.OP_READ);
            
            Log.i(TAG, "✓ Listening for SRT packets on " + bindAddress);
            Log.i(TAG, "✓ Socket bound successfully, SO_REUSEADDR=" + srtListenChannel.socket().getReuseAddress());
        } catch (IOException e) {
            Log.e(TAG, "❌ Failed to bind to " + bindAddress + ": " + e.getMessage());
            throw e;
        }
    }
    
    // Pre-allocated buffer pool for efficient packet processing
    private final ByteBuffer reusableReceiveBuffer = ByteBuffer.allocateDirect(SrtlaProtocol.MTU);
    private final ByteBuffer reusableSendBuffer = ByteBuffer.allocateDirect(SrtlaProtocol.MTU);
    
    private void runEventLoop() {
        while (isRunning.get()) {
            try {
                // Perform housekeeping (like srtla_send.c connection_housekeeping())
                performHousekeeping();
                
                // Check for incoming data (SRT or SRTLA responses)
                int ready = selector.select(200);  // 200ms timeout like srtla_send.c
                
                if (ready > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        
                        if (!key.isValid()) continue;
                        
                        if (key.isReadable()) {
                            if (key.channel() == srtListenChannel) {
                                // Handle incoming SRT data (like srtla_send.c handle_srt_data)
                                handleSrtData(reusableReceiveBuffer);
                            } else if (key.attachment() instanceof SrtlaConnection) {
                                // Handle SRTLA response (like srtla_send.c handle_srtla_data)
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
     * Handle incoming SRT data (equivalent to srtla_send.c handle_srt_data)
     * Receives SRT packets and forwards them via selected SRTLA connection
     */
    private void handleSrtData(ByteBuffer buffer) {
        try {
            reusableReceiveBuffer.clear();
            InetSocketAddress clientAddress = (InetSocketAddress) srtListenChannel.receive(reusableReceiveBuffer);
            
            if (clientAddress != null && reusableReceiveBuffer.position() > 0) {
                reusableReceiveBuffer.flip();
                int dataSize = reusableReceiveBuffer.remaining();
                byte[] data = new byte[dataSize];
                reusableReceiveBuffer.get(data);
                
                // Remember SRT client address for bidirectional communication
                lastSrtClientAddress = clientAddress;
                
                // Select SRTLA connection using weighted distribution (like srtla_send.c)
                SrtlaConnection selectedConnection = selectConnectionWeighted();
                
                if (selectedConnection != null) {
                    // Extract SRT sequence number for tracking
                    int srtSequence = SrtlaProtocol.getSrtSequenceNumber(data, dataSize);
                    
                    // Forward SRT packet via SRTLA connection
                    boolean success = selectedConnection.sendDataWithTracking(ByteBuffer.wrap(data), srtSequence);
                    
                    if (success && srtSequence >= 0) {
                        // CRITICAL FIX: Track which connection sent this sequence number
                        trackSequence(srtSequence, selectedConnection);
                        SrtlaLogger.trace(TAG, String.format("📍 Tracked seq=%d → %s (map: %d/%d, %.1f%% capacity)", 
                            srtSequence, selectedConnection.getNetworkType(), 
                            sequenceToConnectionMap.size(), MAX_SEQUENCE_TRACKING,
                            (sequenceToConnectionMap.size() * 100.0) / MAX_SEQUENCE_TRACKING));
                    } else if (!success) {
                        // Mark connection as failed (like srtla_send.c sets last_rcvd = 1)
                        selectedConnection.setState(SrtlaConnection.ConnectionState.FAILED);
                    }
                } else {
                    // Log only when no connections available (error condition)
                    SrtlaLogger.warn(TAG, "⚠️ No available SRTLA connections to forward SRT data (total connections: " + srtlaConnections.size() + ")");
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ Error handling SRT data", e);
        }
    }
    
    /**
     * Handle SRTLA response data (equivalent to srtla_send.c handle_srtla_data)
     * Processes registration packets, ACKs, NAKs, and forwards SRT responses
     */
    private void handleSrtlaResponse(SrtlaConnection connection, ByteBuffer buffer) {
        try {
            reusableReceiveBuffer.clear();
            int bytesRead = connection.receive(reusableReceiveBuffer);
            
            if (bytesRead > 0) {
                reusableReceiveBuffer.flip();
                byte[] data = new byte[reusableReceiveBuffer.remaining()];
                reusableReceiveBuffer.get(data);
                
                // Update connection timestamp (like srtla_send.c updates last_rcvd)
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
     * Select best SRTLA connection using Swift-style algorithm
     * Pure best-connection selection based on window size and in-flight packets
     */
    /**
     * Calculate connection score using Swift SRTLA algorithm (simpler and more conservative)
     * Based on: score = windowSize / (packetsInFlight.count + 1)
     */
    /**
     * Calculate connection score using configurable algorithm modes
     * Can operate in pure C SRTLA mode or enhanced Android mode
     */
    private int calculateConnectionScore(SrtlaConnection connection) {
        // Base score calculation (same for all modes)
        int window = connection.getWindow();
        int inFlight = connection.getInFlightPackets();
        int baseScore = window / (inFlight + 1);
        
        // In classic SRTLA mode, return just the base score
        if (classicMode) {
            return baseScore;
        }
        
        // Enhanced Android mode with configurable features
        float priority = networkPriorityEnabled ? getConnectionPriority(connection) : 1.0f;
        
        // Apply network priority scaling if enabled
        int enhancedScore;
        if (networkPriorityEnabled && window > WINDOW_STABLE_MAXIMUM * WINDOW_MULTIPLY) {
            // High window size - apply full priority scaling
            enhancedScore = (int) (baseScore * priority);
        } else if (networkPriorityEnabled && window > WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY) {
            // Moderate window size - apply scaled priority
            float factor = (float) (window - WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY) / 
                          (float) ((WINDOW_STABLE_MAXIMUM - WINDOW_STABLE_MINIMUM) * WINDOW_MULTIPLY);
            float scaledPriority = 1 + (priority - 1) * factor;
            enhancedScore = (int) (baseScore * scaledPriority);
        } else {
            // Low window size - no priority scaling
            enhancedScore = baseScore;
        }
        
        // Apply quality-based penalties if enabled
        if (!qualityScoringEnabled) {
            return enhancedScore;
        }
        
        // Quality-based scoring is enabled, apply NAK penalties
        long timeSinceLastNak = connection.getTimeSinceLastNak();
        int nakCount = connection.getNakCount();
        int nakBurstCount = connection.getNakBurstCount();
        
        float qualityMultiplier = 1.0f;
        
        if (timeSinceLastNak < 2000) {
            // Recent NAKs (< 2s): severe penalty to avoid bad connections
            qualityMultiplier = 0.1f;
        } else if (timeSinceLastNak < 5000) {
            // Somewhat recent NAKs (< 5s): moderate penalty
            qualityMultiplier = 0.5f;
        } else if (timeSinceLastNak < 10000) {
            // Old NAKs (< 10s): light penalty
            qualityMultiplier = 0.8f;
        } else {
            // No recent NAKs: full score + bonus for very clean connections
            if (nakCount == 0) {
                qualityMultiplier = 1.2f; // Bonus for connections with zero NAKs
            } else {
                qualityMultiplier = 1.0f; // Normal score for connections without recent NAKs
            }
        }
        
        // Extra penalty for burst NAKs (multiple NAKs in short time)
        if (nakBurstCount > 1 && timeSinceLastNak < 5000) {
            qualityMultiplier *= 0.5f; // Halve score for connections with NAK bursts
        }
        
        int finalScore = Math.max(1, (int) (enhancedScore * qualityMultiplier));
        
        // Log quality analysis for debugging (only for low scores to avoid spam)
        if (qualityMultiplier < 1.0f) {
            SrtlaLogger.debug("ConnectionQuality", connection.getNetworkType() + 
                " quality penalty: " + String.format("%.2f", qualityMultiplier) + 
                " (NAKs: " + nakCount + ", last: " + (timeSinceLastNak/1000) + "s ago, burst: " + nakBurstCount + 
                ") base: " + enhancedScore + " → final: " + finalScore);
        }
        
        return finalScore;
    }
    
    /**
     * Get network score for initial best network selection (before connections are established)
     * Based on network type priority - WiFi preferred over cellular
     */
    private int getNetworkScore(NetworkCapabilities capabilities) {
        int baseScore = 50; // Default score
        
        // WiFi gets higher priority
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            baseScore = 100;
        }
        // Cellular gets medium priority
        else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            baseScore = 80;
        }
        // Ethernet gets highest priority (if available)
        else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            baseScore = 120;
        }
        
        // Boost score for validated networks
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            baseScore += 10;
        }
        
        // Boost score for networks that don't require payment
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            baseScore += 5;
        }
        
        return baseScore;
    }
    
    /**
     * Get connection priority based on network type
     * WiFi gets highest priority, Cellular gets medium, Ethernet gets high
     */
    private float getConnectionPriority(SrtlaConnection connection) {
        String networkType = connection.getNetworkType();
        switch (networkType) {
            case "WiFi":
                return 2.0f;  // High priority for WiFi
            case "Cellular":
                return 1.5f;  // Medium priority for Cellular
            case "Ethernet":
                return 1.8f;  // High priority for Ethernet
            default:
                return 1.0f;  // Default priority
        }
    }
    
    /**
     * Get all active connections that are not timed out and not failed (from Swift implementation)
     */
    private List<SrtlaConnection> getActiveConnections() {
        List<SrtlaConnection> activeConnections = new ArrayList<>();
        for (SrtlaConnection connection : srtlaConnections) {
            if (!connection.isTimedOut() && 
                connection.getState() != SrtlaConnection.ConnectionState.FAILED &&
                connection.isConnected()) {
                activeConnections.add(connection);
            }
        }
        return activeConnections;
    }
    
    /**
     * Select connection using C SRTLA-style quality prioritization with periodic exploration
     * Prioritizes connections with fewer NAKs while occasionally testing other connections
     */
    private SrtlaConnection selectConnectionWeighted() {
        List<SrtlaConnection> activeConnections = getActiveConnections();
        
        if (activeConnections.isEmpty()) {
            return null;
        }
        
        if (activeConnections.size() == 1) {
            return activeConnections.get(0);
        }
        
        // Calculate quality-adjusted scores for all connections
        int totalScore = 0;
        int bestScore = 0;
        int[] scores = new int[activeConnections.size()];
        SrtlaConnection bestConnection = null;
        
        for (int i = 0; i < activeConnections.size(); i++) {
            scores[i] = Math.max(1, calculateConnectionScore(activeConnections.get(i)));
            totalScore += scores[i];
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestConnection = activeConnections.get(i);
            }
        }
        
        long currentTime = System.currentTimeMillis();
        boolean canSwitch = stickinessEnabled ? 
            (currentTime - lastConnectionSwitch) >= MIN_SWITCH_INTERVAL_MS : 
            true; // If stickiness is disabled, always allow switching
        
        // C SRTLA-style selection: Heavily favor best connection, but periodically explore others
        if (canSwitch) {
            lastConnectionSwitch = currentTime;
            
            // 90% of the time: use best connection (quality first)
            // 10% of the time: explore other connections to test their health (if exploration enabled)
            boolean shouldExplore = explorationEnabled && (currentTime / 5000) % 10 == 0; // Every ~5 seconds, explore for ~500ms
            
            if (shouldExplore && activeConnections.size() > 1) {
                // Exploration phase: try the second-best connection to test its current quality
                SrtlaConnection secondBest = null;
                int secondBestScore = 0;
                
                for (int i = 0; i < activeConnections.size(); i++) {
                    SrtlaConnection conn = activeConnections.get(i);
                    if (conn != bestConnection && scores[i] > secondBestScore) {
                        secondBestScore = scores[i];
                        secondBest = conn;
                    }
                }
                
                if (secondBest != null) {
                    lastSelectedConnection = secondBest;
                    SrtlaLogger.logConnectionSelection(secondBest.getNetworkType(), 
                        "exploration: " + secondBestScore + "/" + bestScore + " (testing alternative path)");
                    return secondBest;
                }
            }
        }
        
        // Default: Use best quality connection (like C SRTLA select_conn)
        if (bestConnection != null) {
            lastSelectedConnection = bestConnection;
            
            String selectionReason;
            if (!canSwitch && stickinessEnabled) {
                selectionReason = "sticky best connection";
            } else if (!stickinessEnabled) {
                selectionReason = "quality-first selection (stickiness disabled)";
            } else {
                selectionReason = "quality-first selection";
            }
            
            SrtlaLogger.logConnectionSelection(bestConnection.getNetworkType(), 
                "quality score: " + bestScore + " (" + selectionReason + ")");
            
            return bestConnection;
        }
        
        // Fallback to first available connection
        return activeConnections.get(0);
    }
    
    /**
     * Handle SRTLA protocol packets (equivalent to parts of srtla_send.c handle_srtla_data)
     */
    private void handleSrtlaProtocolPacket(SrtlaConnection connection, byte[] data, int length) {
        int packetType = SrtlaProtocol.getPacketType(data, length);
        
        switch (packetType) {
            case SrtlaProtocol.SRT_TYPE_ACK:
                // Handle SRT ACK - update all connections
                int ackSeq = SrtlaProtocol.parseSrtAck(data, length);
                if (ackSeq >= 0) {
                    for (SrtlaConnection conn : srtlaConnections) {
                        conn.handleSrtAck(ackSeq);
                    }
                }
                
                // Forward ACK back to SRT client
                forwardToSrtClient(data, length);
                break;
                
            case SrtlaProtocol.SRT_TYPE_NAK:
                // Handle SRT NAK - CRITICAL FIX: Find the connection that sent the lost packet
                int[] nakSeqs = SrtlaProtocol.parseSrtNak(data, length);
                
                // Log NAK for pattern analysis
                SrtlaLogger.logNak(connection.getNetworkType(), nakSeqs, 
                    connection.getWindow(), connection.getInFlightPackets());
                
                // Apply NAK to the correct connection that sent the packet (not the receiver)
                int correctAttributions = 0;
                int fallbackAttributions = 0;
                
                for (int nakSeq : nakSeqs) {
                    SrtlaConnection senderConnection = findConnectionForSequence(nakSeq);
                    if (senderConnection != null) {
                        senderConnection.handleNak(nakSeq);
                        correctAttributions++;
                        SrtlaLogger.trace(TAG, "✓ NAK seq=" + nakSeq + " correctly attributed to sender: " + 
                            senderConnection.getNetworkType() + " (received via " + connection.getNetworkType() + ")");
                    } else {
                        // Fallback: if we can't find the sender, apply to the receiver
                        connection.handleNak(nakSeq);
                        fallbackAttributions++;
                        SrtlaLogger.trace(TAG, "⚠️ NAK seq=" + nakSeq + " sender unknown, applied to receiver: " + 
                            connection.getNetworkType());
                    }
                }
                
                // Log attribution statistics for monitoring fix effectiveness
                if (nakSeqs.length > 0) {
                    SrtlaLogger.info(TAG, String.format("🎯 NAK Attribution: %d correct, %d fallback (%.1f%% accuracy)", 
                        correctAttributions, fallbackAttributions, 
                        (correctAttributions * 100.0) / nakSeqs.length));
                }
                
                // Forward NAK back to SRT client
                forwardToSrtClient(data, length);
                break;
                
            case SrtlaProtocol.SRTLA_TYPE_ACK:
                // Handle SRTLA ACK - connection-specific acknowledgment
                // Simple parsing: extract ACK numbers from packet payload
                // For now, just acknowledge receipt without detailed parsing
                break;
                
            case SrtlaProtocol.SRTLA_TYPE_KEEPALIVE:
                // Handle keepalive response for RTT measurement (Swift SRTLA style)
                connection.handleKeepaliveResponse(data, length);
                break;
                
            default:
                // Forward other SRT packets back to client
                if (packetType == SrtlaProtocol.SRT_TYPE_HANDSHAKE || 
                    packetType == SrtlaProtocol.SRT_TYPE_SHUTDOWN ||
                    packetType == SrtlaProtocol.SRT_TYPE_DATA ||
                    (packetType & 0x8000) != 0) {  // SRT control packets have 0x8000 bit set
                    forwardToSrtClient(data, length);
                }
                break;
        }
    }
    
    /**
     * CRITICAL FIX: Find which connection sent a packet with given sequence number
     * This ensures NAKs are applied to the correct connection, not just the receiver
     * Enhanced with timestamp validation to avoid stale entries
     */
    private SrtlaConnection findConnectionForSequence(int sequenceNumber) {
        PacketTrackingInfo info = sequenceToConnectionMap.get(sequenceNumber);
        if (info != null) {
            // Validate entry is not too old
            if (!info.isExpired(System.currentTimeMillis(), SEQUENCE_TRACKING_MAX_AGE_MS)) {
                return info.connection;
            } else {
                // Remove expired entry
                sequenceToConnectionMap.remove(sequenceNumber);
                SrtlaLogger.trace(TAG, "Removed expired sequence tracking for seq=" + sequenceNumber);
            }
        }
        return null;
    }
    
    /**
     * Track which connection sent a packet with given sequence number
     * Called when sending packets to maintain proper NAK attribution
     * Enhanced with timestamp tracking for age-based cleanup
     */
    private void trackSequence(int sequenceNumber, SrtlaConnection connection) {
        if (sequenceNumber < 0) return;
        
        // Store with timestamp for age tracking
        long timestamp = System.currentTimeMillis();
        PacketTrackingInfo info = new PacketTrackingInfo(connection, timestamp);
        sequenceToConnectionMap.put(sequenceNumber, info);
        
        // Note: LinkedHashMap with removeEldestEntry automatically handles capacity limit
        // No manual cleanup needed here - it will auto-remove oldest entry when MAX_SEQUENCE_TRACKING is exceeded
    }
    
    /**
     * Forward packet back to SRT client (equivalent to srtla_send.c sendto(listenfd, ...))
     */
    private void forwardToSrtClient(byte[] data, int length) {
        if (lastSrtClientAddress != null && srtListenChannel != null) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
                srtListenChannel.send(buffer, lastSrtClientAddress);
            } catch (IOException e) {
                Log.w(TAG, "Failed to forward data to SRT client", e);
            }
        }
    }
    
    // Cached timestamp for service-level operations
    private long cachedServiceTime = 0;
    private static final long SERVICE_TIMESTAMP_CACHE_INTERVAL = 50; // 50ms cache for service operations
    
    /**
     * Get cached timestamp for service operations
     */
    private long getServiceTime() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - cachedServiceTime >= SERVICE_TIMESTAMP_CACHE_INTERVAL) {
            cachedServiceTime = currentTime;
        }
        return cachedServiceTime;
    }
    
    /**
     * Clean up expired sequence tracking entries
     * Called periodically to remove stale mappings and prevent memory bloat
     */
    private void cleanupExpiredSequenceTracking(long currentTime) {
        // Only cleanup every SEQUENCE_MAP_CLEANUP_INTERVAL_MS to avoid overhead
        if (currentTime - lastSequenceMapCleanup < SEQUENCE_MAP_CLEANUP_INTERVAL_MS) {
            return;
        }
        lastSequenceMapCleanup = currentTime;
        
        int beforeSize = sequenceToConnectionMap.size();
        int removedCount = 0;
        
        // Remove expired entries
        Iterator<Map.Entry<Integer, PacketTrackingInfo>> iterator = 
            sequenceToConnectionMap.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<Integer, PacketTrackingInfo> entry = iterator.next();
            if (entry.getValue().isExpired(currentTime, SEQUENCE_TRACKING_MAX_AGE_MS)) {
                iterator.remove();
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            SrtlaLogger.info(TAG, String.format(
                "🧹 Sequence tracking cleanup: removed %d expired entries (%d → %d, %.1f%% capacity)",
                removedCount, beforeSize, sequenceToConnectionMap.size(),
                (sequenceToConnectionMap.size() * 100.0) / MAX_SEQUENCE_TRACKING
            ));
        }
        
        // Log warning if we're approaching capacity (>80%)
        if (sequenceToConnectionMap.size() > MAX_SEQUENCE_TRACKING * 0.8) {
            SrtlaLogger.warn(TAG, String.format(
                "⚠️ Sequence tracking at %.1f%% capacity (%d/%d) - consider increasing MAX_SEQUENCE_TRACKING",
                (sequenceToConnectionMap.size() * 100.0) / MAX_SEQUENCE_TRACKING,
                sequenceToConnectionMap.size(), MAX_SEQUENCE_TRACKING
            ));
        }
    }
    
    private void performHousekeeping() {
        // Throttle housekeeping to once per second with cached time
        long currentTime = getServiceTime();
        if (currentTime - lastHousekeeping < HOUSEKEEPING_INTERVAL_MS) {
            return;
        }
        lastHousekeeping = currentTime;
        
        registrationManager.performHousekeeping(srtlaConnections);
        
        // Perform window recovery for all connections
        for (SrtlaConnection connection : srtlaConnections) {
            if (connection.isConnected()) {
                connection.performWindowRecovery();
            }
        }
        
        // Periodic cleanup of expired sequence tracking entries
        cleanupExpiredSequenceTracking(currentTime);
        
        // Check for failed connections and schedule smart reconnections
        checkFailedConnectionsAndScheduleReconnects();
        
        // Check for new networks that we don't have connections for yet
        checkForNewNetworks(connectivityManager.getAllNetworks());
        
        // Check for global connection failure
        int activeConnections = registrationManager.getActiveConnections();
        
        if (activeConnections == 0) {
            if (allFailedAt == 0) {
                allFailedAt = currentTime; // Use cached time
            }
            
            if (registrationManager.hasConnected()) {
                SrtlaLogger.dev("EnhancedSrtlaService", "⚠️ No available SRTLA connections");
            }
            
            // Global timeout check with cached time
            long failedDuration = currentTime - allFailedAt;
            if (failedDuration > (SrtlaProtocol.GLOBAL_TIMEOUT * 1000)) {
                if (registrationManager.hasConnected()) {
                    SrtlaLogger.dev("EnhancedSrtlaService", "Failed to re-establish any SRTLA connections");
                } else {
                    Log.e(TAG, "Failed to establish any initial SRTLA connections");
                }
                // Could restart service or take other recovery action
            }
        } else {
            allFailedAt = 0;
        }
        
        Log.v(TAG, "Housekeeping: " + activeConnections + " active SRTLA connections");
        
        // Refresh the foreground notification with latest info
        try {
            updateNotification();
        } catch (Exception e) {
            Log.w(TAG, "Failed to update notification: " + e.getMessage());
        }
    }
    
    /**
     * Check for failed connections and schedule smart reconnections using SrtlaReconnectionManager
     */
    private void checkFailedConnectionsAndScheduleReconnects() {
        // Get available networks for validation
        Network[] networks = connectivityManager.getAllNetworks();
        
        // First, validate existing connections against current networks
        validateExistingConnections(networks);
        
        // Check each existing connection for failure and schedule reconnections
        for (SrtlaConnection connection : srtlaConnections) {
            if (connection.getState() == SrtlaConnection.ConnectionState.FAILED || 
                connection.isTimedOut()) {
                
                String networkType = connection.getNetworkType();
                String reason = connection.isTimedOut() ? "Connection timed out" : "Connection failed";
                
                // Use smart reconnection manager instead of manual logic
                if (reconnectionManager.shouldAttemptReconnection(networkType)) {
                    SrtlaLogger.info(TAG, "Scheduling reconnection for " + networkType + ": " + reason);
                    reconnectionManager.scheduleReconnection(networkType, reason);
                } else {
                    SrtlaLogger.debug(TAG, "Skipping reconnection for " + networkType + " - still in backoff period");
                }
            }
        }
        
        // Cleanup truly dead connections that have been failed for too long
        cleanupDeadConnections();
    }
    
    /**
     * Attempt to reconnect a specific network type (called by SrtlaReconnectionManager)
     */
    private boolean attemptNetworkReconnection(String networkType) {
        Network[] networks = connectivityManager.getAllNetworks();
        
        // First, verify that the network type is actually available
        boolean networkTypeAvailable = false;
        Network targetNetwork = null;
        
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                String currentNetworkType = getNetworkTypeName(capabilities);
                if (currentNetworkType.equals(networkType)) {
                    networkTypeAvailable = true;
                    targetNetwork = network;
                    break;
                }
            }
        }
        
        if (!networkTypeAvailable) {
            SrtlaLogger.warn(TAG, "Cannot reconnect " + networkType + " - network type not currently available");
            return false;
        }
        
        // Find the failed connection to replace
        for (int i = 0; i < srtlaConnections.size(); i++) {
            SrtlaConnection connection = srtlaConnections.get(i);
            
            if (connection.getNetworkType().equals(networkType) && 
                (connection.getState() == SrtlaConnection.ConnectionState.FAILED || 
                 connection.isTimedOut())) {
                
                // Close the failed connection
                try {
                    if (connection.getChannel() != null) {
                        connection.getChannel().close();
                    }
                } catch (Exception e) {
                    SrtlaLogger.warn(TAG, "Error closing failed connection channel: " + e.getMessage());
                }
                
                // Create new connection with the verified network
                SrtlaConnection newConnection = new SrtlaConnection(targetNetwork, srtlaReceiverHost, srtlaReceiverPort, networkType);
                if (newConnection.connect()) {
                    // Replace the failed connection
                    srtlaConnections.set(i, newConnection);
                    
                    // Register new connection for reading
                    try {
                        if (selector != null && newConnection.getChannel() != null) {
                            newConnection.getChannel().register(selector, SelectionKey.OP_READ, newConnection);
                        }
                    } catch (Exception e) {
                        SrtlaLogger.warn(TAG, "Failed to register reconnected " + networkType + " with selector: " + e.getMessage());
                    }
                    
                    // Mark successful connection in reconnection manager
                    reconnectionManager.markSuccessfulConnection(networkType);
                    
                    SrtlaLogger.info(TAG, "Successfully reconnected " + networkType + " connection");
                    return true;
                } else {
                    SrtlaLogger.warn(TAG, "Failed to reconnect " + networkType + " - connection attempt failed");
                    return false;
                }
            }
        }
        
        SrtlaLogger.debug(TAG, "No failed connection found for " + networkType + " to reconnect");
        return false;
    }
    
    /**
     * Validate existing connections against current available networks
     * Mark connections as failed if their network is no longer available
     */
    private void validateExistingConnections(Network[] currentNetworks) {
        for (SrtlaConnection connection : srtlaConnections) {
            Network connectionNetwork = connection.getNetwork();
            if (connectionNetwork != null) {
                boolean networkStillExists = false;
                
                // Check if the connection's network is still in the current networks list
                for (Network network : currentNetworks) {
                    if (network.equals(connectionNetwork)) {
                        // Also verify the network capabilities are still valid
                        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                        if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            networkStillExists = true;
                            break;
                        }
                    }
                }
                
                // If the network no longer exists or lost internet capability, mark connection as failed
                if (!networkStillExists && 
                    connection.getState() != SrtlaConnection.ConnectionState.FAILED &&
                    connection.getState() != SrtlaConnection.ConnectionState.DISCONNECTED) {
                    
                    Log.i(TAG, "🔌 Network no longer available for " + connection.getNetworkType() + " connection, marking as failed");
                    connection.setState(SrtlaConnection.ConnectionState.FAILED);
                }
            }
        }
    }
    
    /**
     * Remove connections that have been in FAILED state for an extended period
     * This prevents the connection list from growing indefinitely
     */
    private void cleanupDeadConnections() {
        long currentTime = System.currentTimeMillis();
        long deadConnectionThreshold = 5 * 60 * 1000; // 5 minutes
        
        for (int i = srtlaConnections.size() - 1; i >= 0; i--) {
            SrtlaConnection connection = srtlaConnections.get(i);
            
            if (connection.getState() == SrtlaConnection.ConnectionState.FAILED || 
                connection.getState() == SrtlaConnection.ConnectionState.DISCONNECTED) {
                
                String networkType = connection.getNetworkType();
                
                // Check if this connection has been failed for too long
                // Note: We don't track individual connection failure times anymore,
                // so we'll be more conservative and only remove truly stale connections
                if (!reconnectionManager.shouldAttemptReconnection(networkType)) {
                    Log.i(TAG, "🗑️ Removing dead " + networkType + " connection (no reconnection scheduled)");
                    
                    // Close the connection properly
                    connection.close();
                    srtlaConnections.remove(i);
                    
                    // Cancel any pending reconnections for this network type
                    reconnectionManager.cancelReconnection(networkType);
                }
            }
        }
    }
    
    /**
     * Check if there are new available networks we should connect to
     */
    private void checkForNewNetworks(Network[] networks) {
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                String networkType = getNetworkTypeName(capabilities);
                
                // First, clean up any existing failed/disconnected connections of this type
                // This is important when networks are toggled off/on (like mobile data)
                cleanupFailedConnectionsOfType(networkType);
                
                // Check if we already have a connection for this specific network or network type
                // ALSO check if there's a pending reconnection for this network type
                boolean hasConnection = false;
                boolean hasPendingReconnection = !reconnectionManager.shouldAttemptReconnection(networkType);
                
                for (SrtlaConnection connection : srtlaConnections) {
                    // Check both the specific network object and network type to prevent duplicates
                    if (connection.getNetwork() != null && connection.getNetwork().equals(network)) {
                        hasConnection = true;
                        Log.v(TAG, "Already have connection for specific " + networkType + " network (exact match)");
                        break;
                    }
                    // Also check if we have any connection of this type that's not failed or disconnected
                    if (connection.getNetworkType().equals(networkType)) {
                        SrtlaConnection.ConnectionState state = connection.getState();
                        // Consider a connection exists if it's in any state except FAILED or DISCONNECTED
                        if (state != SrtlaConnection.ConnectionState.FAILED && 
                            state != SrtlaConnection.ConnectionState.DISCONNECTED) {
                            hasConnection = true;
                            Log.v(TAG, "Already have " + networkType + " connection in state: " + state);
                            break;
                        }
                    }
                }
                
                if (!hasConnection && !hasPendingReconnection) {
                    Log.i(TAG, "🆕 Detected new available network: " + networkType + ", attempting to connect");
                    
                    // Debug: Show current connections before adding new one
                    Log.d(TAG, "Current connections before adding " + networkType + ":");
                    for (int j = 0; j < srtlaConnections.size(); j++) {
                        SrtlaConnection existing = srtlaConnections.get(j);
                        Log.d(TAG, "  [" + j + "] " + existing.getNetworkType() + " - " + existing.getState() + 
                              " (network: " + (existing.getNetwork() != null ? existing.getNetwork().toString() : "null") + ")");
                    }
                    
                    SrtlaConnection newConnection = new SrtlaConnection(network, srtlaReceiverHost, srtlaReceiverPort, networkType);
                    if (newConnection.connect()) {
                        srtlaConnections.add(newConnection);
                        
                        Log.i(TAG, "✓ Added new " + networkType + " connection. Total connections: " + srtlaConnections.size());
                        
                        // Register new connection for reading
                        try {
                            if (selector != null && newConnection.getChannel() != null) {
                                newConnection.getChannel().register(selector, SelectionKey.OP_READ, newConnection);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to register new " + networkType + " with selector", e);
                        }
                        
                        Log.i(TAG, "✓ Successfully connected to new " + networkType + " network");
                    } else {
                        Log.w(TAG, "❌ Failed to connect to new " + networkType + " network");
                    }
                } else {
                    if (hasConnection) {
                        Log.v(TAG, "Skipping " + networkType + " - already have connection");
                    } else if (hasPendingReconnection) {
                        Log.v(TAG, "Skipping " + networkType + " - pending reconnection scheduled");
                    }
                }
            }
        }
    }
    
    /**
     * Clean up any failed/disconnected connections of a specific network type
     * This is important when networks are toggled off/on (like mobile data)
     */
    private void cleanupFailedConnectionsOfType(String networkType) {
        for (int i = srtlaConnections.size() - 1; i >= 0; i--) {
            SrtlaConnection connection = srtlaConnections.get(i);
            
            if (connection.getNetworkType().equals(networkType)) {
                SrtlaConnection.ConnectionState state = connection.getState();
                
                // Remove connections that are failed or disconnected for this network type
                if (state == SrtlaConnection.ConnectionState.FAILED || 
                    state == SrtlaConnection.ConnectionState.DISCONNECTED) {
                    
                    Log.i(TAG, "🧹 Cleaning up old " + networkType + " connection in state: " + state);
                    
                    // Close the connection properly
                    connection.close();
                    srtlaConnections.remove(i);
                    
                    // Cancel any pending reconnections for this network type
                    reconnectionManager.cancelReconnection(networkType);
                }
            }
        }
    }
    
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
    
    private void sendReg2ToAllConnections(byte[] srtlaId) {
        Log.i(TAG, "Broadcasting REG2 to all " + srtlaConnections.size() + " connections");
        
        for (SrtlaConnection connection : srtlaConnections) {
            if (connection.isConnected()) {
                byte[] reg2Packet = SrtlaProtocol.createReg2Packet(srtlaId);
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(reg2Packet);
                    boolean success = connection.sendDataWithTracking(buffer, -1);
                    if (success) {
                        Log.v(TAG, "Sent REG2 to " + connection.getNetworkType());
                    } else {
                        Log.w(TAG, "Failed to send REG2 to " + connection.getNetworkType());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error sending REG2 to " + connection.getNetworkType(), e);
                }
            }
        }
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping Enhanced SRTLA Service");
        
        isRunning.set(false);
        
        if (selector != null && selector.isOpen()) {
            try {
                selector.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing selector", e);
            }
        }
        
        if (srtListenChannel != null && srtListenChannel.isOpen()) {
            try {
                srtListenChannel.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing SRT listen channel", e);
            }
        }
        
        for (SrtlaConnection connection : srtlaConnections) {
            connection.close();
        }
        srtlaConnections.clear();
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        if (reconnectionManager != null) {
            reconnectionManager.shutdown();
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        instance = null;
        
        super.onDestroy();
    }
    
    // Static method for UI to get connection statistics
    public static String getConnectionStatistics() {
        if (instance == null || instance.srtlaConnections == null) {
            return "No active connections";
        }
        
        List<SrtlaConnection> connections = instance.srtlaConnections;
        if (connections.isEmpty()) {
            return "No SRTLA connections established";
        }
        
        StringBuilder stats = new StringBuilder();
        long totalBytes = 0;
        long totalPackets = 0;
        double totalBitrate = 0;
        int activeConnections = 0;
        
        for (SrtlaConnection connection : connections) {
            if (connection.isConnected()) {
                activeConnections++;
                totalBytes += connection.getBytesUploaded();
                totalPackets += connection.getPacketsUploaded();
                totalBitrate += connection.getUploadSpeed(); // bits per second
                
                stats.append(connection.getFormattedStats()).append("\n\n");
            }
        }
        
        if (activeConnections == 0) {
            return "All connections inactive or failed";
        }
        
        double totalMB = totalBytes / (1024.0 * 1024.0);
        String bitrateFormatted = formatBitrate(totalBitrate);
        stats.insert(0, String.format("📊 Total: %s, %.2f MB uploaded, %d packets, %d active connections\n\n", 
                                     bitrateFormatted, totalMB, totalPackets, activeConnections));
        
        return stats.toString().trim();
    }
    
    /**
     * Format bitrate for display
     */
    private static String formatBitrate(double bitsPerSecond) {
        if (bitsPerSecond < 1000) {
            return String.format("%.0f bps", bitsPerSecond);
        } else if (bitsPerSecond < 1000000) {
            return String.format("%.1f Kbps", bitsPerSecond / 1000);
        } else if (bitsPerSecond < 1000000000) {
            return String.format("%.1f Mbps", bitsPerSecond / 1000000);
        } else {
            return String.format("%.1f Gbps", bitsPerSecond / 1000000000);
        }
    }
    
    /**
     * Get connection window data for visualization
     * @return List of connection window data for UI display
     */
    public static List<ConnectionWindowView.ConnectionWindowData> getConnectionWindowData() {
        List<ConnectionWindowView.ConnectionWindowData> windowData = new ArrayList<>();
        
        if (instance == null || instance.srtlaConnections == null) {
            return windowData;
        }
        
        SrtlaConnection selectedConnection = instance.getLastSelectedConnection();
        
        for (SrtlaConnection connection : instance.srtlaConnections) {
            boolean isSelected = (selectedConnection != null && selectedConnection == connection);
            int score = instance.calculateConnectionScore(connection);
            
            ConnectionWindowView.ConnectionWindowData data = new ConnectionWindowView.ConnectionWindowData(
                connection.getNetworkType(),
                connection.getWindow(),
                connection.getInFlightPackets(),
                score,
                connection.isConnected() && !connection.isTimedOut(),
                isSelected,
                (long) connection.getEstimatedRtt(),
                connection.getState().toString(),
                connection.getUploadSpeed() // bits per second
            );
            
            windowData.add(data);
        }
        
        return windowData;
    }
    
    /**
     * Check if the service is currently running
     * @return true if service is active and processing connections
     */
    public static boolean isServiceRunning() {
        return instance != null && instance.isRunning.get();
    }
    
    /**
     * Enable or disable connection stickiness
     * @param enabled true to enable stickiness, false to disable
     */
    public static void setStickinessEnabled(boolean enabled) {
        stickinessEnabled = enabled;
        SrtlaLogger.info("EnhancedSrtlaService", "Connection stickiness " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Get current stickiness state
     * @return true if stickiness is enabled
     */
    public static boolean isStickinessEnabled() {
        return stickinessEnabled;
    }
    
    /**
     * Enable or disable quality-based scoring (NAK penalties)
     * @param enabled true to enable quality scoring, false to disable
     */
    public static void setQualityScoringEnabled(boolean enabled) {
        qualityScoringEnabled = enabled;
        SrtlaLogger.info("EnhancedSrtlaService", "Quality-based scoring " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Get current quality scoring state
     * @return true if quality scoring is enabled
     */
    public static boolean isQualityScoringEnabled() {
        return qualityScoringEnabled;
    }
    
    /**
     * Enable or disable network priority scaling
     * @param enabled true to enable network priority, false to disable
     */
    public static void setNetworkPriorityEnabled(boolean enabled) {
        networkPriorityEnabled = enabled;
        SrtlaLogger.info("EnhancedSrtlaService", "Network priority scaling " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Get current network priority state
     * @return true if network priority is enabled
     */
    public static boolean isNetworkPriorityEnabled() {
        return networkPriorityEnabled;
    }
    
    /**
     * Enable or disable connection exploration
     * @param enabled true to enable exploration, false to disable
     */
    public static void setExplorationEnabled(boolean enabled) {
        explorationEnabled = enabled;
        SrtlaLogger.info("EnhancedSrtlaService", "Connection exploration " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Get current exploration state
     * @return true if exploration is enabled
     */
    public static boolean isExplorationEnabled() {
        return explorationEnabled;
    }
    
    /**
     * Enable or disable classic SRTLA algorithm mode (pure C SRTLA compatibility)
     * @param enabled true for classic mode, false for enhanced Android mode
     */
    public static void setClassicMode(boolean enabled) {
        classicMode = enabled;
        SrtlaLogger.info("EnhancedSrtlaService", "Classic SRTLA algorithm " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Get current classic mode state
     * @return true if classic mode is enabled
     */
    public static boolean isClassicMode() {
        return classicMode;
    }
    
    private SrtlaConnection getLastSelectedConnection() {
        return lastSelectedConnection;
    }

    // Centralized creation of the NotificationChannel so other components (Activity) can reuse it
    public static void createNotificationChannel(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Bond Bunny Notification",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Bond Bunny status notifications.");

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        String title = "Listening for SRT on port " + srtListenPort;
        String text = "Connection(s): " + srtlaConnections.size();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        return builder.build();
    }

    // Build and post an updated notification with current service state
    private void updateNotification() {
        if (notificationManagerCompat == null) return;

        String title = "Listening for SRT on port " + srtListenPort;
        String text = "Connection(s): " + srtlaConnections.size();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text));

        notificationManagerCompat.notify(NOTIFICATION_ID, builder.build());
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Get debug log for NAK pattern analysis
     */
    public static String getDebugLog() {
        return SrtlaLogger.getFormattedLog();
    }
    
    /**
     * Get NAK analysis for debugging connection switching issues
     */
    public static String getNakAnalysis() {
        return SrtlaLogger.getNakAnalysis();
    }
    
    /**
     * Clear debug logs
     */
    public static void clearDebugLogs() {
        SrtlaLogger.clearLog();
    }
    
    /**
     * Get reconnection statistics from the smart reconnection manager
     */
    public static String getReconnectionStats() {
        if (instance != null && instance.reconnectionManager != null) {
            return instance.reconnectionManager.getReconnectionStats();
        }
        return "Reconnection manager not available";
    }
}
