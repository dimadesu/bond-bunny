package com.dimadesu.bondbunny;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dimadesu.bondbunny.srtlalib.R;

/**
 * Self-contained view that polls native SRTLA stats every second and renders them.
 *
 * <p>Embed directly in any layout:</p>
 * <pre>
 * <com.dimadesu.bondbunny.SrtlaStatsView
 *     android:id="@+id/srtla_stats"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" />;
 * </pre>
 *
 * <p>Call {@link #startStatsUpdates()} in your Activity's {@code onResume()} and
 * {@link #stopStatsUpdates()} in {@code onPause()}.</p>
 */
public class SrtlaStatsView extends LinearLayout {

    private static final String TAG = "SrtlaStatsView";

    private TextView textTotalBitrate;
    private LinearLayout connectionsContainer;
    private TextView textNoConnections;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdateRunnable;
    private OnServiceStoppedListener onServiceStoppedListener;

    /**
     * Callback fired when the polling loop detects that the native SRTLA service
     * is no longer running.  The host Activity can use this to update button state, etc.
     */
    public interface OnServiceStoppedListener {
        void onServiceStopped();
    }

    public void setOnServiceStoppedListener(OnServiceStoppedListener listener) {
        this.onServiceStoppedListener = listener;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public SrtlaStatsView(Context context) {
        super(context);
        init(context);
    }

    public SrtlaStatsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SrtlaStatsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.srtla_stats_view, this, true);
        textTotalBitrate    = findViewById(R.id.text_total_bitrate);
        connectionsContainer = findViewById(R.id.connections_container);
        textNoConnections   = findViewById(R.id.text_no_connections);
        clearConnectionsDisplay();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void startStatsUpdates() {
        Log.i(TAG, "Starting stats updates");
        
        // Stop any existing stats updates first to avoid multiple concurrent loops
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
        }
        
        statsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // Log.i(TAG, "Stats update tick - nativeRunning=" + NativeSrtlaJni.isRunningSrtlaNative());
                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    updateConnectionStats();
                    uiHandler.postDelayed(this, 1000); // Update every second
                } else {
                    Log.i(TAG, "No services running, stopping stats updates");
                    updateConnectionStats(); // One last update to show "Service not running"
                    if (onServiceStoppedListener != null) {
                        onServiceStoppedListener.onServiceStopped();
                    }
                }
            }
        };
        uiHandler.post(statsUpdateRunnable);
    }
    
    public void stopStatsUpdates() {
        Log.i(TAG, "stopStatsUpdates called - nativeRunning=" + NativeSrtlaJni.isRunningSrtlaNative());
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
        }
        // Always clear stats when explicitly stopping updates
        Log.i(TAG, "Clearing stats display");
        clearConnectionsDisplay();
    }

    // -------------------------------------------------------------------------
    // Stats update
    // -------------------------------------------------------------------------

    private void updateConnectionStats() {
        long currentTime = System.currentTimeMillis();
        
        // Check if native SRTLA is running and show its stats instead
        if (NativeSrtlaJni.isRunningSrtlaNative()) {
            // First check connection status
            boolean isConnected = NativeSrtlaJni.isConnected();
            boolean isRetrying = NativeSrtlaJni.isRetrying();
            int retryCount = NativeSrtlaJni.getRetryCount();
            
            // Log the current state for debugging
            Log.i(TAG, String.format("updateConnectionStats: connected=%b, retrying=%b, retryCount=%d", 
                  isConnected, isRetrying, retryCount));
            
            // Get stats - this might return empty string if retrying/connecting
            String nativeStats = NativeSrtlaJni.getAllStats();
            boolean hasStats = nativeStats != null && !nativeStats.isEmpty() && nativeStats.contains("Total bitrate:");
            
            // Log stats state for debugging
            Log.i(TAG, String.format("Stats check: hasStats=%b, statsLen=%d, isEmpty=%b, hasTotalBitrate=%b",
                  hasStats, 
                  nativeStats != null ? nativeStats.length() : -1,
                  nativeStats == null || nativeStats.isEmpty(),
                  nativeStats != null && nativeStats.contains("Total bitrate:")));
            
            // Check retry state first - regardless of isConnected (handles server stops)
            if (isRetrying || retryCount > 0) {
                // Show retry status
                String statusMessage = String.format("🔄 Reconnecting... (attempt %d)", retryCount > 0 ? retryCount : 1);
                textTotalBitrate.setText(statusMessage);
                textTotalBitrate.setVisibility(View.VISIBLE);
                
                // Clear connection list
                connectionsContainer.removeAllViews();
                textNoConnections.setVisibility(View.GONE);
                
                Log.i(TAG, "Showing retry UI: " + statusMessage);
            } else if (!isConnected && !hasStats) {
                // Show initial connecting status (not connected, no stats yet)
                String statusMessage = "Connecting to SRTLA receiver...";
                textTotalBitrate.setText(statusMessage);
                textTotalBitrate.setVisibility(View.VISIBLE);
                
                // Clear connection list
                connectionsContainer.removeAllViews();
                textNoConnections.setVisibility(View.GONE);
                
                Log.i(TAG, "Showing connecting UI");
            } else if (hasStats) {
                // We have actual stats to display (even if bitrate is 0)
                parseAndDisplayConnections(nativeStats);
                Log.i(TAG, "Displaying stats");
            } else {
                // Connected but no stats yet - give it a moment
                // This can happen briefly when connections are established but stats not ready
                textTotalBitrate.setText("Waiting for connection stats...");
                textTotalBitrate.setVisibility(View.VISIBLE);
                
                connectionsContainer.removeAllViews();
                textNoConnections.setVisibility(View.GONE);
                
                Log.i(TAG, "Waiting for stats (connected=" + isConnected + ", hasStats=" + hasStats + ")");
            }
        } else {
            // Service not running - clear display
            connectionsContainer.removeAllViews();
            textNoConnections.setVisibility(View.VISIBLE);
            textNoConnections.setText("Service not running");
            
            textTotalBitrate.setVisibility(View.GONE);
        }
    }

    private void parseAndDisplayConnections(String statsText) {
        // Handle empty or no-connection state
        if (statsText == null || statsText.isEmpty() || !statsText.contains("Total bitrate:")) {
            // Don't clear if we're showing a retry/connecting status
            if (textTotalBitrate != null && textTotalBitrate.getVisibility() == View.VISIBLE) {
                String text = textTotalBitrate.getText().toString();
                if (text.contains("Connecting") || text.contains("Reconnecting")) {
                    // Keep the status message, just clear the connection list
                    connectionsContainer.removeAllViews();
                    textNoConnections.setVisibility(View.GONE);
                    return;
                }
            }
            
            clearConnectionsDisplay();
            return;
        }
        
        // Clear existing views
        connectionsContainer.removeAllViews();
        textNoConnections.setVisibility(android.view.View.GONE);
        
        // Parse the stats text to extract connection information
        // Format: "Total bitrate: X.X Mbps\n\nWIFI\n  Bitrate: ... \n  Window: ...\n  Packets in-flight: ...\n\n..."
        String[] sections = statsText.split("\n\n");
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(getContext());
        
        // Extract and display total bitrate from first section
        if (sections.length > 0 && sections[0].startsWith("Total bitrate:")) {
            textTotalBitrate.setText(sections[0]);
            textTotalBitrate.setVisibility(android.view.View.VISIBLE);
        }
        
        for (String section : sections) {
            if (section.trim().isEmpty() || section.startsWith("Total bitrate:")) {
                continue; // Skip empty sections and total bitrate line
            }
            
            // Parse connection section
            try {
                String[] lines = section.trim().split("\n");
                if (lines.length < 5) continue; // Need at least 5 lines: type, bitrate, window, in-flight, rtt
                
                // First line is network type
                String networkType = lines[0].trim();
                
                // Parse bitrate line: "  Bitrate: 45.2 Mbps 45%"
                String bitrateLine = lines[1].trim();
                String bitrate = "N/A";
                String load = "N/A";
                if (bitrateLine.startsWith("Bitrate:")) {
                    String bitrateData = bitrateLine.substring(8).trim(); // Remove "Bitrate:"
                    String[] bitrateParts = bitrateData.split(" ");
                    if (bitrateParts.length >= 2) {
                        bitrate = bitrateParts[0] + " " + bitrateParts[1]; // e.g., "45.2 Mbps"
                    }
                    if (bitrateParts.length >= 3) {
                        load = bitrateParts[2]; // e.g., "45%"
                    }
                }
                
                // Parse window line: "  Window: 15234"
                String windowLine = lines[2].trim();
                int windowSize = 0;
                if (windowLine.startsWith("Window:")) {
                    windowSize = Integer.parseInt(windowLine.substring(7).trim());
                }
                
                // Parse in-flight line: "  Packets in-flight: 125"
                String inFlightLine = lines[3].trim();
                int inFlight = 0;
                if (inFlightLine.startsWith("Packets in-flight:")) {
                    inFlight = Integer.parseInt(inFlightLine.substring(18).trim());
                }
                
                // Parse RTT line: "  RTT: 45 ms" or "  RTT: N/A"
                String rttLine = lines[4].trim();
                String rtt = "N/A";
                if (rttLine.startsWith("RTT:")) {
                    rtt = rttLine.substring(4).trim(); // e.g., "45 ms" or "N/A"
                }
                
                // Determine if connection is active (has bitrate > 0 or in-flight packets)
                boolean isActive = inFlight > 0 || (bitrate != null && !bitrate.equals("0.00 Mbps") && !bitrate.equals("N/A"));
                
                // Create connection item view
                android.view.View connectionView = inflater.inflate(R.layout.connection_item, connectionsContainer, false);
                
                // Set network type with display formatting
                TextView networkTypeView = connectionView.findViewById(R.id.connection_network_type);
                String displayName = networkType.equals("WIFI") ? "WI-FI" : networkType;
                networkTypeView.setText(displayName);
                
                // Set status
                TextView statusView = connectionView.findViewById(R.id.connection_status);
                if (isActive) {
                    statusView.setText("ACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#28a745"));
                } else {
                    statusView.setText("INACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#dc3545"));
                }
                
                // Set window bar
                WindowBarView windowBar = connectionView.findViewById(R.id.window_bar);
                windowBar.setWindowData(windowSize, isActive);
                
                // Set stats text
                TextView statsTextView = connectionView.findViewById(R.id.connection_stats_text);
                String statsDisplay = String.format(
                    "Bitrate: %s  %s\nPackets in-flight: %,d\nRTT: %s\nWindow: %,d / 60,000",
                    bitrate, load, inFlight, rtt, windowSize
                );
                statsTextView.setText(statsDisplay);
                
                // Add view to container
                connectionsContainer.addView(connectionView);
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing connection section: " + section, e);
            }
        }
        
        // If no connections were added, show the "no connections" message
        if (connectionsContainer.getChildCount() == 0) {
            clearConnectionsDisplay();
        }
    }

    private void clearConnectionsDisplay() {
        // Remove all dynamically added connection views
        connectionsContainer.removeAllViews();
        
        // Hide total bitrate when no connections
        textTotalBitrate.setVisibility(android.view.View.GONE);
        
        // Show the "no connections" message
        textNoConnections.setVisibility(android.view.View.VISIBLE);
        connectionsContainer.addView(textNoConnections);
    }
}
