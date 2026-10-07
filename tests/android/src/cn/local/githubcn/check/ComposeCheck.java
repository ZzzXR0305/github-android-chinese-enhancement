package cn.local.githubcn.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Exercises the original repository-description renderer with synthetic text only. */
public final class ComposeCheck {
    private static final String DESCRIPTION = "This application helps developers browse open source projects.";
    private static final String HTML = "<p>" + DESCRIPTION + "</p>";
    private static final String LOGIN = "com.github.android.auth.SimplifiedLoginActivity";
    private final Instrumentation runner;
    private final Context target;
    private final long deadline;
    private final Bundle evidence = new Bundle();
    private final AtomicReference<Throwable> renderFailure = new AtomicReference<>();
    private Class<?> immersive;
    private Object textKey;
    private Activity activity;
    private View compose;
    private boolean oldEnabled;
    private String oldTarget;

    private ComposeCheck(Instrumentation runner, Context target, long deadline) {
        this.runner = runner;
        this.target = target;
        this.deadline = Math.min(deadline, SystemClock.elapsedRealtime() + 45000);
    }

    /** deadline is the caller's absolute SystemClock.elapsedRealtime deadline. */
    public static Bundle run(Instrumentation runner, Context target, long deadline) {
        return new ComposeCheck(runner, target, deadline).check();
    }

    private Bundle check() {
        long started = SystemClock.elapsedRealtime();
        Instrumentation.ActivityMonitor monitor = null;
        try {
            ClassLoader loader = target.getClassLoader();
            immersive = Class.forName("cn.local.githubcn.Immersive", true, loader);
            oldEnabled = (Boolean) immersive.getMethod("enabled").invoke(null);
            oldTarget = (String) immersive.getMethod("target").invoke(null);
            Class<?> function = Class.forName("pik", true, loader);
            Class<?> composer = Class.forName("ekk", true, loader);
            Class<?> modifier = Class.forName("b1r", true, loader);
            Class<?> viewClass = Class.forName("androidx.compose.ui.platform.ComposeView", true, loader);
            Method render = Class.forName("xrz", true, loader).getMethod("a", int.class, int.class, composer, modifier, String.class);
            Object unit = Class.forName("g590", true, loader).getField("a").get(null);
            textKey = Class.forName("gk30", true, loader).getField("C").get(null);
            Object content = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
                if (method.getName().equals("q")) {
                    try { render.invoke(null, 0, 1, args[0], null, HTML); }
                    catch (Throwable error) { renderFailure.compareAndSet(null, error); throw error; }
                    return unit;
                }
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "Synthetic repository description check";
                throw new UnsupportedOperationException(method.toString());
            });
            monitor = runner.addMonitor(LOGIN, null, false);
            main(() -> {
                setting("setEnabled", boolean.class, true);
                setting("setTarget", String.class, "en");
                target.startActivity(new Intent().setClassName(target.getPackageName(), LOGIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            activity = monitor.waitForActivityWithTimeout(Math.max(1, Math.min(10000, remaining())));
            require(activity != null, "Synthetic Compose host activity did not start");
            main(() -> {
                // Show synthetic content while locked; this neither unlocks nor authenticates the phone.
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
                try {
                    compose = (View) viewClass.getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
                    compose.setId(View.generateViewId());
                    compose.setBackgroundColor(0xffffffff);
                    compose.setMinimumHeight((int) (160 * target.getResources().getDisplayMetrics().density));
                    viewClass.getMethod("setContent", function).invoke(compose, content);
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    decor.addView(compose, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } catch (Exception error) { throw new IllegalStateException("Original ComposeView fixture failed", error); }
            });
            evidence.putString("compose_baseline", waitFor("original Compose description", text -> DESCRIPTION.equals(text.trim())));
            main(() -> setting("setTarget", String.class, "zh"));
            String translated = waitFor("Compose description Chinese recomposition", text -> chinese(text) && !DESCRIPTION.equals(text.trim()));
            evidence.putString("compose_translated", translated);
            evidence.putBoolean("compose_description_translated", true);
            report("Original xrz repository renderer recomposed its Text semantics in Chinese: " + translated);
            main(() -> setting("setTarget", String.class, "en"));
            evidence.putString("compose_restored", waitFor("Compose original restoration", text -> DESCRIPTION.equals(text.trim())));
            evidence.putBoolean("compose_en_restored", true);
            evidence.putBoolean("compose_original_renderer", true);
            evidence.putBoolean("compose_passed", true);
            report("Original Compose repository description restored after selecting English");
        } catch (Throwable error) {
            evidence.putBoolean("compose_passed", false);
            evidence.putString("compose_failure", error.toString());
            while (error.getCause() != null) error = error.getCause();
            evidence.putString("compose_failure_cause", error.toString());
        } finally {
            if (monitor != null) runner.removeMonitor(monitor);
            try {
                main(() -> {
                    if (compose != null && compose.getParent() instanceof ViewGroup) ((ViewGroup) compose.getParent()).removeView(compose);
                    if (immersive != null && oldTarget != null) {
                        setting("setTarget", String.class, oldTarget);
                        setting("setEnabled", boolean.class, oldEnabled);
                    }
                    if (activity != null) activity.finish();
                });
            } catch (Throwable cleanup) { evidence.putString("compose_cleanup_error", cleanup.toString()); }
            evidence.putLong("compose_elapsed_ms", SystemClock.elapsedRealtime() - started);
        }
        return evidence;
    }

    private String waitFor(String stage, Predicate<String> condition) throws Exception {
        AtomicReference<String> last = new AtomicReference<>("");
        while (remaining() > 0) {
            if (renderFailure.get() != null) throw new Exception("Original xrz render failed", renderFailure.get());
            main(() -> {
                try {
                    View owner = semanticsView(compose);
                    if (owner == null) { last.set(""); return; }
                    Object semantics = owner.getClass().getMethod("getSemanticsOwner").invoke(owner);
                    Object root = semantics.getClass().getMethod("a").invoke(semantics);
                    StringBuilder text = new StringBuilder();
                    collect(root, text, 0);
                    last.set(text.toString());
                } catch (Exception error) { throw new IllegalStateException("Original Compose Text semantics could not be read", error); }
            });
            if (condition.test(last.get())) return last.get();
            Thread.sleep(Math.min(200, remaining()));
        }
        throw new AssertionError(stage + " timed out; synthetic Text semantics: " + last.get());
    }

    private View semanticsView(View view) {
        if (view == null) return null;
        try { view.getClass().getMethod("getSemanticsOwner"); return view; }
        catch (NoSuchMethodException absent) {}
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View owner = semanticsView(group.getChildAt(i));
                if (owner != null) return owner;
            }
        }
        return null;
    }

    private void collect(Object node, StringBuilder output, int depth) throws Exception {
        if (depth > 30) throw new IllegalStateException("Unexpected synthetic semantics depth");
        Object configuration = node.getClass().getMethod("n").invoke(node);
        Object properties = configuration.getClass().getField("p").get(configuration);
        Object texts = properties.getClass().getMethod("g", Object.class).invoke(properties, textKey);
        if (texts instanceof List) for (Object text : (List<?>) texts) {
            if (output.length() > 0) output.append('\n');
            output.append(text.toString());
        }
        Object children = node.getClass().getMethod("i", boolean.class, boolean.class).invoke(node, true, true);
        for (Object child : (List<?>) children) collect(child, output, depth + 1);
    }

    private void setting(String method, Class<?> type, Object value) {
        try { immersive.getMethod(method, type).invoke(null, value); }
        catch (Exception error) { throw new IllegalStateException(method + " failed", error); }
    }

    private void main(Runnable work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner.runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new Exception("Compose main-thread operation failed", failure.get());
    }
    private void report(String message) { Bundle status = new Bundle(); status.putString("stream", message + "\n"); runner.sendStatus(0, status); }
    private long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    private static boolean chinese(String text) { return text != null && text.matches("(?s).*[\\u3400-\\u9fff].*"); }
    private static void require(boolean success, String message) { if (!success) throw new AssertionError(message); }
}
