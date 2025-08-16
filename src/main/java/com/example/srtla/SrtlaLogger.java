package com.example.srtla;

import android.util.Log;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Enhanced logging utility for SRTLA operations with persistent log buffer.
 * Inspired by moblink's Logger with debugging capabilities for NAK pattern analysis.
 */
public class SrtlaLogger {
    
    // Moblink-inspired persistent logging
    private static final int MAX_LOG_ENTRIES = 2000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ArrayDeque<String> logBuffer = new ArrayDeque<>();
    private static final ReentrantReadWriteLock bufferLock = new ReentrantReadWriteLock();
    
    // Connection switching tracking for NAK analysis
    private static String lastSelectedConnection = "";
    private static int connectionSwitchCount = 0;
    private static long lastSwitchTime = 0;
    
    /**
     * Logging performance levels
     */
    public enum LogLevel {
        PRODUCTION,    // Minimal logging - errors and critical info only
        DEVELOPMENT,   // Moderate logging - connection events and warnings  
        DEBUG,         // Verbose logging - packet-level details
        TRACE          // Maximum logging - every operation
    }
    
    // Current logging level - can be changed at runtime
    private static LogLevel currentLevel = LogLevel.DEVELOPMENT;  // Default level for balanced performance
    
    // Thread-safe performance counters using atomic operations
    private static final java.util.concurrent.atomic.AtomicLong totalPacketsLogged = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong logCallsSkipped = new java.util.concurrent.atomic.AtomicLong(0);
    
    /**
     * Set the global logging level
     */
    public static void setLogLevel(LogLevel level) {
        currentLevel = level;
        Log.i("SrtlaLogger", "Logging level changed to: " + level);
    }
    
    public static LogLevel getLogLevel() {
        return currentLevel;
    }
    
    /**
     * Production-level logging (always enabled)
     */
    public static void error(String tag, String message) {
        Log.e(tag, message);
        addToBuffer("ERROR", tag, message);
    }
    
    public static void error(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        addToBuffer("ERROR", tag, message + " - " + throwable.getMessage());
    }
    
    public static void warn(String tag, String message) {
        Log.w(tag, message);
        addToBuffer("WARN", tag, message);
    }
    
    /**
     * Development-level logging
     */
    public static void info(String tag, String message) {
        if (currentLevel.ordinal() >= LogLevel.DEVELOPMENT.ordinal()) {
            Log.i(tag, message);
            addToBuffer("INFO", tag, message);
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Log connection selection for NAK pattern analysis (Moblink-inspired)
     */
    public static void logConnectionSelection(String connectionType, String reason) {
        long currentTime = System.currentTimeMillis();
        
        if (!connectionType.equals(lastSelectedConnection)) {
            connectionSwitchCount++;
            long switchInterval = lastSwitchTime > 0 ? currentTime - lastSwitchTime : 0;
            
            String switchMessage = String.format("Connection switch #%d: %s -> %s (%s) [interval: %dms]", 
                    connectionSwitchCount, lastSelectedConnection, connectionType, reason, switchInterval);
            
            info("ConnectionSwitch", switchMessage);
            
            lastSelectedConnection = connectionType;
            lastSwitchTime = currentTime;
        }
    }
    
    /**
     * Log NAK events for pattern analysis
     */
    public static void logNak(String connectionType, int[] nakSequences, int windowSize, int inFlight) {
        String nakMessage = String.format("NAK on %s: seqs=%s, window=%d, inFlight=%d", 
                connectionType, 
                java.util.Arrays.toString(nakSequences),
                windowSize,
                inFlight);
        
        warn("NAK", nakMessage);
    }
    
    /**
     * Log window changes for debugging
     */
    public static void logWindowChange(String connectionType, int oldWindow, int newWindow, String reason) {
        if (currentLevel.ordinal() >= LogLevel.DEBUG.ordinal()) {
            String windowMessage = String.format("%s window: %d -> %d (%s)", 
                    connectionType, oldWindow, newWindow, reason);
            
            debug("Window", windowMessage);
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Log reconnection attempts with reason (Moblink-inspired)
     */
    public static void logReconnection(String connectionType, String reason, boolean success) {
        String reconnectMessage = String.format("Reconnect %s: %s - %s", 
                connectionType, reason, success ? "SUCCESS" : "FAILED");
        
        info("Reconnect", reconnectMessage);
    }
    
    private static void addToBuffer(String level, String tag, String message) {
        bufferLock.writeLock().lock();
        try {
            if (logBuffer.size() >= MAX_LOG_ENTRIES) {
                logBuffer.removeFirst();
            }
            
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String logEntry = String.format("%s [%s][%s] %s", timestamp, level, tag, message);
            logBuffer.addLast(logEntry);
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Development-level logging (connection events, state changes)
     */
    public static void dev(String tag, String message) {
        if (currentLevel.ordinal() >= LogLevel.DEVELOPMENT.ordinal()) {
            Log.d(tag, message);
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Debug-level logging (packet details, window operations)
     */
    public static void debug(String tag, String message) {
        if (currentLevel.ordinal() >= LogLevel.DEBUG.ordinal()) {
            Log.d(tag, message);
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Trace-level logging (every operation, very verbose)
     */
    public static void trace(String tag, String message) {
        if (currentLevel.ordinal() >= LogLevel.TRACE.ordinal()) {
            Log.v(tag, message);
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Performance-optimized packet logging with rate limiting
     */
    public static void packet(String tag, String operation, String networkType, int bytes) {
        if (currentLevel.ordinal() >= LogLevel.DEBUG.ordinal()) {
            long packetCount = totalPacketsLogged.incrementAndGet();
            
            // Rate limiting: only log every Nth packet in high-volume scenarios
            if (shouldLogPacket(packetCount)) {
                Log.v(tag, operation + " " + bytes + " bytes via " + networkType + 
                      " (total: " + packetCount + ")");
            }
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Rate limiting logic for packet logging (thread-safe)
     */
    private static boolean shouldLogPacket(long packetCount) {
        // In high-volume scenarios, only log every 100th packet
        if (packetCount % 100 == 0) {
            return true;
        }
        
        // Always log first few packets for debugging
        return packetCount <= 10;
    }
    
    /**
     * Conditional logging with lambda for expensive string operations
     */
    public static void debugLazy(String tag, java.util.function.Supplier<String> messageSupplier) {
        if (currentLevel.ordinal() >= LogLevel.DEBUG.ordinal()) {
            Log.d(tag, messageSupplier.get());
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    public static void traceLazy(String tag, java.util.function.Supplier<String> messageSupplier) {
        if (currentLevel.ordinal() >= LogLevel.TRACE.ordinal()) {
            Log.v(tag, messageSupplier.get());
        } else {
            logCallsSkipped.incrementAndGet();
        }
    }
    
    /**
     * Get performance statistics (thread-safe)
     */
    public static String getPerformanceStats() {
        long packetsLogged = totalPacketsLogged.get();
        long callsSkipped = logCallsSkipped.get();
        long totalCalls = packetsLogged + callsSkipped;
        
        if (totalCalls == 0) {
            return "No logging activity";
        }
        
        double skipPercentage = (callsSkipped * 100.0) / totalCalls;
        return String.format("Logged: %d packets, Skipped: %d calls (%.1f%% efficiency)", 
                           packetsLogged, callsSkipped, skipPercentage);
    }
    
    /**
     * Reset performance counters (thread-safe)
     */
    public static void resetStats() {
        totalPacketsLogged.set(0);
        logCallsSkipped.set(0);
    }
    
    /**
     * Get formatted log for debugging (Moblink-inspired)
     */
    public static String getFormattedLog() {
        bufferLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== SRTLA Debug Log (").append(logBuffer.size()).append(" entries) ===\n");
            sb.append("Connection switches: ").append(connectionSwitchCount).append("\n");
            sb.append("Last selected: ").append(lastSelectedConnection).append("\n\n");
            
            for (String entry : logBuffer) {
                sb.append(entry).append("\n");
            }
            
            return sb.toString();
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get recent NAK events for analysis
     */
    public static String getNakAnalysis() {
        bufferLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== NAK Pattern Analysis ===\n");
            
            int nakCount = 0;
            int switchCount = 0;
            
            for (String entry : logBuffer) {
                if (entry.contains("[NAK]")) {
                    nakCount++;
                    sb.append(entry).append("\n");
                } else if (entry.contains("[ConnectionSwitch]")) {
                    switchCount++;
                    sb.append(entry).append("\n");
                }
            }
            
            sb.insert(0, String.format("NAK events: %d, Connection switches: %d\n\n", nakCount, switchCount));
            
            return sb.toString();
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Clear log buffer
     */
    public static void clearLog() {
        bufferLock.writeLock().lock();
        try {
            logBuffer.clear();
            connectionSwitchCount = 0;
            lastSelectedConnection = "";
            lastSwitchTime = 0;
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
}
