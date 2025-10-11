package com.example.srtla;

/**
 * Data class to hold per-connection bitrate information
 */
public class ConnectionBitrateData {
    public final double bitrateMbps;
    public final String connectionType;
    public final String connectionIP;
    public final int loadPercentage;
    public final int windowSize;
    public final int inFlightPackets;
    
    public ConnectionBitrateData(double bitrateMbps, String connectionType, 
                                String connectionIP, int loadPercentage,
                                int windowSize, int inFlightPackets) {
        this.bitrateMbps = bitrateMbps;
        this.connectionType = connectionType;
        this.connectionIP = connectionIP;
        this.loadPercentage = loadPercentage;
        this.windowSize = windowSize;
        this.inFlightPackets = inFlightPackets;
    }
    
    @Override
    public String toString() {
        return String.format("%s (%s): %.2f Mbps (%d%% load, %d pkts window, %d in-flight)", 
                           connectionType, connectionIP, bitrateMbps, loadPercentage, windowSize, inFlightPackets);
    }
}