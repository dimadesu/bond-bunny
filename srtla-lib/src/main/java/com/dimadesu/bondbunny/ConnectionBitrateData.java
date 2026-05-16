package com.dimadesu.bondbunny;

/**
 * Data class holding per-connection SRTLA statistics.
 */
public class ConnectionBitrateData {
    public final double bitrateMbps;
    public final String connectionType;
    public final String connectionIP;
    public final int loadPercentage;
    public final int windowSize;
    public final int inFlightPackets;
    public final boolean isActive;

    public ConnectionBitrateData(double bitrateMbps, String connectionType,
                                 String connectionIP, int loadPercentage,
                                 int windowSize, int inFlightPackets, boolean isActive) {
        this.bitrateMbps      = bitrateMbps;
        this.connectionType   = connectionType;
        this.connectionIP     = connectionIP;
        this.loadPercentage   = loadPercentage;
        this.windowSize       = windowSize;
        this.inFlightPackets  = inFlightPackets;
        this.isActive         = isActive;
    }

    @Override
    public String toString() {
        return String.format("%s (%s): %.2f Mbps (%d%% load, window %d, in-flight %d, %s)",
                connectionType, connectionIP, bitrateMbps, loadPercentage,
                windowSize, inFlightPackets, isActive ? "ACTIVE" : "INACTIVE");
    }
}
