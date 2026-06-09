package com.example.cppr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;

public class RiskBoundsOverlay extends View {

    private boolean showBounds = false;

    private final Paint borderPaint;
    private final Paint dotPaint;
    private final Paint fillPaint;
    private final RectF borderRect;
    private java.util.Map<String, BoundingBox> boundingBoxes;

    public RiskBoundsOverlay(Context context) {
        super(context);

        borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3);
        borderPaint.setAntiAlias(true);

        dotPaint = new Paint();
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(true);

        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        borderRect = new RectF();
        boundingBoxes = new java.util.HashMap<>();
    }

    public void setShowBounds(boolean show) {
        this.showBounds = show;
    }

    public boolean isShowBounds() {
        return this.showBounds;
    }

    public void addBoundingBox(String title, BoundingBox box) {
        boundingBoxes.put(title, box);
        invalidate();
    }

    public void removeBoundingBox(String title) {
        boundingBoxes.remove(title);
        invalidate();
    }

    public void clearBoundingBoxes() {
        boundingBoxes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!showBounds || boundingBoxes.isEmpty()) return;

        for (BoundingBox box : boundingBoxes.values()) {
            if (box != null) {
                drawBoundingBox(canvas, box);
            }
        }
    }

    private void drawBoundingBox(Canvas canvas, BoundingBox box) {
        int color = getRiskLevelColor(box.getRiskLevel());
        borderPaint.setColor(color);
        dotPaint.setColor(color);

        borderRect.set(box.getLeft(), box.getTop(), box.getRight(), box.getBottom());

        float cornerRadius = dpToPx(4);
        fillPaint.setColor(color);
        fillPaint.setAlpha(30);
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, fillPaint);
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint);

        float dotRadius = dpToPx(4);
        canvas.drawCircle(box.getLeft() + dotRadius, box.getTop() + dotRadius, dotRadius, dotPaint);
    }

    private int getRiskLevelColor(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return 0xFFE57373;
            case MEDIUM: return 0xFFFF9800;
            case LOW:    return 0xFF4CAF50;
            default:     return 0xFFFF9800;
        }
    }

    private int dpToPx(int dp) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        return Math.round(dp * metrics.density);
    }
}
