package com.example.srtla;

/**
 * Data class to hold per-connection bitrate information
 */
public class ConnectionBitrateData {
    public final double bitrateMbps;
    public final String connectionType;
    public final String connectionIP;
    public final int loadPercentage;
    
    public ConnectionBitrateData(double bitrateMbps, String connectionType, 
                                String connectionIP, int loadPercentage) {
        this.bitrateMbps = bitrateMbps;
        this.connectionType = connectionType;
        this.connectionIP = connectionIP;
        this.loadPercentage = loadPercentage;
    }
    
    @Override
    public String toString() {
        return String.format("%s (%s): %.2f Mbps (%d%% load)", 
                           connectionType, connectionIP, bitrateMbps, loadPercentage);
    }
}