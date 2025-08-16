package com.example.srtla;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * SRTLA Registration Manager
 * Handles the 3-step SRTLA registration process (REG1, REG2, REG3)
 * Based on the registration logic in srtla_send.c
 */
public class SrtlaRegistrationManager {
    private static final String TAG = "SrtlaRegistration";
    
    public interface RegistrationCallback {
        void broadcastReg2ToAllConnections(byte[] srtlaId);
    }
    
    private byte[] srtlaId = new byte[SrtlaProtocol.SRTLA_ID_LEN];
    private SrtlaConnection pendingReg2Connection = null;
    private long pendingRegTimeout = 0;
    private int activeConnections = 0;
    private boolean hasConnected = false;
    private RegistrationCallback callback;
    
    public SrtlaRegistrationManager() {
        // Generate random SRTLA ID for this session (matches srtla_send.c)
        SecureRandom random = new SecureRandom();
        random.nextBytes(srtlaId);
        
        Log.i(TAG, "Generated SRTLA ID: " + bytesToHex(srtlaId));
    }
    
    public void setCallback(RegistrationCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Process incoming SRTLA packet and handle registration
     * Returns true if packet was consumed by registration process
     */
    public boolean processRegistrationPacket(SrtlaConnection connection, byte[] buffer, int length) {
        if (length < 2) return false;
        
        int packetType = SrtlaProtocol.getPacketType(buffer, length);
        long currentTime = System.currentTimeMillis() / 1000;
        
        switch (packetType) {
            case SrtlaProtocol.SRTLA_TYPE_REG_NGP:
                return handleRegNgp(connection, currentTime);
                
            case SrtlaProtocol.SRTLA_TYPE_REG2:
                return handleReg2(connection, buffer, length, currentTime);
                
            case SrtlaProtocol.SRTLA_TYPE_REG3:
                return handleReg3(connection);
                
            case SrtlaProtocol.SRTLA_TYPE_REG_ERR:
                return handleRegError(connection);
                
            default:
                return false; // Not a registration packet
        }
    }
    
    /**
     * Handle REG_NGP (Next Generation Protocol) packet
     * This initiates the registration process
     */
    private boolean handleRegNgp(SrtlaConnection connection, long currentTime) {
        // Only process NGPs if we meet the conditions from srtla_send.c:
        // - No established connections
        // - No pending REG1->REG2 exchange
        // - No pending REG2->REG3 exchanges
        
        if (activeConnections == 0 && pendingReg2Connection == null && currentTime > pendingRegTimeout) {
            if (sendReg1(connection)) {
                pendingReg2Connection = connection;
                pendingRegTimeout = currentTime + SrtlaProtocol.REG2_TIMEOUT;
                
                Log.i(TAG, "Sent REG1 to " + connection.getNetworkType() + " in response to NGP");
            }
        } else {
            Log.v(TAG, "Ignoring NGP from " + connection.getNetworkType() + 
                  " (active=" + activeConnections + ", pending=" + (pendingReg2Connection != null) + ")");
        }
        
        return true; // Consumed
    }
    
    /**
     * Handle REG2 packet - group registration response
     */
    private boolean handleReg2(SrtlaConnection connection, byte[] buffer, int length, long currentTime) {
        if (length < 2 + SrtlaProtocol.SRTLA_ID_LEN) {
            Log.w(TAG, "REG2 packet too short: " + length + " bytes");
            return true;
        }
        
        if (pendingReg2Connection == connection) {
            // Extract ID from packet (starts at byte 2)
            byte[] receivedId = new byte[SrtlaProtocol.SRTLA_ID_LEN / 2];
            System.arraycopy(buffer, 2, receivedId, 0, receivedId.length);
            
            // Verify ID matches first half of our ID
            byte[] ourIdHalf = new byte[SrtlaProtocol.SRTLA_ID_LEN / 2];
            System.arraycopy(srtlaId, 0, ourIdHalf, 0, ourIdHalf.length);
            
            if (!Arrays.equals(receivedId, ourIdHalf)) {
                Log.e(TAG, "Got mismatching ID in REG2 from " + connection.getNetworkType());
                return true;
            }
            
            Log.i(TAG, "✓ Connection group registered with " + connection.getNetworkType());
            
            // Update our SRTLA ID with the full ID from server
            System.arraycopy(buffer, 2, srtlaId, 0, SrtlaProtocol.SRTLA_ID_LEN);
            
            // Broadcast REG2 to all connections (matches srtla_send.c behavior)
            if (callback != null) {
                callback.broadcastReg2ToAllConnections(srtlaId);
            }
            
            pendingReg2Connection = null;
            pendingRegTimeout = currentTime + SrtlaProtocol.REG3_TIMEOUT;
            
            connection.setState(SrtlaConnection.ConnectionState.REGISTERING_REG2);
        }
        
        return true; // Consumed
    }
    
    /**
     * Handle REG3 packet - connection establishment confirmation
     */
    private boolean handleReg3(SrtlaConnection connection) {
        hasConnected = true;
        activeConnections++;
        
        connection.setState(SrtlaConnection.ConnectionState.CONNECTED);
        
        Log.i(TAG, "✓ Connection established with " + connection.getNetworkType() + 
              " (total active: " + activeConnections + ")");
        
        return true; // Consumed
    }
    
    /**
     * Handle registration error packet from server
     */
    private boolean handleRegError(SrtlaConnection connection) {
        Log.w(TAG, "✗ Registration failed for " + connection.getNetworkType() + 
              " - server rejected connection");
        
        // Reset pending registration state if this was the pending connection
        if (connection == pendingReg2Connection) {
            pendingReg2Connection = null;
            pendingRegTimeout = 0;
        }
        
        // Mark connection as failed
        connection.setState(SrtlaConnection.ConnectionState.FAILED);
        
        return true; // Consumed
    }
    
    /**
     * Send REG1 packet to initiate registration
     */
    public boolean sendReg1(SrtlaConnection connection) {
        byte[] packet = SrtlaProtocol.createReg1Packet(srtlaId);
        
        if (connection.sendSrtlaPacket(packet)) {
            connection.setState(SrtlaConnection.ConnectionState.REGISTERING_REG1);
            Log.d(TAG, "Sent REG1 to " + connection.getNetworkType());
            return true;
        }
        
        return false;
    }
    
    /**
     * Send REG2 packet for group registration
     */
    public boolean sendReg2(SrtlaConnection connection) {
        byte[] packet = SrtlaProtocol.createReg2Packet(srtlaId);
        
        if (connection.sendSrtlaPacket(packet)) {
            Log.d(TAG, "Sent REG2 to " + connection.getNetworkType());
            return true;
        }
        
        return false;
    }
    
    /**
     * Broadcast REG2 to all connections
     */
    public void broadcastReg2(List<SrtlaConnection> connections) {
        Log.i(TAG, "Broadcasting REG2 to " + connections.size() + " connections");
        
        for (SrtlaConnection connection : connections) {
            if (connection.isConnected()) {
                sendReg2(connection);
            }
        }
    }
    
    /**
     * Send keepalive packet
     */
    public boolean sendKeepalive(SrtlaConnection connection) {
        byte[] packet = SrtlaProtocol.createKeepalivePacket();
        
        if (connection.sendSrtlaPacket(packet)) {
            connection.recordKeepaliveSent(); // Record timestamp for RTT measurement
            SrtlaLogger.dev(TAG, "✅ Sent keepalive to " + connection.getNetworkType() + 
                           " (length=" + packet.length + ")");
            return true;
        } else {
            Log.w(TAG, "❌ Failed to send keepalive to " + connection.getNetworkType());
        }
        
        return false;
    }
    
    /**
     * Perform connection housekeeping (matches connection_housekeeping in srtla_send.c)
     */
    public void performHousekeeping(List<SrtlaConnection> connections) {
        long currentTime = System.currentTimeMillis() / 1000;
        activeConnections = 0;
        
        Log.d(TAG, "⚙️ Performing housekeeping for " + connections.size() + " connections");
        
        // Clear pending registration if timeout expired
        if (pendingReg2Connection != null && currentTime > pendingRegTimeout) {
            Log.w(TAG, "REG2 timeout expired, clearing pending connection");
            pendingReg2Connection = null;
        }
        
        for (SrtlaConnection connection : connections) {
            Log.v(TAG, "Housekeeping " + connection.getNetworkType() + ": connected=" + 
                  connection.isConnected() + ", state=" + connection.getState() + 
                  ", timedOut=" + connection.isTimedOut());
                  
            if (!connection.isConnected()) {
                // Skip disconnected connections - let the service handle reconnection
                SrtlaLogger.debug(TAG, connection.getNetworkType() + " not connected, skipping in housekeeping");
                continue;
            }

            // Handle timed out connections first (matches C logic: reset and re-register)
            if (connection.isTimedOut()) {
                // Reset connection stats like in C code
                Log.i(TAG, connection.getNetworkType() + " timed out, resetting and re-registering");
                connection.resetConnection(); // This sets state to REGISTERING_REG1
                
                // Re-register based on current state (matches C logic)
                if (pendingReg2Connection == null) {
                    // No pending registration - send REG2 to re-establish
                    sendReg2(connection);
                } else if (pendingReg2Connection == connection) {
                    // This connection was pending - send REG1 to restart
                    if (sendReg1(connection)) {
                        pendingRegTimeout = (System.currentTimeMillis() / 1000) + SrtlaProtocol.REG2_TIMEOUT;
                    }
                }
                continue; // Skip rest of processing for this connection
            }
            
            // Connection is active - handle based on state
            if (connection.getState() == SrtlaConnection.ConnectionState.CONNECTED) {
                activeConnections++;
            } else if (connection.getState() == SrtlaConnection.ConnectionState.REGISTERING_REG1) {
                // Connection needs to start registration
                // Check if this is a newly connected socket (recently transitioned to REG1)
                long timeSinceStateChange = (System.currentTimeMillis() - connection.getStateChangeTime()) / 1000;
                boolean isNewConnection = timeSinceStateChange < 5; // New connection in last 5 seconds
                
                if ((activeConnections == 0 && pendingReg2Connection == null && currentTime > pendingRegTimeout) || isNewConnection) {
                    // First connection or new connection - send REG1 to establish/join group
                    if (sendReg1(connection)) {
                        pendingReg2Connection = connection;
                        pendingRegTimeout = (System.currentTimeMillis() / 1000) + SrtlaProtocol.REG2_TIMEOUT;
                        Log.d(TAG, "Started registration for " + connection.getNetworkType() + 
                              " (new=" + isNewConnection + ", active=" + activeConnections + ")");
                    }
                } else if (activeConnections > 0) {
                    // Additional connections - send REG2 to join existing group
                    sendReg2(connection);
                    Log.d(TAG, "Joining existing group for " + connection.getNetworkType());
                }
                // If there's already a pending REG2 connection, wait
            } else if (connection.getState() == SrtlaConnection.ConnectionState.REGISTERING_REG2) {
                // Connection is waiting for REG3, send REG2 again if needed
                sendReg2(connection);
            }
            
            // Send keepalive if needed for connected connections
            if (connection.getState() == SrtlaConnection.ConnectionState.CONNECTED && connection.needsKeepalive()) {
                Log.d(TAG, connection.getNetworkType() + ": sending regular keepalive (lastKeepalive=" + 
                      (System.currentTimeMillis() - connection.getLastKeepaliveTime()) + "ms ago)");
                sendKeepalive(connection);
            }
            
            // Send additional keepalive for RTT measurement if needed for connected connections
            if (connection.getState() == SrtlaConnection.ConnectionState.CONNECTED && connection.needsRttMeasurement()) {
                Log.d(TAG, connection.getNetworkType() + ": sending RTT measurement keepalive (lastRTT=" + 
                      (System.currentTimeMillis() - connection.getLastRttMeasurement()) + "ms ago)");
                sendKeepalive(connection);
            }
        }
        
        Log.v(TAG, "Housekeeping: " + activeConnections + " active connections");
    }
    
    /**
     * Select best connection for sending data (matches select_conn in srtla_send.c)
     */
    public SrtlaConnection selectConnection(List<SrtlaConnection> connections) {
        SrtlaConnection bestConnection = null;
        int maxScore = -1;
        
        for (SrtlaConnection connection : connections) {
            int score = connection.getScore();
            if (score > maxScore) {
                bestConnection = connection;
                maxScore = score;
            }
        }
        
        return bestConnection;
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    // Getters
    public byte[] getSrtlaId() { return srtlaId.clone(); }
    public int getActiveConnections() { return activeConnections; }
    public boolean hasConnected() { return hasConnected; }
    public SrtlaConnection getPendingReg2Connection() { return pendingReg2Connection; }
}
