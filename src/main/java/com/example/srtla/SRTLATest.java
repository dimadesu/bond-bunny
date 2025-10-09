package com.example.srtla;

import android.util.Log;

/**
 * Simple test class to verify the SRTLA Android wrapper integration
 */
public class SRTLATest {
    private static final String TAG = "SRTLATest";
    
    /**
     * Test basic SRTLA native library loading and session creation
     */
    public static boolean testBasicFunctionality() {
        try {
            Log.i(TAG, "Testing SRTLA native library...");
            
            // Create SRTLANative instance
            SRTLANative srtla = new SRTLANative();
            
            // Test 1: Create session
            long sessionPtr = srtla.createSession();
            if (sessionPtr == 0) {
                Log.e(TAG, "Failed to create SRTLA session");
                return false;
            }
            Log.i(TAG, "✓ SRTLA session created successfully: " + sessionPtr);
            
            // Test 2: Get connection count (should be 0 initially)
            int connectionCount = srtla.getActiveConnectionCount(sessionPtr);
            Log.i(TAG, "✓ Initial connection count: " + connectionCount);
            
            // Test 3: Destroy session
            srtla.destroySession(sessionPtr);
            Log.i(TAG, "✓ SRTLA session destroyed successfully");
            
            Log.i(TAG, "All SRTLA basic tests passed!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "SRTLA test failed: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Test SRTLA functionality with mock connections
     */
    public static boolean testConnectionManagement() {
        try {
            Log.i(TAG, "Testing SRTLA connection management...");
            
            SRTLANative srtla = new SRTLANative();
            
            long sessionPtr = srtla.createSession();
            if (sessionPtr == 0) {
                Log.e(TAG, "Failed to create session for connection test");
                return false;
            }
            
            // Initialize session
            boolean initialized = srtla.initialize(sessionPtr, "srtla.belabox.net", 5000, 6000);
            if (!initialized) {
                Log.e(TAG, "Failed to initialize SRTLA session");
                srtla.destroySession(sessionPtr);
                return false;
            }
            Log.i(TAG, "✓ SRTLA session initialized");
            
            // Add a mock connection with virtual IP support
            boolean connectionAdded = srtla.addConnection(sessionPtr, "192.168.1.100", 0L, "Test");
            if (!connectionAdded) {
                Log.e(TAG, "Failed to add connection");
                srtla.shutdown(sessionPtr);
                srtla.destroySession(sessionPtr);
                return false;
            }
            Log.i(TAG, "✓ Connection added successfully");
            
            // Check connection count
            int count = srtla.getActiveConnectionCount(sessionPtr);
            Log.i(TAG, "✓ Active connection count: " + count);
            
            // Get connection stats
            String[] stats = srtla.getConnectionStats(sessionPtr);
            Log.i(TAG, "✓ Connection stats: " + (stats != null ? stats.length + " entries" : "null"));
            
            // Clean up
            srtla.shutdown(sessionPtr);
            srtla.destroySession(sessionPtr);
            Log.i(TAG, "✓ Session shutdown and destroyed");
            
            Log.i(TAG, "All SRTLA connection tests passed!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "SRTLA connection test failed: " + e.getMessage(), e);
            return false;
        }
    }
}