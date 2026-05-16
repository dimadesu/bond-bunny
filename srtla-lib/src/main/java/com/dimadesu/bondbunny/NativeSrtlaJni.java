package com.dimadesu.bondbunny;

import android.util.Log;

/**
 * JNI wrapper for native SRTLA functionality.
 * Provides a single point of access to native methods.
 */
public class NativeSrtlaJni {
    private static final String TAG = "NativeSrtlaJni";

    static {
        try {
            System.loadLibrary("srtla_android");
            Log.i(TAG, "Native SRTLA library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native SRTLA library", e);
        }
    }

    // --- Lifecycle ---
    public static native int startSrtlaNative(String listenPort, String srtlaHost,
                                              String srtlaPort, String ipsFile);
    public static native int stopSrtlaNative();
    public static native boolean isRunningSrtlaNative();

    // --- Connection state ---
    public static native boolean isConnected();
    public static native boolean isRetrying();
    public static native int getRetryCount();

    // --- Aggregate stats ---
    public static native int getConnectionCount();
    public static native int getActiveConnectionCount();
    public static native int getTotalInFlightPackets();
    public static native String getAllStats();

    // --- Per-connection stats ---
    public static native double[] getConnectionBitrates();
    public static native String[] getConnectionTypes();
    public static native String[] getConnectionIPs();
    public static native int[] getConnectionLoadPercentages();
    public static native int[] getConnectionWindowSizes();
    public static native int[] getConnectionInFlightPackets();
    public static native boolean[] getConnectionActiveStatus();

    // --- Network management ---
    public static native void notifyNetworkChange();
    public static native void setNetworkSocket(String virtualIP, String realIP,
                                               int networkType, int socketFD);

    // --- Socket helpers (used by SrtlaSender) ---
    public static native int createUdpSocketNative();
    public static native void closeSocketNative(int socketFD);

    // --- Helper: fetch all connection data in one call ---
    public static ConnectionBitrateData[] getAllConnectionBitrates() {
        try {
            double[] bitrates   = getConnectionBitrates();
            String[] types      = getConnectionTypes();
            String[] ips        = getConnectionIPs();
            int[]    loads      = getConnectionLoadPercentages();
            int[]    windows    = getConnectionWindowSizes();
            int[]    inflight   = getConnectionInFlightPackets();
            boolean[] active    = getConnectionActiveStatus();

            if (bitrates == null || types == null || ips == null || loads == null
                    || windows == null || inflight == null || active == null) {
                return new ConnectionBitrateData[0];
            }

            int count = Math.min(
                Math.min(Math.min(bitrates.length, types.length), Math.min(ips.length, loads.length)),
                Math.min(Math.min(windows.length, inflight.length), active.length));

            ConnectionBitrateData[] result = new ConnectionBitrateData[count];
            for (int i = 0; i < count; i++) {
                result[i] = new ConnectionBitrateData(
                    bitrates[i], types[i], ips[i], loads[i], windows[i], inflight[i], active[i]);
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error getting connection bitrate data", e);
            return new ConnectionBitrateData[0];
        }
    }
}
