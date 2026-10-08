package com.culpen.nes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.function.IntConsumer;

/** Drawn controls with independent fingers and libretro joypad button masks. */
public final class GamepadView extends View {
    public static final int B = 1 << 0;
    public static final int SELECT = 1 << 2;
    public static final int START = 1 << 3;
    public static final int UP = 1 << 4;
    public static final int DOWN = 1 << 5;
    public static final int LEFT = 1 << 6;
    public static final int RIGHT = 1 << 7;
    public static final int A = 1 << 8;

    private static final int BACKGROUND = Color.rgb(24, 27, 32);
    private static final int PANEL = Color.rgb(33, 37, 43);
    private static final int EDGE = Color.rgb(54, 59, 66);
    private static final int CREAM = Color.rgb(234, 229, 217);
    private static final int MUTED = Color.rgb(143, 148, 155);
    private static final int CORAL = Color.rgb(242, 108, 88);
    private static final int[] NODE_BITS = {UP, DOWN, LEFT, RIGHT, B, A, SELECT, START};
    private static final String[] NODE_NAMES = {
            "Up", "Down", "Left", "Right", "B", "A", "Select", "Start"
    };

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dpadPath = new Path();
    private final Path arrow = new Path();
    private final SparseIntArray pointerButtons = new SparseIntArray();
    private final RectF padBounds = new RectF();
    private final RectF selectBounds = new RectF();
    private final RectF startBounds = new RectF();
    private final RectF[] nodeBounds = new RectF[8];
    private final long[] accessibleUntil = new long[8];
    private final AccessibilityNodeProvider accessibility = new PadAccessibility();
    private final Runnable finishAccessiblePress = this::updateAccessiblePresses;
    private float padX, padY, padRadius, armWidth;
    private float aX, aY, bX, bY, buttonRadius;
    private volatile int buttons;
    private int accessibleButtons;
    private int accessibilityFocus = -1;
    private int hoveredNode = -1;
    private IntConsumer onButtonsChanged;

    public GamepadView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < nodeBounds.length; i++) nodeBounds[i] = new RectF();
        setBackgroundColor(BACKGROUND);
        setContentDescription("NES controller. Direction pad, B, A, Select and Start.");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
    }

    public void setOnButtonsChanged(IntConsumer listener) {
        onButtonsChanged = listener;
        if (listener != null) listener.accept(buttons);
    }

    public int getButtons() {
        return buttons;
    }

    public int getPreferredHeight() {
        return Math.round(232 * density);
    }

    /** Also called on cancellation, focus loss, hiding and detaching to avoid stuck keys. */
    public void releaseAll() {
        pointerButtons.clear();
        accessibleButtons = 0;
        for (int i = 0; i < accessibleUntil.length; i++) accessibleUntil[i] = 0;
        removeCallbacks(finishAccessiblePress);
        publishButtons(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(360 * density), widthMeasureSpec);
        int height = resolveSize(getPreferredHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        releaseAll();
        float w = width - getPaddingLeft() - getPaddingRight();
        float h = height - getPaddingTop() - getPaddingBottom();
        float x = getPaddingLeft();
        float y = getPaddingTop();
        float inset = Math.min(18 * density, w * .04f);
        padRadius = Math.max(0, Math.min(64 * density,
                Math.min(h * .34f, (w - inset * 2) * .205f)));
        armWidth = padRadius * .36f;
        padX = x + inset + padRadius;
        padY = y + h * .47f;
        padBounds.set(padX - padRadius, padY - padRadius,
                padX + padRadius, padY + padRadius);
        buttonRadius = Math.max(0, Math.min(31 * density, Math.min(h * .17f, w * .081f)));
        aX = x + w - inset - buttonRadius - 3 * density;
        aY = y + h * .39f;
        bX = aX - buttonRadius * 2.07f;
        bY = aY + buttonRadius * 1.02f;
        float pillWidth = Math.min(44 * density, Math.max(0, w * .112f));
        float pillHeight = Math.min(21 * density, Math.max(0, h * .108f));
        float gap = Math.min(14 * density, w * .035f);
        float middle = (padBounds.right + bX - buttonRadius) / 2f;
        // The center buttons sit below the thumbs in portrait and in the clear center
        // of wider, shorter layouts, using the same control and touch behavior.
        float pillY = y + h * (w / Math.max(h, 1) > 2.7f ? .55f : .75f);
        selectBounds.set(middle - gap / 2f - pillWidth, pillY - pillHeight / 2f,
                middle - gap / 2f, pillY + pillHeight / 2f);
        startBounds.set(middle + gap / 2f, pillY - pillHeight / 2f,
                middle + gap / 2f + pillWidth, pillY + pillHeight / 2f);

        buildDpadPath();
        nodeBounds[0].set(padX - armWidth, padY - padRadius, padX + armWidth, padY - armWidth);
        nodeBounds[1].set(padX - armWidth, padY + armWidth, padX + armWidth, padY + padRadius);
        nodeBounds[2].set(padX - padRadius, padY - armWidth, padX - armWidth, padY + armWidth);
        nodeBounds[3].set(padX + armWidth, padY - armWidth, padX + padRadius, padY + armWidth);
        nodeBounds[4].set(bX - buttonRadius, bY - buttonRadius, bX + buttonRadius, bY + buttonRadius);
        nodeBounds[5].set(aX - buttonRadius, aY - buttonRadius, aX + buttonRadius, aY + buttonRadius);
        nodeBounds[6].set(selectBounds);
        nodeBounds[7].set(startBounds);
    }

    private void buildDpadPath() {
        float r = padRadius;
        float a = armWidth;
        dpadPath.reset();
        dpadPath.moveTo(padX - a, padY - r);
        dpadPath.lineTo(padX + a, padY - r);
        dpadPath.lineTo(padX + a, padY - a);
        dpadPath.lineTo(padX + r, padY - a);
        dpadPath.lineTo(padX + r, padY + a);
        dpadPath.lineTo(padX + a, padY + a);
        dpadPath.lineTo(padX + a, padY + r);
        dpadPath.lineTo(padX - a, padY + r);
        dpadPath.lineTo(padX - a, padY + a);
        dpadPath.lineTo(padX - r, padY + a);
        dpadPath.lineTo(padX - r, padY - a);
        dpadPath.lineTo(padX - a, padY - a);
        dpadPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = getPaddingTop();
        float bottom = getHeight() - getPaddingBottom();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(EDGE);
        canvas.drawRect(0, top, getWidth(), top + density, paint);
        paint.setColor(MUTED);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(9 * density);
        if (getHeight() >= 180 * density) {
            canvas.drawText("CONTROLLER", getPaddingLeft() + 20 * density, top + 23 * density, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("PLAYER 01", getWidth() - getPaddingRight() - 20 * density,
                    top + 23 * density, paint);
        }

        paint.setColor(Color.BLACK);
        canvas.save();
        canvas.translate(0, 3 * density);
        canvas.drawPath(dpadPath, paint);
        canvas.restore();
        paint.setColor(EDGE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * density);
        canvas.drawPath(dpadPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        canvas.drawPath(dpadPath, paint);
        drawArrow(canvas, padX, padY - padRadius * .66f, 0, UP);
        drawArrow(canvas, padX, padY + padRadius * .66f, 180, DOWN);
        drawArrow(canvas, padX - padRadius * .66f, padY, -90, LEFT);
        drawArrow(canvas, padX + padRadius * .66f, padY, 90, RIGHT);
        paint.setColor((buttons & (UP | DOWN | LEFT | RIGHT)) == 0 ? EDGE : CORAL);
        canvas.drawCircle(padX, padY, padRadius * .12f, paint);

        drawRoundButton(canvas, bX, bY, "B", B, CREAM);
        drawRoundButton(canvas, aX, aY, "A", A, CORAL);
        drawPill(canvas, selectBounds, "SELECT", SELECT);
        drawPill(canvas, startBounds, "START", START);

        if (accessibilityFocus > 0 && accessibilityFocus <= nodeBounds.length) {
            paint.setColor(CORAL);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            RectF focused = new RectF(nodeBounds[accessibilityFocus - 1]);
            focused.inset(-4 * density, -4 * density);
            canvas.drawRoundRect(focused, 5 * density, 5 * density, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (bottom > top && padRadius > 0) {
            paint.setColor(EDGE);
            canvas.drawRect(getWidth() / 2f - 12 * density, bottom - 12 * density,
                    getWidth() / 2f + 12 * density, bottom - 10 * density, paint);
        }
    }

    private void drawArrow(Canvas canvas, float cx, float cy, float angle, int bit) {
        float size = padRadius * .10f;
        arrow.reset();
        arrow.moveTo(-size, size * .6f);
        arrow.lineTo(size, size * .6f);
        arrow.lineTo(0, -size * .7f);
        arrow.close();
        paint.setColor((buttons & bit) != 0 ? CORAL : CREAM);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(angle);
        canvas.drawPath(arrow, paint);
        canvas.restore();
    }

    private void drawRoundButton(Canvas canvas, float cx, float cy, String label, int bit, int color) {
        boolean pressed = (buttons & bit) != 0;
        paint.setColor(EDGE);
        canvas.drawCircle(cx, cy, buttonRadius + 5 * density, paint);
        paint.setColor(Color.BLACK);
        canvas.drawCircle(cx, cy + 3 * density, buttonRadius, paint);
        float offset = pressed ? 2 * density : 0;
        paint.setColor(pressed ? blend(color, BACKGROUND, .18f) : color);
        canvas.drawCircle(cx, cy + offset, buttonRadius, paint);
        paint.setColor(BACKGROUND);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(buttonRadius * .65f);
        Paint.FontMetrics font = paint.getFontMetrics();
        canvas.drawText(label, cx, cy + offset - (font.ascent + font.descent) / 2f, paint);
    }

    private void drawPill(Canvas canvas, RectF rect, String label, int bit) {
        boolean pressed = (buttons & bit) != 0;
        float radius = rect.height() / 2f;
        paint.setColor(Color.BLACK);
        canvas.save();
        canvas.translate(0, 2 * density);
        canvas.drawRoundRect(rect, radius, radius, paint);
        canvas.restore();
        paint.setColor(pressed ? CORAL : EDGE);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(Math.min(9 * density, rect.width() * .19f));
        paint.setColor(MUTED);
        canvas.drawText(label, rect.centerX(), rect.bottom + 16 * density, paint);
    }

    private static int blend(int first, int second, float amount) {
        return Color.rgb(Math.round(Color.red(first) * (1 - amount) + Color.red(second) * amount),
                Math.round(Color.green(first) * (1 - amount) + Color.green(second) * amount),
                Math.round(Color.blue(first) * (1 - amount) + Color.blue(second) * amount));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                releaseAll();
                requestFocus();
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                // Fall through: each pointer owns only its current buttons.
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerButtons.put(event.getPointerId(index), hitTest(event.getX(index), event.getY(index)));
                publishButtons(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    if (pointerButtons.indexOfKey(id) >= 0) {
                        pointerButtons.put(id, hitTest(event.getX(i), event.getY(i)));
                    }
                }
                publishButtons(true);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pointerButtons.delete(event.getPointerId(index));
                publishButtons(false);
                return true;
            case MotionEvent.ACTION_UP:
                pointerButtons.delete(event.getPointerId(index));
                publishButtons(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return true;
        }
    }

    private int hitTest(float x, float y) {
        boolean select = containsExpanded(selectBounds, x, y, 8 * density);
        boolean start = containsExpanded(startBounds, x, y, 8 * density);
        if (select || start) {
            if (select && start) {
                return Math.abs(x - selectBounds.centerX()) <= Math.abs(x - startBounds.centerX())
                        ? SELECT : START;
            }
            return select ? SELECT : START;
        }
        if (padBounds.contains(x, y)) {
            float dx = x - padX;
            float dy = y - padY;
            float ax = Math.abs(dx);
            float ay = Math.abs(dy);
            float dead = padRadius * .20f;
            int result = 0;
            if (ax > dead && ax >= ay * .45f) result |= dx < 0 ? LEFT : RIGHT;
            if (ay > dead && ay >= ax * .45f) result |= dy < 0 ? UP : DOWN;
            return result;
        }
        int result = 0;
        float touchRadius = buttonRadius + 6 * density;
        if (distanceSquared(x, y, aX, aY) <= touchRadius * touchRadius) result |= A;
        if (distanceSquared(x, y, bX, bY) <= touchRadius * touchRadius) result |= B;
        return result;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        AccessibilityManager manager = (AccessibilityManager) getContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isTouchExplorationEnabled()) {
            return super.dispatchHoverEvent(event);
        }
        int next = -1;
        if (event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
            int hit = hitTest(event.getX(), event.getY());
            for (int i = 0; i < NODE_BITS.length; i++) {
                if ((hit & NODE_BITS[i]) != 0) {
                    next = i + 1;
                    break;
                }
            }
        }
        if (next != hoveredNode) {
            if (next > 0) sendVirtualEvent(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (hoveredNode > 0) sendVirtualEvent(hoveredNode, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            hoveredNode = next;
        }
        return next > 0 || event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT;
    }

    private static float distanceSquared(float x, float y, float cx, float cy) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy;
    }

    private static boolean containsExpanded(RectF rect, float x, float y, float expansion) {
        return x >= rect.left - expansion && x <= rect.right + expansion
                && y >= rect.top - expansion && y <= rect.bottom + expansion;
    }

    private void publishButtons(boolean haptic) {
        int next = accessibleButtons;
        for (int i = 0; i < pointerButtons.size(); i++) next |= pointerButtons.valueAt(i);
        if (next == buttons) return;
        int newlyPressed = next & ~buttons;
        buttons = next;
        if (haptic && newlyPressed != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
        if (onButtonsChanged != null) onButtonsChanged.accept(next);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) releaseAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && pointerButtons != null) releaseAll();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return accessibility;
    }

    private void updateAccessiblePresses() {
        long now = SystemClock.uptimeMillis();
        long nextExpiry = Long.MAX_VALUE;
        accessibleButtons = 0;
        for (int i = 0; i < accessibleUntil.length; i++) {
            if (accessibleUntil[i] > now) {
                accessibleButtons |= NODE_BITS[i];
                nextExpiry = Math.min(nextExpiry, accessibleUntil[i]);
            }
        }
        publishButtons(false);
        removeCallbacks(finishAccessiblePress);
        if (nextExpiry != Long.MAX_VALUE) postDelayed(finishAccessiblePress, nextExpiry - now);
    }

    private void sendVirtualEvent(int id, int type) {
        ViewParent parent = getParent();
        if (parent == null) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.Button");
        event.setSource(this, id);
        event.setContentDescription(NODE_NAMES[id - 1]);
        parent.requestSendAccessibilityEvent(this, event);
    }

    private final class PadAccessibility extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == View.NO_ID) {
                AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(GamepadView.this);
                onInitializeAccessibilityNodeInfo(node);
                node.setClassName("android.view.ViewGroup");
                node.setClickable(false);
                node.setFocusable(false);
                node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                for (int i = 1; i <= NODE_NAMES.length; i++) node.addChild(GamepadView.this, i);
                return node;
            }
            if (virtualViewId < 1 || virtualViewId > NODE_NAMES.length) return null;
            int index = virtualViewId - 1;
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            node.setSource(GamepadView.this, virtualViewId);
            node.setParent(GamepadView.this);
            node.setPackageName(getContext().getPackageName());
            node.setClassName("android.widget.Button");
            node.setContentDescription(NODE_NAMES[index]);
            node.setClickable(true);
            node.setFocusable(true);
            node.setEnabled(isEnabled());
            node.setVisibleToUser(isShown());
            node.setAccessibilityFocused(accessibilityFocus == virtualViewId);
            Rect bounds = new Rect();
            nodeBounds[index].roundOut(bounds);
            node.setBoundsInParent(bounds);
            int[] location = new int[2];
            getLocationOnScreen(location);
            bounds.offset(location[0], location[1]);
            node.setBoundsInScreen(bounds);
            node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            node.addAction(accessibilityFocus == virtualViewId
                    ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            return node;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId < 1 || virtualViewId > NODE_NAMES.length) return false;
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled()) {
                accessibleUntil[virtualViewId - 1] = SystemClock.uptimeMillis() + 120;
                updateAccessiblePresses();
                sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocus == virtualViewId) return false;
                if (accessibilityFocus > 0) sendVirtualEvent(accessibilityFocus,
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                accessibilityFocus = virtualViewId;
                invalidate();
                sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    && accessibilityFocus == virtualViewId) {
                accessibilityFocus = -1;
                invalidate();
                sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                return true;
            }
            return false;
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocus > 0
                    ? createAccessibilityNodeInfo(accessibilityFocus) : null;
        }
    }
}
