package com.example.srtla;

/**
 * Native interface for the clean SRTLA implementation
 * This provides a simplified, battle-tested SRTLA implementation
 * based on the original BELABOX SRTLA code
 */
public class SRTLANative {
    
    static {
        System.loadLibrary("srtla_native");
    }
    
    // Session management
    public native long createSession();
    public native void destroySession(long sessionPtr);
    public native boolean initialize(long sessionPtr, String serverHost, int serverPort, int localPort);
    public native void shutdown(long sessionPtr);
    
    // Connection management
    public native boolean addConnection(long sessionPtr, String localIp, int networkHandle);
    public native void removeConnection(long sessionPtr, String localIp);
    
    // Statistics
    public native int getActiveConnectionCount(long sessionPtr);
    public native String[] getConnectionStats(long sessionPtr);
}