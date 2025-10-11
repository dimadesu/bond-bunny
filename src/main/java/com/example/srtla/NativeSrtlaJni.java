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
    
    // Network change notification
    public static native void notifyNetworkChange();
    
    // Virtual IP support for Application-Level Virtual IPs
    public static native void setNetworkSocket(String virtualIP, String realIP, 
                                             int networkType, int socketFD);
}