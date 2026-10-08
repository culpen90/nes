package com.culpen.nes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A small, thread-safe framebuffer view. Frames are copied before this call returns. */
public final class NesScreenView extends View {
    private final Object frameLock = new Object();
    private final Paint pixels = new Paint();
    private final RectF destination = new RectF();
    private Bitmap frame;
    private volatile boolean pixelFilter;

    public NesScreenView(Context context) {
        super(context);
        pixels.setAntiAlias(false);
        pixels.setDither(false);
        pixels.setFilterBitmap(false);
        setBackgroundColor(Color.BLACK);
        setContentDescription("NES game screen");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setFrame(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0
                || (long) width * height > argb.length) {
            throw new IllegalArgumentException("A frame must contain width × height pixels");
        }
        synchronized (frameLock) {
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            frame.setPixels(argb, 0, width, 0, 0, width, height);
        }
        postInvalidateOnAnimation();
    }

    public void clearFrame() {
        synchronized (frameLock) {
            frame = null;
        }
        postInvalidateOnAnimation();
    }

    /** False keeps the original pixels sharp; true enables a smooth bilinear image. */
    public void setPixelFilter(boolean enabled) {
        pixelFilter = enabled;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int suggestedWidth = Math.round(320 * getResources().getDisplayMetrics().density);
        int width = resolveSize(suggestedWidth + getPaddingLeft() + getPaddingRight(),
                widthMeasureSpec);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int height = Math.round(contentWidth * 240f / 256f)
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        synchronized (frameLock) {
            if (frame == null) return;
            float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
            if (availableWidth <= 0 || availableHeight <= 0) return;
            float scale = Math.min(availableWidth / frame.getWidth(),
                    availableHeight / frame.getHeight());
            float width = frame.getWidth() * scale;
            float height = frame.getHeight() * scale;
            float left = getPaddingLeft() + (availableWidth - width) / 2f;
            float top = getPaddingTop() + (availableHeight - height) / 2f;
            destination.set(left, top, left + width, top + height);
            pixels.setFilterBitmap(pixelFilter);
            canvas.drawBitmap(frame, null, destination, pixels);
        }
    }
}
