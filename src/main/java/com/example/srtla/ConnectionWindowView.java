package com.example.srtla;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom view to visualize SRTLA connection windows and performance
 * Shows congestion window sizes, in-flight packets, and network types
 */
public class ConnectionWindowView extends View {
    
    private Paint backgroundPaint;
    private Paint borderPaint;
    private Paint textPaint;
    private Paint windowBarPaint;
    private Paint inFlightBarPaint;
    private Paint scorePaint;
    
    private List<ConnectionWindowData> connectionData = new ArrayList<>();
    private static final int MAX_WINDOW_SIZE = 60000; // Maximum window size for scaling
    private static final int WINDOW_STABLE_MIN = 10000;
    private static final int WINDOW_STABLE_MAX = 20000;
    
    public static class ConnectionWindowData {
        public String networkType;
        public int window;
        public int inFlightPackets;
        public int score;
        public boolean isActive;
        public boolean isSelected;
        public long rtt;
        public String state;
        public double bitrateBps; // bits per second
        
        public ConnectionWindowData(String networkType, int window, int inFlightPackets, 
                                  int score, boolean isActive, boolean isSelected, 
                                  long rtt, String state, double bitrateBps) {
            this.networkType = networkType;
            this.window = window;
            this.inFlightPackets = inFlightPackets;
            this.score = score;
            this.isActive = isActive;
            this.isSelected = isSelected;
            this.rtt = rtt;
            this.state = state;
            this.bitrateBps = bitrateBps;
        }
    }
    
    public ConnectionWindowView(Context context) {
        super(context);
        init();
    }
    
    public ConnectionWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#f8f9fa"));
        backgroundPaint.setStyle(Paint.Style.FILL);
        
        borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#dee2e6"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);
        
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#495057"));
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        
        windowBarPaint = new Paint();
        windowBarPaint.setStyle(Paint.Style.FILL);
        windowBarPaint.setAntiAlias(true);
        
        inFlightBarPaint = new Paint();
        inFlightBarPaint.setStyle(Paint.Style.FILL);
        inFlightBarPaint.setAntiAlias(true);
        
        scorePaint = new Paint();
        scorePaint.setColor(Color.parseColor("#6c757d"));
        scorePaint.setTextSize(32);
        scorePaint.setAntiAlias(true);
    }
    
    public void updateConnectionData(List<ConnectionWindowData> data) {
        this.connectionData = new ArrayList<>(data);
        invalidate(); // Trigger redraw
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (connectionData.isEmpty()) {
            drawNoConnections(canvas);
            return;
        }
        
        int width = getWidth();
        int height = getHeight();
        int padding = 40;
        int connectionHeight = (height - padding * 2) / Math.max(connectionData.size(), 1);
        
        // Calculate total bitrate
        double totalBitrate = 0;
        for (ConnectionWindowData conn : connectionData) {
            if (conn.isActive) {
                totalBitrate += conn.bitrateBps;
            }
        }
        
        // Draw total bitrate at the top
        textPaint.setTextSize(40);
        textPaint.setColor(Color.parseColor("#28a745"));
        String totalBitrateText = "📊 Total: " + formatBitrate(totalBitrate);
        float totalBitrateWidth = textPaint.measureText(totalBitrateText);
        canvas.drawText(totalBitrateText, width - padding - totalBitrateWidth, padding - 10, textPaint);
        
        for (int i = 0; i < connectionData.size(); i++) {
            ConnectionWindowData conn = connectionData.get(i);
            int y = padding + i * connectionHeight;
            drawConnection(canvas, conn, padding, y, width - padding * 2, connectionHeight - 20);
        }
    }
    
    private void drawNoConnections(Canvas canvas) {
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        
        textPaint.setTextSize(36);
        textPaint.setColor(Color.parseColor("#6c757d"));
        String text = "No active connections";
        float textWidth = textPaint.measureText(text);
        canvas.drawText(text, centerX - textWidth / 2, centerY, textPaint);
    }
    
    private void drawConnection(Canvas canvas, ConnectionWindowData conn, int x, int y, int width, int height) {
        // Background rectangle with selection highlight
        RectF bgRect = new RectF(x, y, x + width, y + height);
        if (conn.isSelected) {
            backgroundPaint.setColor(Color.parseColor("#e3f2fd"));
            borderPaint.setColor(Color.parseColor("#2196f3"));
            borderPaint.setStrokeWidth(4);
        } else if (conn.isActive) {
            backgroundPaint.setColor(Color.parseColor("#f8f9fa"));
            borderPaint.setColor(Color.parseColor("#28a745"));
            borderPaint.setStrokeWidth(2);
        } else {
            backgroundPaint.setColor(Color.parseColor("#f8f9fa"));
            borderPaint.setColor(Color.parseColor("#dc3545"));
            borderPaint.setStrokeWidth(2);
        }
        
        canvas.drawRoundRect(bgRect, 8, 8, backgroundPaint);
        canvas.drawRoundRect(bgRect, 8, 8, borderPaint);
        
        // Network type and status
        textPaint.setTextSize(40);
        textPaint.setColor(Color.parseColor("#212529"));
        String networkIcon = getNetworkIcon(conn.networkType);
        String title = networkIcon + " " + conn.networkType + " (" + conn.state + ")";
        canvas.drawText(title, x + 20, y + 40, textPaint);
        
        // Window visualization
        int barY = y + 65;
        int barHeight = 28;
        int barWidth = width - 40;
        
        // Window capacity bar
        float windowRatio = Math.min((float) conn.window / MAX_WINDOW_SIZE, 1.0f);
        int windowBarWidth = (int) (barWidth * windowRatio);
        
        // Color based on window stability
        if (conn.window >= WINDOW_STABLE_MAX) {
            windowBarPaint.setColor(Color.parseColor("#28a745")); // Green - high window
        } else if (conn.window >= WINDOW_STABLE_MIN) {
            windowBarPaint.setColor(Color.parseColor("#ffc107")); // Yellow - medium window
        } else {
            windowBarPaint.setColor(Color.parseColor("#dc3545")); // Red - low window
        }
        
        RectF windowRect = new RectF(x + 20, barY, x + 20 + windowBarWidth, barY + barHeight);
        canvas.drawRoundRect(windowRect, 4, 4, windowBarPaint);
        
        // Window statistics text
        textPaint.setTextSize(36);
        textPaint.setColor(Color.parseColor("#495057"));
        String windowStats = String.format("Window: %,d / %,d", conn.window, MAX_WINDOW_SIZE);
        canvas.drawText(windowStats, x + 20, barY + barHeight + 30, textPaint);
    }
    
    private String getNetworkIcon(String networkType) {
        switch (networkType.toLowerCase()) {
            case "wifi": return "📶";
            case "cellular": return "📱";
            case "ethernet": return "🔗";
            case "vpn": return "🔒";
            default: return "🌐";
        }
    }
    
    private String formatBitrate(double bitsPerSecond) {
        if (bitsPerSecond < 1000) {
            return String.format("%.0f bps", bitsPerSecond);
        } else if (bitsPerSecond < 1000000) {
            return String.format("%.1f Kbps", bitsPerSecond / 1000);
        } else if (bitsPerSecond < 1000000000) {
            return String.format("%.1f Mbps", bitsPerSecond / 1000000);
        } else {
            return String.format("%.1f Gbps", bitsPerSecond / 1000000000);
        }
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = Math.max(connectionData.size() * 160 + 120, 200);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }
}
