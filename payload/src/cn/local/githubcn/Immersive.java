package cn.local.githubcn;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Called by the patched GitHub WebView constructor/load hooks in the same process. */
public final class Immersive {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<WebView, Session> SESSIONS = new WeakHashMap<>();
    private static final Uri PROVIDER = Uri.parse("content://cn.local.repochinese.translation");
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(24));
    private static volatile boolean active = true;
    private static volatile String language = "zh";
    private static volatile long configuration;
    private static String script;

    private Immersive() {}
    static void execute(Runnable task) { WORK.execute(task); }
    public static boolean enabled() { return active; }
    public static String target() { return language; }
    public static synchronized void setEnabled(boolean value) {
        if (active != value) { active = value; configuration++; NativeText.refresh(); }
    }
    public static synchronized void setTarget(String value) {
        if (value == null) return;
        value = value.toLowerCase(Locale.ROOT);
        if (!LanguageOptions.supports(value)) return;
        if (!language.equals(value)) { language = value; configuration++; NativeText.refresh(); }
    }
    public static void attach(final WebView web) {
        if (web == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> attach(web));
            return;
        }
        if (SESSIONS.containsKey(web)) return;
        try {
            if (script == null) {
                StringBuilder text = new StringBuilder();
                try (InputStream stream = web.getContext().getAssets().open("github-immersive.js");
                     InputStreamReader reader = new InputStreamReader(stream, "UTF-8")) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                }
                script = text.toString();
            }
            Session session = new Session(web);
            SESSIONS.put(web, session);
            MAIN.postDelayed(session, 300);
        } catch (Exception ignored) {
            // A missing payload asset must never prevent the original app from opening.
        }
    }

    private static final class Session implements Runnable {
        private final WeakReference<WebView> view;
        private final ContentResolver resolver;
        private volatile long generation;
        private String document = "";
        private boolean reportedError;

        Session(WebView web) {
            view = new WeakReference<>(web);
            resolver = web.getContext().getApplicationContext().getContentResolver();
        }
        @Override public void run() {
            WebView web = view.get();
            if (web == null) return;
            boolean enabled;
            String target;
            long config;
            synchronized (Immersive.class) {
                enabled = active; target = language; config = configuration;
            }
            final long expectedConfig = config;
            try {
                // Rechecking installs again after navigation, including loadDataWithBaseURL.
                String command = "(function(){" + script + "\nreturn window.__ghcnImmersive.poll("
                    + enabled + "," + JSONObject.quote(target) + "," + config + ");})()";
                web.evaluateJavascript(command, raw -> collect(raw, expectedConfig));
            } catch (Exception ignored) { generation++; }
            MAIN.postDelayed(this, 900);
        }
        private void collect(String raw, long config) {
            if (configuration != config || raw == null || raw.equals("null")) return;
            try {
                JSONObject batch = new JSONObject(raw);
                if (!batch.optBoolean("allowed")) { generation++; document = ""; return; }
                String page = batch.getString("document");
                if (!page.equals(document)) { generation++; document = page; }
                long currentGeneration = generation;
                long epoch = batch.getLong("epoch");
                JSONArray items = batch.optJSONArray("items");
                if (items == null) return;
                String target = batch.getString("target");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    int id = item.getInt("id");
                    String source = item.getString("text");
                    try {
                        WORK.execute(() -> translate(page, epoch, id, source, target, config, currentGeneration));
                    } catch (RuntimeException full) {
                        finish(page, epoch, id, null, "busy", config, currentGeneration);
                    }
                }
            } catch (Exception ignored) { /* Empty or replaced documents are retried next tick. */ }
        }
        private void translate(String page, long epoch, int id, String text, String target,
                               long config, long expectedGeneration) {
            if (configuration != config || generation != expectedGeneration || view.get() == null) return;
            String translated = null, error = null;
            try {
                Bundle extras = new Bundle();
                extras.putString("text", text);
                extras.putString("target", target);
                Bundle answer = resolver.call(PROVIDER, "translate", null, extras);
                if (answer == null) error = "empty provider result";
                else {
                    translated = answer.getString("text");
                    error = answer.getString("error");
                    if (translated == null && error == null) error = "missing translation";
                }
            } catch (Exception failure) { error = failure.getClass().getSimpleName(); }
            finish(page, epoch, id, translated, error, config, expectedGeneration);
        }
        private void finish(String page, long epoch, int id, String translated, String error,
                            long config, long expectedGeneration) {
            final String result = translated, problem = error;
            MAIN.post(() -> {
                WebView web = view.get();
                if (web == null || configuration != config || generation != expectedGeneration) return;
                if (problem != null && !reportedError) {
                    reportedError = true;
                    android.widget.Toast.makeText(web.getContext(), "翻译暂不可用，请在仓库中文助手中检查模型是否就绪", android.widget.Toast.LENGTH_LONG).show();
                    android.util.Log.w("GitHubCN", "Translation provider error: " + problem);
                }
                String command = "window.__ghcnImmersive && window.__ghcnImmersive.apply("
                    + JSONObject.quote(page) + "," + epoch + "," + id + ","
                    + (result == null ? "null" : JSONObject.quote(result)) + ","
                    + (problem == null ? "null" : JSONObject.quote(problem)) + ")";
                try { web.evaluateJavascript(command, null); } catch (Exception ignored) {}
            });
        }
    }
}
