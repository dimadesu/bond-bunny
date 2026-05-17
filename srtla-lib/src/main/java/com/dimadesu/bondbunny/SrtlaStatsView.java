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
        if (statsText == null || !statsText.contains("Total bitrate:")) {
            clearConnectionsDisplay();
            return;
        }

        connectionsContainer.removeAllViews();
        textNoConnections.setVisibility(GONE);

        String[] sections = statsText.split("\n\n");
        LayoutInflater inflater = LayoutInflater.from(getContext());

        // First section = "Total bitrate: X.X Mbps"
        if (sections.length > 0 && sections[0].startsWith("Total bitrate:")) {
            textTotalBitrate.setText(sections[0]);
            textTotalBitrate.setVisibility(VISIBLE);
        }

        for (String section : sections) {
            if (section.trim().isEmpty() || section.startsWith("Total bitrate:")) continue;

            try {
                String[] lines = section.trim().split("\n");
                if (lines.length < 5) continue;

                String networkType = lines[0].trim();

                String bitrateLine = lines[1].trim();
                String bitrate = "N/A";
                String load = "";
                if (bitrateLine.startsWith("Bitrate:")) {
                    String[] parts = bitrateLine.substring(8).trim().split(" ");
                    if (parts.length >= 2) bitrate = parts[0] + " " + parts[1];
                    if (parts.length >= 3) load    = parts[2];
                }

                int windowSize = 0;
                String windowLine = lines[2].trim();
                if (windowLine.startsWith("Window:")) {
                    try { windowSize = Integer.parseInt(windowLine.substring(7).trim()); }
                    catch (NumberFormatException ignored) { }
                }

                int inFlight = 0;
                String inFlightLine = lines[3].trim();
                if (inFlightLine.startsWith("Packets in-flight:")) {
                    try { inFlight = Integer.parseInt(inFlightLine.substring(18).trim()); }
                    catch (NumberFormatException ignored) { }
                }

                String rtt = "N/A";
                String rttLine = lines[4].trim();
                if (rttLine.startsWith("RTT:")) rtt = rttLine.substring(4).trim();

                boolean isActive = inFlight > 0
                        || (bitrate != null && !bitrate.equals("0.00 Mbps") && !bitrate.equals("N/A"));

                View itemView = inflater.inflate(R.layout.connection_item, connectionsContainer, false);

                TextView typeView = itemView.findViewById(R.id.connection_network_type);
                typeView.setText(networkType.equals("WIFI") ? "WI-FI" : networkType);

                TextView statusView = itemView.findViewById(R.id.connection_status);
                if (isActive) {
                    statusView.setText("ACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#28a745"));
                } else {
                    statusView.setText("INACTIVE");
                    statusView.setTextColor(android.graphics.Color.parseColor("#dc3545"));
                }

                WindowBarView bar = itemView.findViewById(R.id.window_bar);
                bar.setWindowData(windowSize, isActive);

                TextView statsTextView = itemView.findViewById(R.id.connection_stats_text);
                statsTextView.setText(String.format(
                        "Bitrate: %s  %s\nPackets in-flight: %,d\nRTT: %s\nWindow: %,d / 60,000",
                        bitrate, load, inFlight, rtt, windowSize));

                connectionsContainer.addView(itemView);

            } catch (Exception e) {
                Log.e(TAG, "Error parsing section: " + section, e);
            }
        }

        if (connectionsContainer.getChildCount() == 0) {
            clearConnectionsDisplay();
        }
    }

    private void clearConnectionsDisplay() {
        connectionsContainer.removeAllViews();
        textTotalBitrate.setVisibility(GONE);
        textNoConnections.setVisibility(VISIBLE);
        connectionsContainer.addView(textNoConnections);
    }
}
