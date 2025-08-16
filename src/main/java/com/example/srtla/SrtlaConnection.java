package com.example.srtla;

import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced NetworkConnection with full SRTLA protocol support
 * Implements sophisticated window management, packet tracking, and flow control
 * Based on the conn_t structure from srtla_send.c
 */
public class SrtlaConnection {
    private static final String TAG = "SrtlaConnection";
    
    private Network network;
    private DatagramChannel channel;
    private String serverHost;
    private int serverPort;
    private String networkType;
    private boolean connected = false;
    private boolean removed = false;
    
    // Timing information (matches srtla_send.c)
    private long lastReceived = 0;
    private long lastSent = 0;
    private long lastActivity = 0;
    
    // SRTLA window management (matches srtla_send.c conn_t)
    private int window = SrtlaProtocol.WINDOW_DEF * SrtlaProtocol.WINDOW_MULT;
    private int inFlightPackets = 0;
    private int packetIndex = 0;
    // Cache for reducing object allocations in frequently called methods
    private final StringBuilder stringBuilder = new StringBuilder(64);
    
    // Cached AtomicInteger to avoid repeated instantiation for statistics
    private final AtomicInteger tempCounter = new AtomicInteger(0);
    
    // Pre-allocated array for efficient packet processing
    private static final int[] EMPTY_NAK_ARRAY = new int[0];
    
    private int[] packetLog = new int[SrtlaProtocol.PKT_LOG_SIZE];
    
    // Connection state for SRTLA registration
    public enum ConnectionState {
        DISCONNECTED,
        REGISTERING_REG1,
        REGISTERING_REG2,
        CONNECTED,
        FAILED
    }
    
    private ConnectionState state = ConnectionState.DISCONNECTED;
    private long stateChangeTime = 0;
    
    // Statistics tracking
    private long bytesUploaded = 0;
    private long packetsUploaded = 0;
    private long connectionStartTime = 0;
    private double estimatedRtt = 0.0; // milliseconds
    private long lastRttMeasurement = 0;
    private double uploadSpeed = 0.0; // bits per second
    private long lastSpeedCalculation = 0;
    private long lastBytesForSpeed = 0;
    
    // Window management tracking
    private int nakCount = 0;  // Track number of NAKs received
    private long lastNakTime = 0;  // Last time we received a NAK
    private int lowestWindow = Integer.MAX_VALUE;  // Track lowest window size reached
    private long lastWindowIncrease = 0;  // Track when we last increased window
    private int consecutiveAcksWithoutNak = 0;  // Track successful ACKs for recovery
    private int nakBurstCount = 0;  // Track NAKs in current burst
    private long nakBurstStartTime = 0;  // When current NAK burst started
    // Performance optimization: pre-calculated thresholds (DIALED DOWN for conservative recovery)
    private static final double NORMAL_UTILIZATION_THRESHOLD = 0.85;  // Was 0.75 - now require higher utilization
    private static final double FAST_UTILIZATION_THRESHOLD = 0.95;     // Was 0.90 - now require very high utilization
    private static final int NORMAL_ACKS_REQUIRED = 4;                 // Was 2 - now require more ACKs
    private static final int FAST_ACKS_REQUIRED = 2;                   // Was 1 - now require more ACKs even in fast mode
    
    // Performance optimization: pre-calculated recovery parameters (DIALED DOWN for slower recovery)
    private static final long NORMAL_MIN_WAIT_TIME = 2000;             // Was 1000ms - now wait longer
    private static final long FAST_MIN_WAIT_TIME = 500;                // Was 200ms - now wait longer
    private static final long NORMAL_INCREMENT_WAIT = 1000;            // Was 500ms - now wait longer between increases
    private static final long FAST_INCREMENT_WAIT = 300;               // Was 100ms - now wait longer
    private static final int NORMAL_MODE_MULTIPLIER = 1;
    private static final int FAST_MODE_MULTIPLIER = 2;
    
    // Performance optimization: cached timestamp for reducing System.currentTimeMillis() calls
    private static final int TIMESTAMP_CACHE_INTERVAL = 10; // Cache for 10ms
    private static volatile long cachedTimestamp = 0;
    private static volatile long lastTimestampUpdate = 0;
    
    // Performance optimization: batched statistics updates
    private static final int STATS_UPDATE_INTERVAL = 100; // Update stats every N packets
    private int packetsSinceStatsUpdate = 0;
    
    // Performance optimization: reusable buffer to avoid allocations
    private final ByteBuffer reusableBuffer = ByteBuffer.allocateDirect(2048);
    
    // Fast recovery mode fields  
    private boolean fastRecoveryMode = false;  // Enable ultra-aggressive recovery after severe congestion
    private long fastRecoveryStartTime = 0;  // When fast recovery mode was enabled
    
    // Keepalive-based RTT measurement (Swift SRTLA style)
    private long lastKeepaliveSentTime = 0;
    private boolean waitingForKeepaliveResponse = false;
    private long lastKeepaliveTime = 0; // Track when we last sent a keepalive
    
    public SrtlaConnection(Network network, String serverHost, int serverPort) {
        this(network, serverHost, serverPort, "SRTLA_" + network.toString().hashCode());
    }
    
    public SrtlaConnection(Network network, String serverHost, int serverPort, String networkTypeName) {
        this.network = network;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.networkType = networkTypeName;
        this.lastActivity = System.currentTimeMillis();
        this.stateChangeTime = System.currentTimeMillis();
        this.connectionStartTime = System.currentTimeMillis();
        this.lastSpeedCalculation = System.currentTimeMillis();
        
        // Initialize packet log to -1 (empty slots, matching srtla_send.c)
        Arrays.fill(packetLog, -1);
        
        Log.i(TAG, "Created SRTLA connection for " + networkType + " to " + serverHost + ":" + serverPort);
    }
    
    public boolean connect() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            
            // Bind to specific network if available
            if (network != null) {
                network.bindSocket(channel.socket());
                Log.d(TAG, "Bound " + networkType + " to network " + network);
            }
            
            // Connect to server
            InetSocketAddress serverAddress = new InetSocketAddress(serverHost, serverPort);
            channel.connect(serverAddress);
            
            connected = true;
            lastActivity = System.currentTimeMillis();
            state = ConnectionState.REGISTERING_REG1;
            stateChangeTime = System.currentTimeMillis();
            
            Log.i(TAG, "✓ " + networkType + " connected to " + serverHost + ":" + serverPort);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect " + networkType + " to " + serverHost + ":" + serverPort, e);
            connected = false;
            state = ConnectionState.FAILED;
            return false;
        }
    }
    
    /**
     * Get cached current time to reduce System.currentTimeMillis() overhead
     */
    private static long getCurrentTime() {
        long now = System.currentTimeMillis();
        
        // Cache timestamp for TIMESTAMP_CACHE_INTERVAL milliseconds
        if (now - lastTimestampUpdate >= TIMESTAMP_CACHE_INTERVAL) {
            cachedTimestamp = now;
            lastTimestampUpdate = now;
            return now;
        }
        
        return cachedTimestamp;
    }
    
    /**
     * Register a packet in the tracking log (matches reg_pkt in srtla_send.c)
     */
    public void registerPacket(int sequenceNumber) {
        if (sequenceNumber < 0) return;
        
        packetLog[packetIndex] = sequenceNumber;
        packetIndex = (packetIndex + 1) % SrtlaProtocol.PKT_LOG_SIZE;
        inFlightPackets++;
    }
    
    /**
     * Handle NAK for a specific packet (matches register_nak in srtla_send.c)
     */
    public void handleNak(int sequenceNumber) {
        // Search backwards through packet log with early termination for performance
        int idx = (packetIndex - 1 + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
        int searchLimit = Math.min(SrtlaProtocol.PKT_LOG_SIZE, 64); // Limit search scope for performance
        
        for (int i = 0; i < searchLimit; i++) {
            int currentIdx = (idx - i + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
            
            if (packetLog[currentIdx] == sequenceNumber) {
                packetLog[currentIdx] = -1; // Mark as processed
                
                // Track NAK statistics and bursts
                nakCount++;
                long currentTime = getCurrentTime();
                
                // Detect NAK bursts (multiple NAKs within 1 second)
                if (currentTime - lastNakTime < 1000) {
                    if (nakBurstCount == 0) {
                        nakBurstStartTime = lastNakTime;  // Start of burst
                    }
                    nakBurstCount++;
                } else {
                    // End of burst - log if it was significant
                    if (nakBurstCount >= 3) {
                        SrtlaLogger.warn(TAG, networkType + ": NAK burst ended - " + nakBurstCount + 
                                        " NAKs in " + (lastNakTime - nakBurstStartTime) + "ms");
                    }
                    nakBurstCount = 1;  // Start new burst count
                }
                
                lastNakTime = currentTime;
                consecutiveAcksWithoutNak = 0;  // Reset ACK counter on NAK
                
                // Decrease window (congestion response)
                int oldWindow = window;
                window -= SrtlaProtocol.WINDOW_DECR;
                window = Math.max(window, SrtlaProtocol.WINDOW_MIN * SrtlaProtocol.WINDOW_MULT);
                
                // Track lowest window and enable fast recovery if window gets very small
                lowestWindow = Math.min(lowestWindow, window);
                
                // Enable fast recovery mode if window drops below 2000 (more conservative threshold)
                if (window <= 2000 && !fastRecoveryMode) {
                    fastRecoveryMode = true;
                    fastRecoveryStartTime = currentTime;
                    SrtlaLogger.warn(TAG, networkType + ": Enabling FAST RECOVERY MODE - window critically low at " + window);
                }
                
                // Log window reduction for diagnosis (include burst info)
                if (window <= 3000) {  // Only log when window gets small
                    String burstInfo = nakBurstCount > 1 ? " [BURST: " + nakBurstCount + " NAKs]" : "";
                    SrtlaLogger.warn(TAG, networkType + ": NAK reduced window " + oldWindow + " → " + window + 
                                    " (seq=" + sequenceNumber + ", in_flight=" + inFlightPackets + 
                                    ", total_naks=" + nakCount + burstInfo + ")");
                }
                
                return;
            }
        }
    }
    
    /**
     * Handle SRTLA ACK (matches register_srtla_ack in srtla_send.c)
     */
    public void handleSrtlaAck(int ackSequenceNumber) {
        // Search for the acknowledged packet with performance optimization
        int idx = (packetIndex - 1 + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
        int searchLimit = Math.min(SrtlaProtocol.PKT_LOG_SIZE, 64); // Limit search scope for performance
        
        for (int i = 0; i < searchLimit; i++) {
            int currentIdx = (idx - i + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
            
            if (packetLog[currentIdx] == ackSequenceNumber) {
                if (inFlightPackets > 0) {
                    inFlightPackets--;
                }
                packetLog[currentIdx] = -1; // Mark as processed
                
                // Increase window with proper congestion control
                int oldWindow = window;
                boolean windowIncreased = false;
                long currentTime = getCurrentTime();
                
                // Only increase window if we haven't increased recently (slower recovery)
                if (currentTime - lastWindowIncrease > 200) {  // Increased from 50ms to 200ms for slower recovery
                    
                    // More aggressive recovery with fast recovery mode support (using cached values)
                    double utilizationThreshold = fastRecoveryMode ? FAST_UTILIZATION_THRESHOLD : NORMAL_UTILIZATION_THRESHOLD;
                    if (inFlightPackets < (window * utilizationThreshold / SrtlaProtocol.WINDOW_MULT)) {
                        consecutiveAcksWithoutNak++;
                        
                        // Conservative recovery - require more ACKs and reset counter after increases
                        int acksRequired = fastRecoveryMode ? FAST_ACKS_REQUIRED : NORMAL_ACKS_REQUIRED;
                        if (consecutiveAcksWithoutNak >= acksRequired) {
                            window += SrtlaProtocol.WINDOW_INCR;
                            window = Math.min(window, SrtlaProtocol.WINDOW_MAX * SrtlaProtocol.WINDOW_MULT);
                            windowIncreased = true;
                            lastWindowIncrease = currentTime;
                            consecutiveAcksWithoutNak = 0; // Reset counter to prevent burst increases
                        }
                    }
                }
                
                // Log window recovery for diagnosis
                if (windowIncreased && oldWindow <= 10000) {
                    SrtlaLogger.info(TAG, networkType + ": ACK increased window " + oldWindow + " → " + window + 
                                    " (in_flight=" + inFlightPackets + ", consec_acks=" + consecutiveAcksWithoutNak + 
                                    ", fast_mode=" + fastRecoveryMode + ")");
                }
                
                // Disable fast recovery mode if window has recovered sufficiently (higher threshold)
                if (fastRecoveryMode && window >= 12000) {  // Increased from 10000 to require more recovery
                    fastRecoveryMode = false;
                    long recoveryDuration = currentTime - fastRecoveryStartTime;
                    SrtlaLogger.warn(TAG, networkType + ": Disabling FAST RECOVERY MODE - window recovered to " + window + 
                                    " after " + recoveryDuration + "ms");
                }
                
                break;
            }
        }
    }
    
    /**
     * Handle SRT ACK to update in-flight packet count (matches conn_register_srt_ack)
     */
    public void handleSrtAck(int ackSequenceNumber) {
        int count = 0;
        int idx = (packetIndex - 1 + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
        int searchLimit = Math.min(SrtlaProtocol.PKT_LOG_SIZE, 128); // Limit search for performance
        
        // Count packets with sequence numbers >= ack
        for (int i = 0; i < searchLimit; i++) {
            int currentIdx = (idx - i + SrtlaProtocol.PKT_LOG_SIZE) % SrtlaProtocol.PKT_LOG_SIZE;
            
            if (packetLog[currentIdx] < ackSequenceNumber) {
                packetLog[currentIdx] = -1; // Mark as acknowledged
            } else if (packetLog[currentIdx] > 0) {
                count++;
            }
        }
        
        inFlightPackets = count;
    }
    
    /**
     * Calculate connection score for selection (matches select_conn logic)
     */
    public int getScore() {
        if (!isConnected() || isTimedOut()) {
            return -1;
        }
        
        return window / (inFlightPackets + 1);
    }
    
    /**
     * Send data with SRTLA packet registration
     */
    public boolean sendDataWithTracking(ByteBuffer data, int sequenceNumber) {
        if (!connected || channel == null || data == null || !data.hasRemaining()) {
            return false;
        }
        
        try {
            int bytesToWrite = data.remaining();
            int bytesWritten = channel.write(data);
            
            if (bytesWritten == bytesToWrite) {
                long currentTime = getCurrentTime();
                lastSent = currentTime;
                lastActivity = currentTime;
                
                // Batch statistics updates for performance
                bytesUploaded += bytesWritten;
                packetsUploaded++;
                packetsSinceStatsUpdate++;
                
                if (packetsSinceStatsUpdate >= STATS_UPDATE_INTERVAL) {
                    updateUploadSpeed();
                    packetsSinceStatsUpdate = 0;
                }
                
                // Register packet for tracking if it has a sequence number
                if (sequenceNumber >= 0) {
                    registerPacket(sequenceNumber);
                }
                
                SrtlaLogger.packet(TAG, "Sent", networkType, bytesWritten);
                if (sequenceNumber >= 0) {
                    SrtlaLogger.trace(TAG, networkType + " packet seq: " + sequenceNumber);
                }
                return true;
            } else {
                SrtlaLogger.warn(TAG, "Partial write: " + bytesWritten + "/" + bytesToWrite + " bytes via " + networkType);
                return false;
            }
            
        } catch (IOException e) {
            SrtlaLogger.error(TAG, "Error sending data via " + networkType, e);
            connected = false;
            state = ConnectionState.FAILED;
            return false;
        }
    }
    
    /**
     * Send SRTLA protocol packet
     */
    public boolean sendSrtlaPacket(byte[] packet) {
        if (!connected || channel == null) {
            return false;
        }
        
        try {
            // Reuse buffer to avoid allocations
            reusableBuffer.clear();
            if (packet.length <= reusableBuffer.capacity()) {
                reusableBuffer.put(packet);
                reusableBuffer.flip();
                int bytesWritten = channel.write(reusableBuffer);
                
                if (bytesWritten == packet.length) {
                    long currentTime = getCurrentTime();
                    lastSent = currentTime;
                    lastActivity = currentTime;
                    
                    int packetType = SrtlaProtocol.getPacketType(packet, packet.length);
                    SrtlaLogger.packet(TAG, "Sent SRTLA packet type 0x" + Integer.toHexString(packetType), 
                                     networkType, packet.length);
                    return true;
                }
            } else {
                // Fallback for large packets
                ByteBuffer buffer = ByteBuffer.wrap(packet);
                int bytesWritten = channel.write(buffer);
                
                if (bytesWritten == packet.length) {
                    long currentTime = getCurrentTime();
                    lastSent = currentTime;
                    lastActivity = currentTime;
                    
                    int packetType = SrtlaProtocol.getPacketType(packet, packet.length);
                    SrtlaLogger.packet(TAG, "Sent SRTLA packet type 0x" + Integer.toHexString(packetType), 
                                     networkType, packet.length);
                    return true;
                }
            }
            
        } catch (IOException e) {
            SrtlaLogger.error(TAG, "Error sending SRTLA packet via " + networkType, e);
            connected = false;
            state = ConnectionState.FAILED;
        }
        
        return false;
    }
    
    /**
     * Receive data from SRTLA connection (matches srtla_send.c recvfrom)
     */
    public int receive(ByteBuffer buffer) throws IOException {
        if (!connected || channel == null) {
            return 0;
        }
        
        try {
            int bytesRead = channel.read(buffer);
            if (bytesRead > 0) {
                lastReceived = System.currentTimeMillis();
                lastActivity = lastReceived;
                SrtlaLogger.packet(TAG, "Received", networkType, bytesRead);
            }
            return bytesRead;
            
        } catch (IOException e) {
            SrtlaLogger.error(TAG, "Error receiving from " + networkType, e);
            connected = false;
            state = ConnectionState.FAILED;
            throw e;
        }
    }
    
    /**
     * Perform time-based window recovery when no recent packet loss
     * Should be called periodically (e.g., during housekeeping)
     */
    public void performWindowRecovery() {
        if (!connected || window >= SrtlaProtocol.WINDOW_MAX * SrtlaProtocol.WINDOW_MULT) {
            return;  // No recovery needed
        }
        
        long currentTime = getCurrentTime();
        long timeSinceLastNak = lastNakTime > 0 ? (currentTime - lastNakTime) : Long.MAX_VALUE;
        
        // Conservative recovery with longer wait times (using cached values)
        long minWaitTime = fastRecoveryMode ? FAST_MIN_WAIT_TIME : NORMAL_MIN_WAIT_TIME;
        long incrementWaitTime = fastRecoveryMode ? FAST_INCREMENT_WAIT : NORMAL_INCREMENT_WAIT;
        
        if (timeSinceLastNak > minWaitTime && currentTime - lastWindowIncrease > incrementWaitTime) {
            int oldWindow = window;
            
            // Conservative recovery multipliers (using cached values)
            int fastModeBonus = fastRecoveryMode ? FAST_MODE_MULTIPLIER : NORMAL_MODE_MULTIPLIER;
            
            // More conservative recovery based on how long since last NAK
            if (timeSinceLastNak > 10000) {
                // No NAKs for 10+ seconds: moderate recovery (was 5s)
                window += SrtlaProtocol.WINDOW_INCR * 2 * fastModeBonus;  // Was 5x
            } else if (timeSinceLastNak > 7000) {
                // No NAKs for 7+ seconds: slow recovery (was 3s)
                window += SrtlaProtocol.WINDOW_INCR * 1 * fastModeBonus;  // Was 3x
            } else if (timeSinceLastNak > 5000) {
                // No NAKs for 5+ seconds: very slow recovery (was 1.5s)
                window += SrtlaProtocol.WINDOW_INCR * 1 * fastModeBonus;  // Was 2x
            } else {
                // Recent NAKs: minimal recovery (keep same as before)
                window += SrtlaProtocol.WINDOW_INCR * fastModeBonus;
            }
            window = Math.min(window, SrtlaProtocol.WINDOW_MAX * SrtlaProtocol.WINDOW_MULT);
            lastWindowIncrease = currentTime;
            
            if (window > oldWindow) {
                SrtlaLogger.info(TAG, networkType + ": Time-based window recovery " + oldWindow + " → " + window + 
                                " (no NAKs for " + (timeSinceLastNak / 1000.0) + "s, fast_mode=" + fastRecoveryMode + ")");
            }
        }
    }
    
    public boolean isTimedOut() {
        long currentTime = getCurrentTime();
        return connected && (lastReceived + (SrtlaProtocol.CONN_TIMEOUT * 1000)) < currentTime;
    }
    
    public boolean needsKeepalive() {
        long currentTime = getCurrentTime();
        // Send keepalives every IDLE_TIME seconds regardless of data transmission
        return connected && (lastKeepaliveTime == 0 || (currentTime - lastKeepaliveTime) >= (SrtlaProtocol.IDLE_TIME * 1000));
    }
    
    /**
     * Check if we need to send a keepalive for RTT measurement specifically
     */
    public boolean needsRttMeasurement() {
        long currentTime = getCurrentTime();
        // Send RTT measurement keepalives every 3 seconds if we haven't measured RTT recently
        return connected && !waitingForKeepaliveResponse && 
               (lastRttMeasurement == 0 || (currentTime - lastRttMeasurement) > 3000);
    }
    
    public void markReceived() {
        long currentTime = getCurrentTime();
        lastReceived = currentTime;
        lastActivity = currentTime;
    }
    
    public void resetConnection() {
        SrtlaLogger.dev(TAG, networkType + ": resetting connection state");
        
        // Use cached time for efficient reset
        long currentTime = getCurrentTime();
        
        lastReceived = 0;
        lastSent = 0;
        lastKeepaliveTime = 0;
        lastRttMeasurement = 0;
        waitingForKeepaliveResponse = false;
        window = SrtlaProtocol.WINDOW_MIN * SrtlaProtocol.WINDOW_MULT;
        inFlightPackets = 0;
        Arrays.fill(packetLog, -1);
        state = ConnectionState.REGISTERING_REG1;
        stateChangeTime = currentTime;
    }
    
    public void close() {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing channel for " + networkType, e);
            }
        }
        connected = false;
        state = ConnectionState.DISCONNECTED;
    }
    
    // Getters
    public Network getNetwork() { return network; }
    public String getNetworkType() { return networkType; }
    public boolean isConnected() { return connected && state != ConnectionState.FAILED; }
    public boolean isRemoved() { return removed; }
    public void setRemoved(boolean removed) { this.removed = removed; }
    public ConnectionState getState() { return state; }
    public void setState(ConnectionState state) { 
        this.state = state; 
        this.stateChangeTime = System.currentTimeMillis();
    }
    public long getStateChangeTime() { return stateChangeTime; }
    public int getWindow() { return window; }
    public int getInFlightPackets() { return inFlightPackets; }
    public long getLastReceived() { return lastReceived; }
    public long getLastSent() { return lastSent; }
    public long getLastRttMeasurement() { return lastRttMeasurement; }
    public long getLastKeepaliveTime() { return lastKeepaliveTime; }
    public long getConnectionStartTime() { return connectionStartTime; }
    public DatagramChannel getChannel() { return channel; }
    
    // NAK tracking getters
    public int getNakCount() { return nakCount; }
    public int getNakBurstCount() { return nakBurstCount; }
    public long getTimeSinceLastNak() { 
        return lastNakTime > 0 ? System.currentTimeMillis() - lastNakTime : Long.MAX_VALUE; 
    }
    
    // Statistics methods
    private void updateUploadSpeed() {
        long currentTime = getCurrentTime();
        long timeDiff = currentTime - lastSpeedCalculation;
        
        // Reduce calculation frequency for better performance
        if (timeDiff >= 2000) { // Update every 2 seconds instead of 1
            long bytesDiff = bytesUploaded - lastBytesForSpeed;
            
            if (timeDiff > 0) {
                uploadSpeed = (bytesDiff * 8 * 1000.0) / timeDiff; // bits per second (bytes * 8 bits/byte)
                
                // Only log in debug mode to reduce overhead
                SrtlaLogger.debug(TAG, networkType + " speed update: " + bytesDiff + " bytes in " + timeDiff + 
                          "ms = " + String.format("%.1f", uploadSpeed) + " bps");
            }
            
            lastSpeedCalculation = currentTime;
            lastBytesForSpeed = bytesUploaded;
        }
    }
    
    public void updateEstimatedRtt(long rttMs) {
        if (estimatedRtt == 0.0) {
            estimatedRtt = rttMs;
        } else {
            // Exponential smoothing (87.5% old, 12.5% new)
            estimatedRtt = (estimatedRtt * 0.875) + (rttMs * 0.125);
        }
        lastRttMeasurement = System.currentTimeMillis();
    }
    
    /**
     * Record keepalive sent timestamp for RTT measurement (Swift SRTLA style)
     */
    public void recordKeepaliveSent() {
        lastKeepaliveSentTime = System.currentTimeMillis();
        lastKeepaliveTime = lastKeepaliveSentTime; // Track when we sent keepalive
        waitingForKeepaliveResponse = true;
        Log.d(TAG, networkType + ": keepalive sent at " + lastKeepaliveSentTime + ", waiting for response");
    }
    
    /**
     * Handle keepalive response and calculate RTT (Swift SRTLA style)
     */
    public void handleKeepaliveResponse(byte[] data, int length) {
        SrtlaLogger.debug(TAG, networkType + ": received keepalive response, length=" + length + 
                         ", waitingForResponse=" + waitingForKeepaliveResponse);
        
        if (!waitingForKeepaliveResponse) {
            Log.w(TAG, networkType + ": received keepalive response but not waiting for one");
            return;
        }
        
        // Extract timestamp from keepalive response
        long sendTimestamp = SrtlaProtocol.extractKeepaliveTimestamp(data, length);
        Log.d(TAG, networkType + ": extracted timestamp from keepalive: " + sendTimestamp);
        
        if (sendTimestamp > 0) {
            long currentTime = System.currentTimeMillis();
            long rtt = currentTime - sendTimestamp;
            
            Log.d(TAG, networkType + ": RTT calculation: current=" + currentTime + 
                  ", sent=" + sendTimestamp + ", rtt=" + rtt + "ms");
            
            // Clamp RTT to reasonable range (like Swift SRTLA: 0-10000ms)
            if (rtt >= 0 && rtt <= 10000) {
                updateEstimatedRtt(rtt);
                Log.i(TAG, networkType + ": ✅ RTT measured via keepalive: " + rtt + "ms (avg: " + 
                      String.format("%.1f", estimatedRtt) + "ms)");
            } else {
                Log.w(TAG, networkType + ": Invalid RTT measurement: " + rtt + "ms");
            }
        } else {
            Log.w(TAG, networkType + ": Failed to extract timestamp from keepalive response");
        }
        
        waitingForKeepaliveResponse = false;
    }
    
    // Statistics getters
    public long getBytesUploaded() { return bytesUploaded; }
    public long getPacketsUploaded() { return packetsUploaded; }
    public double getUploadSpeed() { return uploadSpeed; }
    public double getEstimatedRtt() { return estimatedRtt; }
    public long getConnectionDuration() { 
        return connected ? (System.currentTimeMillis() - connectionStartTime) : 0; 
    }
    
    public String getFormattedStats() {
        long duration = getConnectionDuration() / 1000; // seconds
        double speedKbps = uploadSpeed / 1000.0; // Kbps (bits per second / 1000)
        double totalMB = bytesUploaded / (1024.0 * 1024.0); // MB
        
        // Use more precision for speed display and handle very small speeds
        String speedDisplay;
        if (speedKbps >= 1000.0) {
            // Show in Mbps for high speeds
            speedDisplay = String.format("%.1f Mbps", speedKbps / 1000.0);
        } else if (speedKbps >= 1.0) {
            speedDisplay = String.format("%.1f Kbps", speedKbps);
        } else if (speedKbps >= 0.1) {
            speedDisplay = String.format("%.2f Kbps", speedKbps);
        } else if (uploadSpeed > 0) {
            // Show in bps for very small speeds
            speedDisplay = String.format("%.0f bps", uploadSpeed);
        } else {
            speedDisplay = "0 bps";
        }
        
        // Window health indicator
        String windowHealth;
        if (window >= 30000) {
            windowHealth = "🟢 Excellent";
        } else if (window >= 15000) {
            windowHealth = "🟡 Good";
        } else if (window >= 5000) {
            windowHealth = "🟠 Fair";
        } else if (window >= 2000) {
            windowHealth = "🔴 Poor";
        } else {
            windowHealth = "❌ Critical";
        }
        
        // NAK rate information
        String nakInfo = "";
        if (nakCount > 0) {
            long timeSinceLastNak = System.currentTimeMillis() - lastNakTime;
            String burstInfo = nakBurstCount > 1 ? " (burst: " + nakBurstCount + ")" : "";
            nakInfo = String.format(" | ❌ NAKs: %d%s (last %ds ago)", nakCount, burstInfo, timeSinceLastNak / 1000);
        }
        
        return String.format("%s:\n  📈 %s | 📦 %.2f MB | ⏱️ %.0f ms RTT\n  🔗 %d packets | ⏰ %02d:%02d uptime\n  🪟 Window: %d (%s) | 🔄 In-flight: %d%s",
            networkType, speedDisplay, totalMB, estimatedRtt, 
            packetsUploaded, duration / 60, duration % 60, window, windowHealth, inFlightPackets, nakInfo);
    }
    
    @Override
    public String toString() {
        return networkType + " [state=" + state + ", window=" + window + 
               ", in_flight=" + inFlightPackets + ", connected=" + connected + "]";
    }
}
