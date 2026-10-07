package cn.local.githubcn;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

/** An in-app view, with ordinary Android gestures and a foreground-only dwell timer. */
public final class FloatingButton {
    public static final String TAG = "github-cn-menu";
    public static final int CONTROLLER_TAG = 0x6f010102;
    private final FrameLayout root;
    private final TextView button;
    private final SharedPreferences settings;
    private final PageStay stay = new PageStay();
    private final int slop;
    private float downX, downY, startX, startY, fractionX, fractionY;
    private boolean foreground, dragging, held, disposed;
    private final Runnable reveal = this::render;
    private final Runnable hold = this::longPress;
    private final ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> render();
    private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> position();

    private FloatingButton(Activity activity, FrameLayout root, Runnable click) {
        this.root = root;
        settings = activity.getSharedPreferences("github_cn", Context.MODE_PRIVATE);
        slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        fractionX = settings.getFloat("floating_x", 1);
        fractionY = settings.getFloat("floating_y", -1);
        button = new TextView(activity);
        button.setTag(TAG);
        button.setTag(CONTROLLER_TAG, this);
        button.setText("译 / 搜");
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("翻译与增强搜索，可拖动，长按隐藏");
        button.setClickable(true);
        button.setLongClickable(true);
        button.setFocusable(true);
        button.setVisibility(View.GONE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(9, 105, 218));
        background.setCornerRadius(dp(24));
        button.setBackground(background);
        button.setElevation(dp(5));
        button.setOnClickListener(v -> click.run());
        button.setOnLongClickListener(v -> { hide(); return true; });
        button.setOnTouchListener((v, event) -> touch(event));
        root.addView(button, new FrameLayout.LayoutParams(dp(64), dp(48), Gravity.TOP | Gravity.LEFT));
        root.addOnLayoutChangeListener(layout);
        root.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
        root.post(this::position);
    }

    /** Caller attaches only to a resumed activity; attach itself does not start the timer. */
    public static FloatingButton attach(Activity activity, Runnable click) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return null;
        View existing = decor.findViewWithTag(TAG);
        if (existing != null && existing.getTag(CONTROLLER_TAG) instanceof FloatingButton)
            return (FloatingButton) existing.getTag(CONTROLLER_TAG);
        return new FloatingButton(activity, (FrameLayout) decor, click);
    }
    public View view() { return button; }
    public void updatePage(String page) { stay.visit(page, SystemClock.elapsedRealtime()); render(); }
    public void setForeground(boolean value) {
        if (value && !foreground) {
            fractionX = settings.getFloat("floating_x", 1);
            fractionY = settings.getFloat("floating_y", -1);
            position();
        }
        foreground = value;
        render();
    }
    public void hide() { button.removeCallbacks(hold); button.setPressed(false); stay.hide(SystemClock.elapsedRealtime()); render(); }
    public void restore() { stay.restore(SystemClock.elapsedRealtime()); render(); }
    private void longPress() { held = true; button.performLongClick(); }
    public void dispose() {
        disposed = true;
        button.removeCallbacks(reveal);
        button.removeCallbacks(hold);
        root.removeOnLayoutChangeListener(layout);
        if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
        root.removeView(button);
    }

    private void render() {
        if (disposed) return;
        button.removeCallbacks(reveal);
        long now = SystemClock.elapsedRealtime();
        boolean active = foreground && root.isAttachedToWindow() && root.hasWindowFocus();
        stay.foreground(active, now);
        boolean visible = stay.visible(now);
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) position();
        else if (active) button.postDelayed(reveal, Math.max(1, stay.remaining(now)));
        if (!visible) { button.removeCallbacks(hold); button.setPressed(false); }
        if (!active) held = true;
    }

    private boolean touch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY(); startX = button.getX(); startY = button.getY();
                dragging = held = false;
                button.setPressed(true);
                button.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy > slop * slop) {
                    dragging = true; button.removeCallbacks(hold); button.setPressed(false);
                }
                if (dragging && !held) move(startX + dx, startY + dy);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                held = true; button.removeCallbacks(hold); button.setPressed(false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                button.removeCallbacks(hold); button.setPressed(false);
                if (dragging && !held) settings.edit().putFloat("floating_x", fractionX).putFloat("floating_y", fractionY).apply();
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragging && !held) button.performClick();
                return true;
            default: return true;
        }
    }

    private Rect bounds() {
        int left = dp(8), top = dp(8), right = root.getWidth() - dp(8) - dp(64), bottom = root.getHeight() - dp(8) - dp(48);
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            left += insets.getSystemWindowInsetLeft(); top += insets.getSystemWindowInsetTop();
            right -= insets.getSystemWindowInsetRight(); bottom -= insets.getSystemWindowInsetBottom();
        }
        return new Rect(left, top, Math.max(left, right), Math.max(top, bottom));
    }
    private void position() {
        if (disposed || root.getWidth() == 0 || root.getHeight() == 0) return;
        Rect area = bounds();
        if (fractionY < 0) fractionY = Math.max(0, 1 - dp(96) / (float) Math.max(1, area.height()));
        button.setX(area.left + clamp(fractionX, 0, 1) * area.width());
        button.setY(area.top + clamp(fractionY, 0, 1) * area.height());
    }
    private void move(float x, float y) {
        Rect area = bounds();
        x = clamp(x, area.left, area.right); y = clamp(y, area.top, area.bottom);
        fractionX = area.width() == 0 ? 0 : (x - area.left) / area.width();
        fractionY = area.height() == 0 ? 0 : (y - area.top) / area.height();
        button.setX(x); button.setY(y);
    }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private int dp(int value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }
}
