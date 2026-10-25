package cn.local.githubcn.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Synthetic document checks only; no activity, account, credential, or repository data is read. */
public final class ImmersiveCheck extends Instrumentation {
    private static final String ORIGINAL = "Install this application and read the documentation.";
    private static final String DYNAMIC = "This project is open source and welcomes contributions.";
    private static final String CODE = "npm install demo";
    private static final String NATIVE = "This application helps developers browse open source projects.";
    private final Bundle evidence = new Bundle();
    private WebView web;
    private TextView nativeView;
    private Class<?> immersive;
    private long deadline;
    private boolean originalEnabled;
    private boolean originalBilingual;
    private String originalTarget;
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }

    @Override public void onStart() {
        super.onStart();
        deadline = SystemClock.elapsedRealtime() + 180000;
        evidence.putString("check_mode", arguments != null && "french".equals(arguments.getString("only")) ? "french-only" : "all");
        boolean passed = false;
        try {
            Context target = getTargetContext();
            evidence.putString("target_package", target.getPackageName());
            evidence.putString("op_package", target.getOpPackageName());
            evidence.putInt("process_uid", Process.myUid());
            evidence.putInt("process_pid", Process.myPid());
            evidence.putInt("target_uid", target.getApplicationInfo().uid);
            require("com.github.android.chinese".equals(target.getPackageName()), "Wrong instrumentation target package");
            require(Process.myUid() == target.getApplicationInfo().uid, "Runner is not executing under the clone UID");
            String[] permissions = target.getPackageManager().getPackageInfo(target.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissions != null) for (String permission : permissions)
                require(!"cn.local.repochinese.TRANSLATE".equals(permission), "Clone still requests the external helper permission");
            evidence.putBoolean("no_helper_permission", true);
            Class<?> localTranslation = Class.forName("cn.local.githubcn.LocalTranslation", true, target.getClassLoader());
            Method localCall = localTranslation.getMethod("call", Context.class, String.class, Bundle.class);
            evidence.putBoolean("embedded_engine_loaded", true);
            if (arguments != null && "french".equals(arguments.getString("only"))) {
                Bundle french = BilingualCheck.runFrench(this, target, deadline);
                evidence.putAll(french);
                require(french.getBoolean("french_model_ready"), "French model check failed: " + french.getString("french_failure_cause", french.getString("french_failure")));
                passed = true;
                return;
            }
            immersive = Class.forName("cn.local.githubcn.Immersive", true, target.getClassLoader());
            originalEnabled = (Boolean) immersive.getMethod("enabled").invoke(null);
            originalTarget = (String) immersive.getMethod("target").invoke(null);
            originalBilingual = (Boolean) immersive.getMethod("bilingual").invoke(null);
            main(() -> {
                invoke("setTarget", String.class, "zh");
                invoke("setEnabled", boolean.class, true);
                invoke("setBilingual", boolean.class, false);
                web = new WebView(target);
                web.getSettings().setJavaScriptEnabled(true);
                web.loadDataWithBaseURL("https://appassets.androidplatform.net/android_asset/webview/",
                    "<!doctype html><html><body><main id='content' class='markdown-body'><p id='p'>" + ORIGINAL + "</p><code id='c'>" + CODE + "</code></main></body></html>",
                    "text/html", "UTF-8", null);
            });
            JSONObject baseline = waitFor("document loaded", value -> ORIGINAL.equals(value.optString("p")) && CODE.equals(value.optString("c")));
            evidence.putString("baseline", baseline.toString());
            main(() -> invoke("attach", WebView.class, web));
            JSONObject translated = waitFor("original paragraph translated", value -> chinese(value.optString("p")) && !ORIGINAL.equals(value.optString("p")));
            require(CODE.equals(translated.optString("c")), "Code changed during original paragraph translation");
            evidence.putString("translated_p", translated.optString("p"));
            report("Original paragraph replaced by Chinese; code is unchanged");

            eval("(function(){var p=document.createElement('p');p.id='d';p.textContent=" + JSONObject.quote(DYNAMIC) + ";document.getElementById('content').appendChild(p);return true;})()");
            JSONObject dynamic = waitFor("dynamic paragraph translated", value -> chinese(value.optString("d")) && !DYNAMIC.equals(value.optString("d")));
            require(CODE.equals(dynamic.optString("c")), "Code changed during dynamic paragraph translation");
            evidence.putString("translated_dynamic", dynamic.optString("d"));
            report("Dynamically inserted paragraph translated in place");

            main(() -> invoke("setEnabled", boolean.class, false));
            JSONObject restored = waitFor("originals restored", value -> ORIGINAL.equals(value.optString("p")) && DYNAMIC.equals(value.optString("d")));
            require(CODE.equals(restored.optString("c")), "Code changed when translation was disabled");
            evidence.putString("restored", restored.toString());
            evidence.putBoolean("code_unchanged", true);
            evidence.putBoolean("dynamic_translated", true);
            evidence.putBoolean("originals_restored", true);
            report("Disabling translation restored both original paragraphs");

            // A real embedded call verifies the target class and UID, without a helper resolver.
            FutureTask<Bundle> embedded = new FutureTask<>(() -> {
                require(Process.myUid() == target.getApplicationInfo().uid, "Embedded call is outside the clone UID");
                require(Looper.myLooper() != Looper.getMainLooper(), "Embedded translation ran on the main thread");
                Bundle extras = new Bundle();
                extras.putString("text", ORIGINAL);
                extras.putString("target", "zh");
                return (Bundle) localCall.invoke(null, target, "translate", extras);
            });
            Thread caller = new Thread(embedded, "githubcn-check-embedded");
            caller.setDaemon(true);
            caller.start();
            Bundle reply = embedded.get(remaining(), TimeUnit.MILLISECONDS);
            require(reply != null, "Embedded engine returned no bundle");
            evidence.putString("embedded_error", reply.getString("error"));
            evidence.putString("embedded_text", reply.getString("text"));
            require(reply.getString("error") == null && chinese(reply.getString("text")), "Embedded engine returned an error or no Chinese: " + reply);
            evidence.putBoolean("embedded_clone_call", true);
            report("Embedded engine ran under clone UID in a background thread and returned Chinese");

            // Repeat an already translated synthetic sentence: no new language/model request is needed.
            Field cacheField = localTranslation.getDeclaredField("TRANSLATIONS");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            Class<?> cacheType = Class.forName("android.util.LruCache");
            Method hitCount = cacheType.getMethod("hitCount");
            int hitsBefore = ((Number) hitCount.invoke(cache)).intValue();
            FutureTask<Bundle> repeats = new FutureTask<>(() -> {
                require(Looper.myLooper() != Looper.getMainLooper(), "Repeat cache checks ran on the main thread");
                Bundle answer = null;
                for (int i = 0; i < 3; i++) {
                    Bundle extras = new Bundle();
                    extras.putString("text", ORIGINAL); extras.putString("target", "zh");
                    if (i > 0) extras.putString("source", "en");
                    answer = (Bundle) localCall.invoke(null, target, "translate", extras);
                    require(answer != null && answer.getString("error") == null &&
                        reply.getString("text").equals(answer.getString("text")), "Repeat cache reply differs from the successful result");
                }
                return answer;
            });
            Thread cacheCaller = new Thread(repeats, "githubcn-check-repeat-cache");
            cacheCaller.setDaemon(true); cacheCaller.start();
            repeats.get(remaining(), TimeUnit.MILLISECONDS);
            int hitsAfter = ((Number) hitCount.invoke(cache)).intValue();
            int cacheSize = ((Number) cacheType.getMethod("size").invoke(cache)).intValue();
            int cacheLimit = ((Number) cacheType.getMethod("maxSize").invoke(cache)).intValue();
            require(hitsAfter >= hitsBefore + 3, "Repeated successful translations did not hit the memory cache");
            require(cacheLimit == 512 * 1024 && cacheSize >= 0 && cacheSize <= cacheLimit, "Translation cache exceeds its character budget");
            evidence.putInt("translation_cache_hit_count_delta", hitsAfter - hitsBefore);
            evidence.putInt("translation_cache_size_characters", cacheSize);
            evidence.putInt("translation_cache_limit_characters", cacheLimit);
            evidence.putBoolean("translation_cache_repeated_hits", true);
            evidence.putBoolean("translation_cache_char_bound", true);
            report("Repeated synthetic translations hit the in-memory cache; its size stays within 512K characters");

            Class<?> nativeText = Class.forName("cn.local.githubcn.NativeText", true, target.getClassLoader());
            Method attachText = nativeText.getMethod("attach", TextView.class);
            main(() -> {
                invoke("setEnabled", boolean.class, true);
                invoke("setTarget", String.class, "zh");
                nativeView = new TextView(target);
                nativeView.setText(NATIVE);
                try { attachText.invoke(null, nativeView); }
                catch (Exception error) { throw new IllegalStateException("NativeText.attach failed", error); }
            });
            String nativeTranslated = waitForNative("native TextView translated", text -> chinese(text) && !NATIVE.equals(text));
            evidence.putString("native_translated", nativeTranslated);
            evidence.putBoolean("native_text_translated", true);
            report("Native TextView description replaced by Chinese: " + nativeTranslated);
            main(() -> {
                for (int i = 0; i < 10; i++) {
                    nativeView.setText(NATIVE);
                    require(nativeTranslated.equals(nativeView.getText().toString()),
                        "Native rebind " + i + " briefly restored the original instead of synchronously reusing the translation");
                }
            });
            evidence.putBoolean("native_rebind_stable", true);
            evidence.putInt("native_rebind_count", 10);
            report("Ten native source rebinds synchronously kept Chinese without another attach call");
            main(() -> invoke("setBilingual", boolean.class, true));
            String nativeBilingual = waitForNative("native bilingual display", text -> (NATIVE + '\n' + nativeTranslated).equals(text));
            require(count(nativeBilingual, NATIVE) == 1 && count(nativeBilingual, nativeTranslated) == 1,
                "Native bilingual display duplicated a source or translation");
            evidence.putString("native_bilingual_text", nativeBilingual);
            evidence.putBoolean("native_bilingual_source_once", true);
            evidence.putBoolean("native_bilingual_translation_once", true);
            main(() -> invoke("setBilingual", boolean.class, false));
            waitForNative("native translation-only after bilingual mode", nativeTranslated::equals);
            evidence.putBoolean("native_bilingual_switch", true);
            main(() -> invoke("setEnabled", boolean.class, false));
            waitForNative("native original after disabling", NATIVE::equals);
            evidence.putBoolean("native_disabled_original", true);
            main(() -> invoke("setEnabled", boolean.class, true));
            waitForNative("native Chinese after re-enabling", nativeTranslated::equals);
            evidence.putBoolean("native_reenable_chinese", true);
            main(() -> invoke("setTarget", String.class, "en"));
            evidence.putString("native_restored", waitForNative("native TextView English restored", NATIVE::equals));
            evidence.putBoolean("native_en_restored", true);
            report("Switching native target to English restored the original description");

            Class<?> queryClass = Class.forName("cn.local.githubcn.SearchQuery", true, target.getClassLoader());
            Method buildQuery = queryClass.getDeclaredMethod("build", String.class);
            buildQuery.setAccessible(true);
            String query = (String) buildQuery.invoke(null, "codex++");
            Class<?> searchClass = Class.forName("cn.local.githubcn.SearchActivity", true, target.getClassLoader());
            AtomicReference<Object> search = new AtomicReference<>();
            main(() -> {
                try { search.set(searchClass.getDeclaredConstructor().newInstance()); }
                catch (Exception error) { throw new IllegalStateException("SearchActivity construction failed", error); }
            });
            Method fetch = searchClass.getDeclaredMethod("fetch", String.class);
            fetch.setAccessible(true);
            FutureTask<JSONObject> searchRequest = new FutureTask<>(() -> (JSONObject) fetch.invoke(search.get(), query));
            Thread searchWorker = new Thread(searchRequest, "githubcn-check-search");
            searchWorker.setDaemon(true);
            searchWorker.start();
            JSONObject searchReply = searchRequest.get(remaining(), TimeUnit.MILLISECONDS);
            JSONArray items = searchReply.getJSONArray("items");
            JSONArray candidates = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                String name = items.getJSONObject(i).optString("full_name");
                if (name.equalsIgnoreCase("BigPizzaV3/CodexPlusPlus") || name.equalsIgnoreCase("b-nnett/codex-plusplus")) candidates.put(name);
            }
            evidence.putInt("search_display_count", items.length());
            evidence.putLong("search_total_count", searchReply.optLong("total_count"));
            evidence.putString("search_candidates", candidates.toString());
            require(candidates.length() > 0, "Normalized codex++ API search did not return either expected candidate in its first 100 results");
            evidence.putBoolean("search_codexplusplus", true);
            report("Real normalized codex++ search returned " + items.length() + " repositories; candidates " + candidates);
            Bundle bilingual = BilingualCheck.run(this, target, deadline);
            evidence.putAll(bilingual);
            require(bilingual.getBoolean("bilingual_passed"), "Bilingual search check failed: " + bilingual.getString("bilingual_failure_cause", bilingual.getString("bilingual_failure")));
            Bundle compose = ComposeCheck.run(this, target, deadline);
            evidence.putAll(compose);
            require(compose.getBoolean("compose_passed"), "Original Compose check failed: " + compose.getString("compose_failure_cause", compose.getString("compose_failure")));
            Bundle floating = FloatingCheck.run(this, target, deadline);
            evidence.putAll(floating);
            require(floating.getBoolean("floating_passed"), "Floating button check failed: " + floating.getString("floating_failure_cause", floating.getString("floating_failure")));
            evidence.putBoolean("core_passed", true);
            Bundle french = BilingualCheck.runFrench(this, target, deadline);
            evidence.putAll(french);
            require(french.getBoolean("french_model_ready"), "French model check failed: " + french.getString("french_failure_cause", french.getString("french_failure")));
            passed = true;
        } catch (Throwable failure) {
            evidence.putString("failure", failure.toString());
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            evidence.putString("failure_cause", cause.toString());
            if (web != null && remaining() > 1000) {
                try { evidence.putString("last_document", snapshot().toString()); } catch (Throwable ignored) {}
            }
        } finally {
            try {
                main(() -> {
                    if (immersive != null && originalTarget != null) {
                        invoke("setTarget", String.class, originalTarget);
                        invoke("setEnabled", boolean.class, originalEnabled);
                        invoke("setBilingual", boolean.class, originalBilingual);
                        try {
                            require(originalBilingual == (Boolean) immersive.getMethod("bilingual").invoke(null),
                                "Original bilingual setting was not restored");
                            evidence.putBoolean("cleanup_bilingual_restored", true);
                        } catch (Exception error) { throw new IllegalStateException("Bilingual cleanup failed", error); }
                    }
                    if (web != null) { web.stopLoading(); web.destroy(); web = null; }
                    nativeView = null;
                });
            } catch (Throwable cleanup) { evidence.putString("cleanup_error", cleanup.toString()); }
            evidence.putBoolean("passed", passed);
            evidence.putLong("elapsed_ms", SystemClock.elapsedRealtime() - (deadline - 180000));
            String scope = "french-only".equals(evidence.getString("check_mode")) ? "French model check" : "target-process immersive/embedded/native/search/bilingual/Compose/floating/French checks";
            evidence.putString("stream", (passed ? "PASS" : "FAIL") + ": " + scope + (passed ? "" : " - " + evidence.getString("failure_cause", "unknown failure")) + "\n");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, evidence);
        }
    }

    private JSONObject waitFor(String stage, Predicate<JSONObject> condition) throws Exception {
        JSONObject last = new JSONObject();
        while (remaining() > 0) {
            last = snapshot();
            if (condition.test(last)) return last;
            Thread.sleep(Math.min(250, remaining()));
        }
        throw new AssertionError(stage + " timed out: " + last);
    }

    private String waitForNative(String stage, Predicate<String> condition) throws Exception {
        AtomicReference<String> text = new AtomicReference<>("");
        while (remaining() > 0) {
            main(() -> text.set(nativeView.getText().toString()));
            if (condition.test(text.get())) return text.get();
            Thread.sleep(Math.min(250, remaining()));
        }
        throw new AssertionError(stage + " timed out: " + text.get());
    }

    private JSONObject snapshot() throws Exception {
        String raw = eval("(function(){function text(id){var n=document.getElementById(id);return n?n.textContent:null;}return {ready:document.readyState,p:text('p'),d:text('d'),c:text('c'),hook:!!window.__ghcnImmersive,url:location.href};})()");
        return raw == null || raw.equals("null") ? new JSONObject() : new JSONObject(raw);
    }

    private String eval(String code) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main(() -> web.evaluateJavascript(code, value -> { result.set(value); ready.countDown(); }));
        require(ready.await(Math.min(5000, remaining()), TimeUnit.MILLISECONDS), "WebView evaluateJavascript callback timed out");
        return result.get();
    }

    private void main(Runnable work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new Exception("Target main-thread operation failed", failure.get());
    }

    private void invoke(String name, Class<?> type, Object value) {
        try { Method method = immersive.getMethod(name, type); method.invoke(null, value); }
        catch (Exception error) { throw new IllegalStateException("Immersive." + name + " failed", error); }
    }

    private void report(String message) {
        Bundle state = new Bundle();
        state.putString("stream", message + "\n");
        sendStatus(0, state);
    }

    private long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    private static int count(String text, String value) {
        int total = 0, offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) { total++; offset += value.length(); }
        return total;
    }
    private static boolean chinese(String text) { return text != null && text.matches("(?s).*[\\u3400-\\u9fff].*"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
