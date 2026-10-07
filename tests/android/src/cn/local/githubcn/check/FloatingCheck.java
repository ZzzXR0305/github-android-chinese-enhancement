package cn.local.githubcn.check;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Target-process gesture, timer, page-key and rotation checks using synthetic content. */
public final class FloatingCheck {
    private static final String LOGIN = "com.github.android.auth.SimplifiedLoginActivity";
    private final Instrumentation runner;
    private final Context target;
    private final long deadline;
    private final Bundle evidence = new Bundle();
    private final AtomicInteger clicks = new AtomicInteger();
    private Activity activity;
    private Object controller;
    private View button, composeHeader;
    private TextView nativeHeader;
    private Class<?> floating, hooks, immersive;
    private SharedPreferences preferences;
    private boolean oldEnabled, savedX, savedY;
    private float oldX, oldY;
    private int oldOrientation;

    private FloatingCheck(Instrumentation runner, Context target, long deadline) {
        this.runner = runner; this.target = target;
        this.deadline = Math.min(deadline, SystemClock.elapsedRealtime() + 60000);
    }
    public static Bundle run(Instrumentation runner, Context target, long deadline) {
        return new FloatingCheck(runner, target, deadline).check();
    }

    private Bundle check() {
        long started = SystemClock.elapsedRealtime();
        Instrumentation.ActivityMonitor monitor = null;
        try {
            ClassLoader loader = target.getClassLoader();
            floating = Class.forName("cn.local.githubcn.FloatingButton", true, loader);
            hooks = Class.forName("cn.local.githubcn.Hooks", true, loader);
            immersive = Class.forName("cn.local.githubcn.Immersive", true, loader);
            oldEnabled = (Boolean) immersive.getMethod("enabled").invoke(null);
            preferences = target.getSharedPreferences("github_cn", Context.MODE_PRIVATE);
            savedX = preferences.contains("floating_x"); savedY = preferences.contains("floating_y");
            oldX = preferences.getFloat("floating_x", 1); oldY = preferences.getFloat("floating_y", -1);
            monitor = runner.addMonitor(LOGIN, null, false);
            main(() -> {
                try { immersive.getMethod("setEnabled", boolean.class).invoke(null, false); }
                catch (Exception error) { throw new IllegalStateException(error); }
                target.startActivity(new Intent().setClassName(target.getPackageName(), LOGIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            activity = monitor.waitForActivityWithTimeout(Math.max(1, Math.min(10000, remaining())));
            require(activity != null, "Floating fixture activity did not start");
            oldOrientation = activity.getRequestedOrientation();
            prepareWindow();
            waitForFocus();
            main(() -> { attach(); call("updatePage", String.class, "synthetic-A"); call("setForeground", boolean.class, true); });
            long entered = SystemClock.elapsedRealtime();
            delay(4000);
            require(!visible(), "First page showed floating button before five seconds");
            waitForVisible(true, "first-page five-second reveal");
            require(SystemClock.elapsedRealtime() - entered >= 4900, "First-page reveal was too early");
            evidence.putBoolean("floating_first_dwell", true);
            searchRouting();

            main(() -> { gesture(MotionEvent.ACTION_DOWN, 20, 20); gesture(MotionEvent.ACTION_UP, 21, 21); });
            require(clicks.get() == 1, "A tap within touch slop did not click exactly once");
            AtomicReference<float[]> before = new AtomicReference<>(), after = new AtomicReference<>();
            main(() -> {
                before.set(new float[]{button.getX(), button.getY()});
                float dx = button.getX() > decor().getWidth() / 2f ? -dp(120) : dp(120);
                float dy = button.getY() > decor().getHeight() / 2f ? -dp(80) : dp(80);
                gesture(MotionEvent.ACTION_DOWN, 20, 20);
                gesture(MotionEvent.ACTION_MOVE, 20 + dx, 20 + dy);
                gesture(MotionEvent.ACTION_UP, 20 + dx, 20 + dy);
                after.set(new float[]{button.getX(), button.getY()});
            });
            require(Math.abs(before.get()[0] - after.get()[0]) + Math.abs(before.get()[1] - after.get()[1]) > dp(10), "Dragging did not move floating button");
            require(clicks.get() == 1, "Dragging unexpectedly clicked floating button");
            main(() -> {
                gesture(MotionEvent.ACTION_DOWN, 20, 20);
                gesture(MotionEvent.ACTION_MOVE, 100000, 100000);
                gesture(MotionEvent.ACTION_UP, 100000, 100000);
                bounds();
            });
            require(clicks.get() == 1, "Dragging to screen edge unexpectedly clicked");
            float storedX = preferences.getFloat("floating_x", -1), storedY = preferences.getFloat("floating_y", -1);
            require(storedX >= 0 && storedX <= 1 && storedY >= 0 && storedY <= 1, "Drag position was not saved as bounded fractions");
            evidence.putBoolean("floating_drag_no_click", true);
            evidence.putBoolean("floating_edge_clamp", true);

            // An existing Activity must adopt a position saved while another page was foreground.
            main(() -> {
                call("setForeground", boolean.class, false);
                preferences.edit().putFloat("floating_x", .25f).putFloat("floating_y", .4f).commit();
                call("setForeground", boolean.class, true);
                call("restore");
                expectedPosition(.25f, .4f);
                preferences.edit().putFloat("floating_x", storedX).putFloat("floating_y", storedY).commit();
                call("dispose"); attach();
                call("updatePage", String.class, "synthetic-A"); call("setForeground", boolean.class, true); call("restore");
                expectedPosition(storedX, storedY);
            });
            evidence.putBoolean("floating_shared_position", true);
            evidence.putBoolean("floating_persisted_position", true);
            report("Floating button drag, click suppression, edge clamp and saved/shared position passed");

            captureState("floating_before_long_press");
            main(() -> gesture(MotionEvent.ACTION_DOWN, 20, 20));
            delay(ViewConfiguration.getLongPressTimeout() + 120);
            require(!visible(), "A long press did not hide floating button");
            main(() -> gesture(MotionEvent.ACTION_UP, 20, 20));
            captureState("floating_after_long_press");
            long hidden = SystemClock.elapsedRealtime();
            delay(1450);
            main(() -> call("updatePage", String.class, "synthetic-A"));
            delay(100);
            require(!visible(), "Same-page polling immediately undid long-press hiding");
            waitForVisible(true, "long-press five-second reappearance");
            require(SystemClock.elapsedRealtime() - hidden >= 4700, "Long-press hiding ended before its five-second dwell");
            require(clicks.get() == 1, "Long press unexpectedly clicked floating button");
            evidence.putBoolean("floating_long_press_dwell", true);

            main(() -> call("updatePage", String.class, "synthetic-B"));
            require(!visible(), "New page did not hide floating button and reset timer");
            delay(4000);
            require(!visible(), "New page showed floating button too early");
            waitForVisible(true, "new-page dwell");
            evidence.putBoolean("floating_page_dwell", true);
            main(() -> call("setForeground", boolean.class, false));
            delay(5200);
            require(!visible(), "Background activity showed floating button");
            main(() -> call("setForeground", boolean.class, true));
            long resumed = SystemClock.elapsedRealtime();
            require(!visible(), "Background time counted toward foreground dwell");
            waitForVisible(true, "foreground dwell after background");
            require(SystemClock.elapsedRealtime() - resumed >= 4900, "Resume revealed floating button too early");
            main(() -> { call("hide"); call("restore"); });
            require(visible(), "Menu restore did not show floating button immediately");
            evidence.putBoolean("floating_background_dwell", true);
            evidence.putBoolean("floating_menu_restore", true);
            report("Floating button first-page, long-press, page-change and background five-second timers passed");

            pageKeys(loader);
            int oldConfig = activity.getResources().getConfiguration().orientation;
            int requested = oldConfig == Configuration.ORIENTATION_LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            int expected = oldConfig == Configuration.ORIENTATION_LANDSCAPE ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
            rotate(requested, expected);
            main(() -> {
                attach(); call("updatePage", String.class, "synthetic-rotated"); call("setForeground", boolean.class, true); call("restore");
                bounds(); expectedPosition(storedX, storedY);
            });
            evidence.putBoolean("floating_rotation_bounds", true);
            evidence.putBoolean("floating_passed", true);
            report("Native/Fragment/Compose page keys and real rotation bounds passed");
        } catch (Throwable error) {
            captureState("floating_failure_state");
            evidence.putBoolean("floating_passed", false);
            evidence.putString("floating_failure", error.toString());
            while (error.getCause() != null) error = error.getCause();
            evidence.putString("floating_failure_cause", error.toString());
        } finally {
            if (monitor != null) runner.removeMonitor(monitor);
            try {
                main(() -> {
                    if (controller != null) call("dispose");
                    if (composeHeader != null && composeHeader.getParent() instanceof ViewGroup) ((ViewGroup) composeHeader.getParent()).removeView(composeHeader);
                    if (nativeHeader != null && nativeHeader.getParent() instanceof ViewGroup) ((ViewGroup) nativeHeader.getParent()).removeView(nativeHeader);
                    if (preferences != null) {
                        SharedPreferences.Editor edit = preferences.edit();
                        if (savedX) edit.putFloat("floating_x", oldX); else edit.remove("floating_x");
                        if (savedY) edit.putFloat("floating_y", oldY); else edit.remove("floating_y");
                        edit.commit();
                    }
                    if (immersive != null) try { immersive.getMethod("setEnabled", boolean.class).invoke(null, oldEnabled); } catch (Exception error) { throw new IllegalStateException(error); }
                    if (activity != null) { activity.setRequestedOrientation(oldOrientation); activity.finish(); }
                });
            } catch (Throwable cleanup) { evidence.putString("floating_cleanup_error", cleanup.toString()); }
            evidence.putLong("floating_elapsed_ms", SystemClock.elapsedRealtime() - started);
        }
        return evidence;
    }

    private void pageKeys(ClassLoader loader) throws Exception {
        AtomicReference<String> baseline = new AtomicReference<>(), changed = new AtomicReference<>();
        main(() -> {
            nativeHeader = new TextView(activity);
            nativeHeader.setId(target.getResources().getIdentifier("toolbar_title", "id", target.getPackageName()));
            nativeHeader.setText("Synthetic native title Alpha");
            decor().addView(nativeHeader, new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP));
        });
        delay(150);
        main(() -> { baseline.set(pageKey()); nativeHeader.setText("Synthetic native title Beta"); changed.set(pageKey()); });
        require(!baseline.get().equals(changed.get()) && changed.get().contains("Synthetic native title Beta"), "Native toolbar title did not change page key");
        int tagId = target.getResources().getIdentifier("fragment_container_view_tag", "id", target.getPackageName());
        require(tagId != 0, "Original fragment view tag resource is missing");
        main(() -> { baseline.set(pageKey()); nativeHeader.setTag(tagId, new Object()); changed.set(pageKey()); });
        require(!baseline.get().equals(changed.get()), "Fragment root instance did not change page key");
        Class<?> viewClass = Class.forName("androidx.compose.ui.platform.ComposeView", true, loader);
        main(() -> {
            try {
                composeHeader = (View) viewClass.getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
                composeHeader.setId(target.getResources().getIdentifier("pull_request_title", "id", target.getPackageName()));
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(100), Gravity.TOP); params.topMargin = dp(60);
                decor().addView(composeHeader, params);
                composeTitle(loader, "Synthetic Compose title Alpha");
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        waitForKey("Synthetic Compose title Alpha");
        main(() -> { baseline.set(pageKey()); composeTitle(loader, "Synthetic Compose title Beta"); });
        waitForKey("Synthetic Compose title Beta");
        main(() -> changed.set(pageKey()));
        require(!baseline.get().equals(changed.get()), "Compose title did not change page key");
        evidence.putBoolean("floating_native_title_key", true);
        evidence.putBoolean("floating_fragment_key", true);
        evidence.putBoolean("floating_compose_title_key", true);
    }

    private void searchRouting() throws Exception {
        boolean saved = preferences.contains("search_redirect"), old = preferences.getBoolean("search_redirect", false);
        Instrumentation.ActivityMonitor route = runner.addMonitor("cn.local.githubcn.SearchActivity", new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            main(() -> { preferences.edit().remove("search_redirect").commit(); searchEntry(); });
            delay(250);
            require(route.getHits() == 0, "Original search redirected with its setting absent/default disabled");
            main(() -> {
                preferences.edit().putBoolean("search_redirect", true).commit();
                searchEntry();
                preferences.edit().putBoolean("search_redirect", false).commit();
            });
            delay(250);
            require(route.getHits() == 0, "A queued search redirect ignored the setting being disabled");
            main(() -> { preferences.edit().putBoolean("search_redirect", true).commit(); searchEntry(); });
            delay(250);
            require(route.getHits() == 1, "Opt-in original search did not request the enhanced search screen once");
            evidence.putBoolean("search_redirect_default_disabled", true);
            evidence.putBoolean("search_redirect_queued_cancel", true);
            evidence.putBoolean("search_redirect_opt_in", true);
            report("Original-search routing is disabled by default and respects explicit enable/disable settings");
        } finally {
            runner.removeMonitor(route);
            main(() -> { SharedPreferences.Editor edit = preferences.edit(); if (saved) edit.putBoolean("search_redirect", old); else edit.remove("search_redirect"); edit.commit(); });
        }
    }
    private void searchEntry() {
        try { hooks.getMethod("searchEntry", View.class).invoke(null, button); }
        catch (Exception error) { throw new IllegalStateException("Synthetic search-entry check failed", error); }
    }

    private void composeTitle(ClassLoader loader, String title) {
        try {
            Class<?> function = Class.forName("pik", true, loader), composer = Class.forName("ekk", true, loader), modifier = Class.forName("b1r", true, loader);
            Method render = Class.forName("xrz", true, loader).getMethod("a", int.class, int.class, composer, modifier, String.class);
            Object unit = Class.forName("g590", true, loader).getField("a").get(null);
            Object content = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
                if (method.getName().equals("q")) { render.invoke(null, 0, 1, args[0], null, "<p>" + title + "</p>"); return unit; }
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return "Synthetic Compose title";
            });
            composeHeader.getClass().getMethod("setContent", function).invoke(composeHeader, content);
        } catch (Exception error) { throw new IllegalStateException("Compose title fixture failed", error); }
    }

    private void prepareWindow() throws Exception {
        main(() -> prepareWindow(activity));
    }
    private void prepareWindow(Activity value) {
        value.setShowWhenLocked(true); value.setTurnScreenOn(true);
        // Direct synthetic dispatchTouchEvent does not refresh system user activity.
        value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void rotate(int requested, int expected) throws Exception {
        final Activity previous = activity;
        final int task = previous.getTaskId();
        final AtomicReference<Activity> recreated = new AtomicReference<>();
        final Application application = (Application) target.getApplicationContext();
        Application.ActivityLifecycleCallbacks observer = new Application.ActivityLifecycleCallbacks() {
            private void capture(Activity value) {
                if (value != previous && LOGIN.equals(value.getClass().getName()) && value.getTaskId() == task) {
                    prepareWindow(value);
                    recreated.set(value);
                }
            }
            public void onActivityCreated(Activity value, Bundle state) { capture(value); }
            public void onActivityResumed(Activity value) { capture(value); }
            public void onActivityStarted(Activity value) {}
            public void onActivityPaused(Activity value) {}
            public void onActivityStopped(Activity value) {}
            public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            public void onActivityDestroyed(Activity value) {}
        };
        evidence.putInt("floating_rotation_old_identity", System.identityHashCode(previous));
        try {
            main(() -> { application.registerActivityLifecycleCallbacks(observer); previous.setRequestedOrientation(requested); });
            long end = Math.min(deadline, SystemClock.elapsedRealtime() + 15000);
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            while (SystemClock.elapsedRealtime() < end) {
                main(() -> {
                    Activity candidate = recreated.get();
                    if (candidate == null || candidate == previous || candidate.isDestroyed() || candidate.isFinishing()) return;
                    activity = candidate;
                    ready.set(candidate.getResources().getConfiguration().orientation == expected &&
                        decor().isAttachedToWindow() && decor().hasWindowFocus() && decor().getWidth() > 0);
                });
                if (ready.get()) {
                    require(previous.isDestroyed(), "Rotation did not destroy the previous synthetic Activity");
                    evidence.putBoolean("floating_rotation_recreated", true);
                    evidence.putBoolean("floating_rotation_old_destroyed", true);
                    evidence.putInt("floating_rotation_new_identity", System.identityHashCode(activity));
                    evidence.putInt("floating_rotation_orientation", expected);
                    return;
                }
                delay(100);
            }
            evidence.putBoolean("floating_rotation_old_destroyed", previous.isDestroyed());
            evidence.putBoolean("floating_rotation_candidate_captured", recreated.get() != null);
            captureState("floating_rotation_wait_state");
            throw new AssertionError(recreated.get() == null ? "Rotation did not create a new synthetic Activity in the same task" : "Recreated synthetic Activity did not obtain the requested orientation and window focus");
        } finally {
            main(() -> application.unregisterActivityLifecycleCallbacks(observer));
        }
    }
    private void waitForFocus() throws Exception {
        long end = Math.min(deadline, SystemClock.elapsedRealtime() + 8000);
        AtomicReference<Boolean> focus = new AtomicReference<>(false);
        while (SystemClock.elapsedRealtime() < end) {
            main(() -> focus.set(decor().hasWindowFocus() && decor().getWidth() > 0));
            if (focus.get()) return;
            delay(100);
        }
        throw new AssertionError("Synthetic fixture window did not receive focus");
    }
    private void waitForKey(String title) throws Exception {
        long end = Math.min(deadline, SystemClock.elapsedRealtime() + 4000);
        AtomicReference<String> key = new AtomicReference<>("");
        while (SystemClock.elapsedRealtime() < end) {
            main(() -> key.set(pageKey()));
            if (key.get().contains(title)) return;
            delay(100);
        }
        throw new AssertionError("Synthetic Compose title was not present in its page key: " + title);
    }
    private String pageKey() {
        try { return (String) hooks.getMethod("pageKey", Activity.class).invoke(null, activity); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void attach() {
        try {
            controller = floating.getMethod("attach", Activity.class, Runnable.class).invoke(null, activity, (Runnable) clicks::incrementAndGet);
            require(controller != null, "FloatingButton.attach returned null");
            button = (View) floating.getMethod("view").invoke(controller);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void call(String name) { call(name, null, null); }
    private void call(String name, Class<?> type, Object value) {
        try { if (type == null) floating.getMethod(name).invoke(controller); else floating.getMethod(name, type).invoke(controller, value); }
        catch (Exception error) { throw new IllegalStateException(name + " failed", error); }
    }
    private void gesture(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { button.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private boolean visible() throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        main(() -> result.set(button.getVisibility() == View.VISIBLE));
        return result.get();
    }
    private void waitForVisible(boolean expected, String stage) throws Exception {
        long end = Math.min(deadline, SystemClock.elapsedRealtime() + 8000);
        while (SystemClock.elapsedRealtime() < end) { if (visible() == expected) return; delay(100); }
        evidence.putString("floating_timeout_stage", stage);
        captureState("floating_timeout_state");
        throw new AssertionError(stage + " timed out");
    }
    private void captureState(String name) {
        Bundle state = new Bundle();
        try {
            main(() -> {
                long now = SystemClock.elapsedRealtime();
                state.putLong("elapsed_realtime", now);
                state.putBoolean("interactive", ((PowerManager) target.getSystemService(Context.POWER_SERVICE)).isInteractive());
                if (activity != null) {
                    state.putInt("activity_identity", System.identityHashCode(activity));
                    state.putBoolean("activity_destroyed", activity.isDestroyed());
                    state.putBoolean("activity_finishing", activity.isFinishing());
                    state.putBoolean("root_attached", decor().isAttachedToWindow());
                    state.putBoolean("window_focus", decor().hasWindowFocus());
                    state.putBoolean("keep_screen_on", (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
                }
                if (button != null) { state.putBoolean("button_attached", button.isAttachedToWindow()); state.putInt("visibility", button.getVisibility()); }
                if (controller != null) {
                    try {
                        for (String field : new String[]{"foreground", "disposed", "dragging", "held"}) {
                            java.lang.reflect.Field value = floating.getDeclaredField(field); value.setAccessible(true);
                            state.putBoolean(field, value.getBoolean(controller));
                        }
                        java.lang.reflect.Field timerField = floating.getDeclaredField("stay"); timerField.setAccessible(true);
                        Object timer = timerField.get(controller);
                        java.lang.reflect.Field timerForeground = timer.getClass().getDeclaredField("foreground"); timerForeground.setAccessible(true);
                        java.lang.reflect.Field since = timer.getClass().getDeclaredField("since"); since.setAccessible(true);
                        state.putBoolean("timer_foreground", timerForeground.getBoolean(timer));
                        state.putLong("dwell_elapsed", now - since.getLong(timer));
                        state.putLong("timer_remaining", (Long) timer.getClass().getMethod("remaining", long.class).invoke(timer, now));
                        state.putBoolean("timer_visible", (Boolean) timer.getClass().getMethod("visible", long.class).invoke(timer, now));
                    } catch (Exception error) { throw new IllegalStateException("Synthetic floating diagnostics failed", error); }
                }
            });
        } catch (Throwable error) { state.putString("capture_error", error.toString()); }
        evidence.putString(name, state.toString());
    }
    private float[] area() {
        WindowInsets insets = decor().getRootWindowInsets();
        float left = dp(8), top = dp(8), right = decor().getWidth() - dp(8) - dp(64), bottom = decor().getHeight() - dp(8) - dp(48);
        if (insets != null) { left += insets.getSystemWindowInsetLeft(); top += insets.getSystemWindowInsetTop(); right -= insets.getSystemWindowInsetRight(); bottom -= insets.getSystemWindowInsetBottom(); }
        return new float[]{left, top, Math.max(left, right), Math.max(top, bottom)};
    }
    private void bounds() { float[] area = area(); require(button.getX() >= area[0] - 1 && button.getY() >= area[1] - 1 && button.getX() <= area[2] + 1 && button.getY() <= area[3] + 1, "Floating button lies outside the safe screen bounds"); }
    private void expectedPosition(float x, float y) { float[] area = area(); require(Math.abs(button.getX() - (area[0] + x * (area[2] - area[0]))) < 2 && Math.abs(button.getY() - (area[1] + y * (area[3] - area[1]))) < 2, "Floating button did not read the saved normalized position"); }
    private FrameLayout decor() { return (FrameLayout) activity.getWindow().getDecorView(); }
    private int dp(int value) { return Math.round(value * target.getResources().getDisplayMetrics().density); }
    private long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    private void delay(long milliseconds) throws Exception { require(remaining() >= milliseconds, "Floating check reached its deadline"); Thread.sleep(milliseconds); }
    private void main(Runnable work) throws Exception { AtomicReference<Throwable> failure = new AtomicReference<>(); runner.runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } }); if (failure.get() != null) throw new Exception("Floating main-thread check failed", failure.get()); }
    private void report(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); runner.sendStatus(0, status); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
