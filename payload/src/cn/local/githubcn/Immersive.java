package cn.local.githubcn;

import android.content.Context;
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
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(24));
    private static volatile boolean active = true;
    private static volatile String language = "zh";
    private static volatile boolean dual;
    private static volatile long configuration;
    private static String script;
    private static final android.util.LruCache<String, String> PAGES = new android.util.LruCache<String, String>(512 * 1024) {
        @Override protected int sizeOf(String key, String value) { return key.length() + value.length(); }
    };

    private Immersive() {}
    static void execute(Runnable task) { WORK.execute(task); }
    public static boolean enabled() { return active; }
    public static String target() { return language; }
    public static boolean bilingual() { return dual; }
    public static synchronized void setBilingual(boolean value) {
        if (dual != value) { dual = value; configuration++; NativeText.refresh(); kickAll(); }
    }
    public static void warm(Context context) {
        final Context app = context.getApplicationContext();
        final String target = language;
        if (!active || target.equals("en")) return;
        try { WORK.execute(() -> {
            Bundle extras = new Bundle();
            extras.putString("text", "Read the documentation."); extras.putString("target", target);
            LocalTranslation.call(app, "translate", extras);
        }); } catch (RuntimeException ignored) { /* Real page requests remain authoritative. */ }
    }
    /** Runs before the original native renderer's github.load, never blocks the UI for translation. */
    public static String beforeLoad(WebView web, String command) {
        attach(web);
        Session session = SESSIONS.get(web);
        if (session == null || script == null) return command;
        session.lastCommand = command;
        String cached = PAGES.get(language + '\u0000' + command);
        MAIN.removeCallbacks(session); MAIN.post(session);
        String prefix = "(function(){" + script + "\nwindow.__ghcnImmersive.configure(" + active + ","
            + JSONObject.quote(language) + "," + configuration + "," + dual + ");"
            + (cached == null ? "" : "window.__ghcnImmersive.seed(" + cached + ");") + "})();\n";
        // Native markdown uses a JavaScript URL, whose contents WebView URI-decodes once.
        // Encode only our prefix and leave the original command's escaping untouched.
        return command.startsWith("javascript:")
            ? "javascript:" + android.net.Uri.encode(prefix) + command.substring(11)
            : prefix + command;
    }
    /** Keep GitHub's mention highlighting after any delayed native markdown render. */
    public static String afterLoad(WebView web, String command) {
        return "(function(){var run=function(){" + command + "\n};var api=window.__ghcnImmersive;"
            + "if(api&&typeof api.afterLoad==='function'){api.afterLoad(run);}else{run();}})();";
    }
    public static synchronized void setEnabled(boolean value) {
        if (active != value) { active = value; configuration++; NativeText.refresh(); kickAll(); }
    }
    public static synchronized void setTarget(String value) {
        if (value == null) return;
        value = value.toLowerCase(Locale.ROOT);
        if (!LanguageOptions.supports(value)) return;
        if (!language.equals(value)) { language = value; configuration++; NativeText.refresh(); kickAll(); }
    }
    private static void kickAll() {
        MAIN.post(() -> { for (Session session : SESSIONS.values()) {
            MAIN.removeCallbacks(session); MAIN.post(session);
        } });
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
            MAIN.post(session);
        } catch (Exception ignored) {
            // A missing payload asset must never prevent the original app from opening.
        }
    }

    private static final class Session implements Runnable {
        private final WeakReference<WebView> view;
        private final Context context;
        private volatile long generation;
        private String document = "";
        private boolean reportedError;
        private boolean translating;
        private int pollDelay = 900;
        private String lastCommand;

        Session(WebView web) {
            view = new WeakReference<>(web);
            context = web.getContext().getApplicationContext();
        }
        @Override public void run() {
            WebView web = view.get();
            if (web == null) return;
            boolean enabled;
            String target;
            long config;
            boolean bilingual;
            synchronized (Immersive.class) {
                enabled = active; target = language; config = configuration; bilingual = dual;
            }
            final long expectedConfig = config;
            final String pageCacheKey = lastCommand == null ? null : target + '\u0000' + lastCommand;
            try {
                // Rechecking installs again after navigation, including loadDataWithBaseURL.
                if (!translating) {
                    String command = "(function(){" + script + "\nreturn window.__ghcnImmersive.poll("
                        + enabled + "," + JSONObject.quote(target) + "," + config + "," + bilingual + ");})()";
                    web.evaluateJavascript(command, raw -> collect(raw, expectedConfig, pageCacheKey));
                } else {
                    web.evaluateJavascript("window.__ghcnImmersive && window.__ghcnImmersive.configure("
                        + enabled + "," + JSONObject.quote(target) + "," + config + "," + bilingual + ")", null);
                }
            } catch (Exception ignored) { generation++; }
            MAIN.removeCallbacks(this);
            MAIN.postDelayed(this, translating ? 100 : pollDelay);
        }
        private void collect(String raw, long config, String pageCacheKey) {
            if (configuration != config || raw == null || raw.equals("null")) return;
            try {
                JSONObject batch = new JSONObject(raw);
                if (!batch.optBoolean("allowed")) { generation++; document = ""; return; }
                String page = batch.getString("document");
                JSONObject cached = batch.optJSONObject("cache");
                if (pageCacheKey != null && cached != null) {
                    String value = cached.toString();
                    if (pageCacheKey.length() + value.length() <= PAGES.maxSize()) PAGES.put(pageCacheKey, value);
                }
                if (!page.equals(document)) { generation++; document = page; }
                long currentGeneration = generation;
                long epoch = batch.getLong("epoch");
                JSONArray items = batch.optJSONArray("items");
                pollDelay = batch.optBoolean("native") && !batch.optBoolean("ready") && batch.optInt("total") > 0 ? 40 : 900;
                if (items == null || items.length() == 0 || translating) return;
                String target = batch.getString("target");
                translating = true;
                try { WORK.execute(() -> translateBatch(page, epoch, items, target, config, currentGeneration)); }
                catch (RuntimeException full) { translating = false; finishBusy(page, epoch, items, config, currentGeneration); }
            } catch (Exception ignored) { /* Empty or replaced documents are retried next tick. */ }
        }
        private void translateBatch(String page, long epoch, JSONArray items, String target,
                                    long config, long expectedGeneration) {
            JSONArray results = new JSONArray();
            try {
                for (int i = 0; i < items.length(); i++) {
                    if (configuration != config || generation != expectedGeneration || view.get() == null) break;
                    JSONObject item = items.getJSONObject(i), result = new JSONObject();
                    Bundle extras = new Bundle(); extras.putString("text", item.getString("text")); extras.putString("target", target);
                    Bundle answer = LocalTranslation.call(context, "translate", extras);
                    result.put("id", item.getInt("id"));
                    String value = answer == null ? null : answer.getString("text");
                    String error = answer == null ? "empty embedded result" : answer.getString("error");
                    result.put("text", value == null ? JSONObject.NULL : value);
                    result.put("error", error == null ? JSONObject.NULL : error);
                    results.put(result);
                }
            } catch (Exception failure) { android.util.Log.w("GitHubCN", "Page translation failed", failure); }
            MAIN.post(() -> {
                translating = false;
                WebView web = view.get();
                if (web != null && configuration == config && generation == expectedGeneration) {
                    String command = "window.__ghcnImmersive && window.__ghcnImmersive.applyBatch("
                        + JSONObject.quote(page) + "," + epoch + "," + results.toString() + ")";
                    try { web.evaluateJavascript(command, null); } catch (Exception ignored) {}
                }
                MAIN.removeCallbacks(this); MAIN.post(this);
            });
        }
        private void finishBusy(String page, long epoch, JSONArray items, long config, long expectedGeneration) throws Exception {
            for (int i = 0; i < items.length(); i++)
                finish(page, epoch, items.getJSONObject(i).getInt("id"), null, "busy", config, expectedGeneration);
        }
        private void finish(String page, long epoch, int id, String translated, String error,
                            long config, long expectedGeneration) {
            final String result = translated, problem = error;
            MAIN.post(() -> {
                WebView web = view.get();
                if (web == null || configuration != config || generation != expectedGeneration) return;
                if (problem != null && !reportedError) {
                    reportedError = true;
                    android.widget.Toast.makeText(web.getContext(), "内置翻译模型准备中，请保持可访问模型服务的 Wi-Fi 后重试", android.widget.Toast.LENGTH_LONG).show();
                    android.util.Log.w("GitHubCN", "Embedded translation error: " + problem);
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
