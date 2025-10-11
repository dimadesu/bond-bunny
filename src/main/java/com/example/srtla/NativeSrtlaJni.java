package com.example.srtla;

import android.util.Log;

/**
 * JNI wrapper for native SRTLA functionality
 * Provides a single point of access to native methods
 */
public class NativeSrtlaJni {
    private static final String TAG = "NativeSrtlaJni";
    
    // Load native library
    static {
        try {
            System.loadLibrary("srtla_android");
            Log.i(TAG, "Native SRTLA library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native SRTLA library", e);
        }
    }
    
    // Native methods - these will use the existing JNI implementation
    public static native int startSrtlaNative(String listenPort, String srtlaHost, 
                                             String srtlaPort, String ipsFile);
    public static native int stopSrtlaNative();
    public static native boolean isRunningSrtlaNative();
    
    // Stats methods for minimal UI integration
    public static native int getConnectionCount();
    public static native int getActiveConnectionCount();
    public static native int getTotalInFlightPackets();
    
    // Optimized single-call stats method
    public static native String getAllStats();
    
    // Per-connection bitrate methods for UI
    public static native double[] getConnectionBitrates();
    public static native String[] getConnectionTypes();
    public static native String[] getConnectionIPs();
    public static native int[] getConnectionLoadPercentages();
    
    // Per-connection window data methods for accurate visualization
    public static native int[] getConnectionWindowSizes();
    public static native int[] getConnectionInFlightPackets();
    public static native boolean[] getConnectionActiveStatus();
    
    // Helper method to get all connection bitrate data in one call
    public static ConnectionBitrateData[] getAllConnectionBitrates() {
        try {
            double[] bitrates = getConnectionBitrates();
            String[] types = getConnectionTypes();
            String[] ips = getConnectionIPs();
            int[] loads = getConnectionLoadPercentages();
            int[] windows = getConnectionWindowSizes();
            int[] inflight = getConnectionInFlightPackets();
            boolean[] activeStatus = getConnectionActiveStatus();
            
            if (bitrates == null || types == null || ips == null || loads == null || 
                windows == null || inflight == null || activeStatus == null) {
                return new ConnectionBitrateData[0];
            }
            
            int count = Math.min(Math.min(Math.min(bitrates.length, types.length), 
                                         Math.min(ips.length, loads.length)),
                                Math.min(Math.min(windows.length, inflight.length), activeStatus.length));
            
            ConnectionBitrateData[] result = new ConnectionBitrateData[count];
            for (int i = 0; i < count; i++) {
                result[i] = new ConnectionBitrateData(bitrates[i], types[i], ips[i], loads[i], 
                                                     windows[i], inflight[i], activeStatus[i]);
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error getting connection bitrate data", e);
            return new ConnectionBitrateData[0];
        }
    }
    
    // Network change notification
    public static native void notifyNetworkChange();
    
    // Virtual IP support for Application-Level Virtual IPs
    public static native void setNetworkSocket(String virtualIP, String realIP, 
                                             int networkType, int socketFD);
}