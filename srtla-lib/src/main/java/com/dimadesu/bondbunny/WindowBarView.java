package com.dimadesu.bondbunny;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Simple fixed-height view that displays a window capacity bar
 */
public class WindowBarView extends View {
    
    private Paint barPaint;
    private Paint backgroundPaint;
    private Paint borderPaint;
    
    private static final int MAX_WINDOW_SIZE = 60000;
    private static final int BAR_HEIGHT_DP = 12; // Fixed height in dp
    
    private int windowSize = 0;
    
    public WindowBarView(Context context) {
        super(context);
        init();
    }
    
    public WindowBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        barPaint = new Paint();
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setAntiAlias(true);
        
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#e9ecef"));
        backgroundPaint.setStyle(Paint.Style.FILL);
        
        borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);
        borderPaint.setAntiAlias(true);
    }
    
    public void setWindowData(int windowSize, boolean isActive) {
        this.windowSize = windowSize;
        invalidate();
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (BAR_HEIGHT_DP * getResources().getDisplayMetrics().density);
        setMeasuredDimension(width, height);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        // Draw background
        RectF bgRect = new RectF(0, 0, width, height);
        canvas.drawRoundRect(bgRect, 4, 4, backgroundPaint);
        
        // Calculate window ratio
        float windowRatio = Math.min((float) windowSize / MAX_WINDOW_SIZE, 1.0f);
        int barWidth = (int) (width * windowRatio);
        
        // Set blue color for all bars
        barPaint.setColor(Color.parseColor("#5b9bd5")); // Medium blue
        borderPaint.setColor(Color.parseColor("#5b9bd5"));
        
        // Draw bar
        if (barWidth > 0) {
            RectF barRect = new RectF(0, 0, barWidth, height);
            canvas.drawRoundRect(barRect, 4, 4, barPaint);
        }
        
        // Draw border around entire bar area
        canvas.drawRoundRect(bgRect, 4, 4, borderPaint);
    }
}
