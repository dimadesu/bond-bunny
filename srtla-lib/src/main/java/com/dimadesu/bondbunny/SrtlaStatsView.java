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
 * &lt;com.dimadesu.bondbunny.SrtlaStatsView
 *     android:id="@+id/srtla_stats"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" /&gt;
 * </pre>
 *
 * <p>Call {@link #startUpdating()} in your Activity's {@code onResume()} and
 * {@link #stopUpdating()} in {@code onPause()}.</p>
 */
public class SrtlaStatsView extends LinearLayout {

    private static final String TAG = "SrtlaStatsView";
    private static final long UPDATE_INTERVAL_MS = 1000L;

    private TextView textTotalBitrate;
    private LinearLayout connectionsContainer;
    private TextView textNoConnections;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

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

    /** Start polling stats. Call from Activity.onResume(). */
    public void startUpdating() {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionStats();
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        handler.post(updateRunnable);
    }

    /** Stop polling. Call from Activity.onPause(). */
    public void stopUpdating() {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
            updateRunnable = null;
        }
        clearConnectionsDisplay();
    }

    // -------------------------------------------------------------------------
    // Stats update
    // -------------------------------------------------------------------------

    private void updateConnectionStats() {
        if (!NativeSrtlaJni.isRunningSrtlaNative()) {
            clearConnectionsDisplay();
            return;
        }

        boolean isConnected = NativeSrtlaJni.isConnected();
        boolean isRetrying  = NativeSrtlaJni.isRetrying();
        int     retryCount  = NativeSrtlaJni.getRetryCount();

        String statsText = NativeSrtlaJni.getAllStats();
        boolean hasStats = statsText != null && statsText.contains("Total bitrate:");

        if (isRetrying || retryCount > 0) {
            String msg = String.format("🔄 Reconnecting... (attempt %d)",
                    retryCount > 0 ? retryCount : 1);
            textTotalBitrate.setText(msg);
            textTotalBitrate.setVisibility(VISIBLE);
            connectionsContainer.removeAllViews();
            textNoConnections.setVisibility(GONE);

        } else if (!isConnected && !hasStats) {
            textTotalBitrate.setText("Connecting to SRTLA receiver...");
            textTotalBitrate.setVisibility(VISIBLE);
            connectionsContainer.removeAllViews();
            textNoConnections.setVisibility(GONE);

        } else if (hasStats) {
            parseAndDisplayConnections(statsText);

        } else {
            textTotalBitrate.setText("Waiting for connection stats...");
            textTotalBitrate.setVisibility(VISIBLE);
            connectionsContainer.removeAllViews();
            textNoConnections.setVisibility(GONE);
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
        textNoConnections.setVisibility(View.GONE);
        
        // Parse the stats text to extract connection information
        // Format: "Total bitrate: X.X Mbps\n\nWIFI\n  Bitrate: ... \n  Window: ...\n  Packets in-flight: ...\n\n..."
        String[] sections = statsText.split("\n\n");
        LayoutInflater inflater = LayoutInflater.from(getContext());
        
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

        // Hide total bitrate when no connection
        textTotalBitrate.setVisibility(GONE);

        // Show the "no connections" message
        textNoConnections.setVisibility(VISIBLE);
        connectionsContainer.addView(textNoConnections);
    }
}
