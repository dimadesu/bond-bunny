package com.example.srtla;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * Smart reconnection manager inspired by moblink's reconnection pattern
 * Handles connection failures with proper backoff and reason tracking
 */
public class SrtlaReconnectionManager {
    private static final String TAG = "SrtlaReconnectionManager";
    
    private final HandlerThread handlerThread;
    private final Handler handler;
    private final Map<String, Runnable> pendingReconnects = new HashMap<>();
    private final Map<String, Long> lastReconnectTime = new HashMap<>();
    private final Map<String, Integer> backoffCount = new HashMap<>();
    
    // Reconnection intervals (inspired by moblink's approach)
    private static final long BASE_RECONNECT_DELAY_MS = 5000;  // 5 seconds
    private static final long MAX_BACKOFF_DELAY_MS = 120000;   // 2 minutes max
    private static final int MAX_BACKOFF_COUNT = 5;
    
    public interface ReconnectionCallback {
        void onReconnectAttempt(String networkType, String reason);
        boolean attemptReconnection(String networkType);
        void onReconnectResult(String networkType, boolean success);
    }
    
    private final ReconnectionCallback callback;
    
    public SrtlaReconnectionManager(ReconnectionCallback callback) {
        this.callback = callback;
        this.handlerThread = new HandlerThread("SrtlaReconnection");
        this.handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
    }
    
    /**
     * Schedule a reconnection attempt with smart backoff (moblink-style)
     */
    public void scheduleReconnection(String networkType, String reason) {
        handler.post(() -> {
            // Cancel any pending reconnection for this network type
            Runnable existingReconnect = pendingReconnects.remove(networkType);
            if (existingReconnect != null) {
                handler.removeCallbacks(existingReconnect);
                SrtlaLogger.debug(TAG, "Cancelled pending reconnect for " + networkType);
            }
            
            // Calculate backoff delay
            int currentBackoffCount = backoffCount.getOrDefault(networkType, 0);
            long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << currentBackoffCount), MAX_BACKOFF_DELAY_MS);
            
            SrtlaLogger.info(TAG, String.format("Scheduling %s reconnect in %dms (reason: %s, backoff: %d)", 
                    networkType, delay, reason, currentBackoffCount));
            
            // Create reconnection task
            Runnable reconnectTask = () -> {
                pendingReconnects.remove(networkType);
                attemptReconnectionInternal(networkType, reason);
            };
            
            pendingReconnects.put(networkType, reconnectTask);
            handler.postDelayed(reconnectTask, delay);
        });
    }
    
    /**
     * Cancel pending reconnection for a network type
     */
    public void cancelReconnection(String networkType) {
        handler.post(() -> {
            Runnable reconnectTask = pendingReconnects.remove(networkType);
            if (reconnectTask != null) {
                handler.removeCallbacks(reconnectTask);
                SrtlaLogger.debug(TAG, "Cancelled reconnect for " + networkType);
            }
        });
    }
    
    /**
     * Mark a network as successfully connected (resets backoff)
     */
    public void markSuccessfulConnection(String networkType) {
        handler.post(() -> {
            backoffCount.remove(networkType);
            lastReconnectTime.put(networkType, System.currentTimeMillis());
            SrtlaLogger.info(TAG, "Reset backoff for " + networkType + " after successful connection");
        });
    }
    
    /**
     * Check if we should attempt reconnection (respects minimum intervals)
     */
    public boolean shouldAttemptReconnection(String networkType) {
        Long lastAttempt = lastReconnectTime.get(networkType);
        if (lastAttempt == null) {
            return true;
        }
        
        long timeSinceLastAttempt = System.currentTimeMillis() - lastAttempt;
        int currentBackoffCount = backoffCount.getOrDefault(networkType, 0);
        long minInterval = BASE_RECONNECT_DELAY_MS * (1L << Math.min(currentBackoffCount, MAX_BACKOFF_COUNT));
        
        return timeSinceLastAttempt >= minInterval;
    }
    
    private void attemptReconnectionInternal(String networkType, String reason) {
        SrtlaLogger.info(TAG, "Attempting reconnection for " + networkType + " (reason: " + reason + ")");
        
        // Update last attempt time
        lastReconnectTime.put(networkType, System.currentTimeMillis());
        
        // Notify callback about attempt
        callback.onReconnectAttempt(networkType, reason);
        
        // Attempt reconnection
        boolean success = callback.attemptReconnection(networkType);
        
        // Update backoff count
        if (success) {
            backoffCount.remove(networkType);
            SrtlaLogger.info(TAG, "Successfully reconnected " + networkType);
        } else {
            int currentCount = backoffCount.getOrDefault(networkType, 0);
            backoffCount.put(networkType, Math.min(currentCount + 1, MAX_BACKOFF_COUNT));
            SrtlaLogger.warn(TAG, "Failed to reconnect " + networkType + ", backoff count: " + (currentCount + 1));
        }
        
        // Notify callback about result
        callback.onReconnectResult(networkType, success);
    }
    
    /**
     * Get reconnection statistics
     */
    public String getReconnectionStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== Reconnection Statistics ===\n");
        stats.append("Pending reconnects: ").append(pendingReconnects.size()).append("\n");
        
        for (Map.Entry<String, Integer> entry : backoffCount.entrySet()) {
            String networkType = entry.getKey();
            int count = entry.getValue();
            Long lastTime = lastReconnectTime.get(networkType);
            
            stats.append(networkType).append(": backoff=").append(count);
            if (lastTime != null) {
                long elapsed = (System.currentTimeMillis() - lastTime) / 1000;
                stats.append(", last_attempt=").append(elapsed).append("s ago");
            }
            stats.append("\n");
        }
        
        return stats.toString();
    }
    
    /**
     * Shutdown the reconnection manager
     */
    public void shutdown() {
        handler.post(() -> {
            // Cancel all pending reconnections
            for (Runnable reconnectTask : pendingReconnects.values()) {
                handler.removeCallbacks(reconnectTask);
            }
            pendingReconnects.clear();
        });
        
        handlerThread.quitSafely();
    }
}
