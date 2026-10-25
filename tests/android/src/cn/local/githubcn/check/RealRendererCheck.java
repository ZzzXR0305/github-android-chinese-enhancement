package cn.local.githubcn.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Hilt renderer construction with synthetic markdown; never manually attaches translation. */
public final class RealRendererCheck extends Instrumentation {
    private static final String LOGIN = "com.github.android.auth.SimplifiedLoginActivity";
    private static final String ID = "ghcn-renderer-probe";
    private static final String ORIGINAL = "Install this application and read the documentation.";
    private static final String SECOND = "This project is open source and welcomes contributions.";
    private static final String CODE = "npm install demo";
    private static final String LINK = "https://github.com/example/repo";
    private static final int PARAGRAPHS = 12;
    private static final String HTML = fixture(ORIGINAL, "first");
    private static final String SECOND_HTML = fixture(SECOND, "second");
    private final Bundle evidence = new Bundle();
    private Activity activity;
    private View holder;
    private WebView web;
    private Class<?> immersive;
    private boolean oldEnabled, oldBilingual, savedSettings;
    private String oldTarget;
    private volatile String currentHtml = HTML;
    private String lastNativeCommand;
    private int nativeLoads;
    private long deadline;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        super.onStart();
        new Thread(this::checkRenderer, "githubcn-check-real-renderer").start();
    }

    private void checkRenderer() {
        long started = SystemClock.elapsedRealtime();
        deadline = started + 180000;
        Instrumentation.ActivityMonitor monitor = null;
        boolean passed = false;
        evidence.putBoolean("dynamic_automatic_translation", false);
        evidence.putBoolean("restored_after_disable", false);
        evidence.putBoolean("retranslated_after_enable", false);
        evidence.putBoolean("cross_view_cache_hit", false);
        evidence.putBoolean("cross_view_no_requests", false);
        evidence.putBoolean("cross_view_warm_translated", false);
        try {
            Context target = getTargetContext();
            require("com.github.android.chinese".equals(target.getPackageName()), "Wrong target package");
            require(Process.myUid() == target.getApplicationInfo().uid, "Runner is outside target UID");
            require(Looper.myLooper() != Looper.getMainLooper(), "Runner is on main thread");
            evidence.putString("target_package", target.getPackageName());
            evidence.putInt("process_uid", Process.myUid());
            evidence.putBoolean("manual_attach", false);
            ClassLoader loader = target.getClassLoader();
            immersive = Class.forName("cn.local.githubcn.Immersive", true, loader);
            main(() -> {
                try {
                    oldEnabled = (Boolean) immersive.getMethod("enabled").invoke(null);
                    oldTarget = (String) immersive.getMethod("target").invoke(null);
                    oldBilingual = (Boolean) immersive.getMethod("bilingual").invoke(null);
                    savedSettings = true;
                    immersive.getMethod("setTarget", String.class).invoke(null, "zh");
                    immersive.getMethod("setBilingual", boolean.class).invoke(null, false);
                    immersive.getMethod("setEnabled", boolean.class).invoke(null, true);
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            evidence.putBoolean("old_enabled", oldEnabled);
            evidence.putString("old_target", oldTarget);
            evidence.putBoolean("old_bilingual", oldBilingual);
            monitor = addMonitor(LOGIN, null, false);
            main(() -> target.startActivity(new Intent().setClassName(target.getPackageName(), LOGIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            activity = monitor.waitForActivityWithTimeout(Math.min(10000, remaining()));
            require(activity != null, "Hilt fixture Activity did not start");
            main(() -> {
                try {
                    activity.setShowWhenLocked(true);
                    activity.setTurnScreenOn(true);
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    FrameLayout root = new FrameLayout(activity);
                    activity.setContentView(root);
                    Class<?> type = Class.forName("com.github.android.webview.viewholders.GitHubWebView", true, loader);
                    holder = (View) type.getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
                    root.addView(holder, new FrameLayout.LayoutParams(-1, -2));
                    web = (WebView) type.getField("u").get(holder);
                    require(web != null, "Real GitHubWebView constructor returned no inner renderer");
                    Field content = web.getClass().getDeclaredField("F");
                    content.setAccessible(true);
                    Class<?> item = Class.forName("j5d0", true, loader);
                    content.set(web, Proxy.newProxyInstance(loader, new Class<?>[]{item}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("I")) return currentHtml;
                        if (name.equals("F") || name.equals("getId")) return ID;
                        if (name.equals("S")) return "https://github.com/example/repo";
                        if (name.equals("equals")) return proxy == args[0];
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("toString")) return "Synthetic GitHub renderer item";
                        Class<?> result = method.getReturnType();
                        if (result == boolean.class) return false;
                        if (result == int.class) return 0;
                        if (result == long.class) return 0L;
                        if (result == float.class) return 0f;
                        if (result == double.class) return 0d;
                        if (result == byte.class) return (byte) 0;
                        if (result == short.class) return (short) 0;
                        if (result == char.class) return (char) 0;
                        if (result == String.class) return "";
                        return null;
                    }));
                    evidence.putString("holder_class", holder.getClass().getName());
                    evidence.putString("renderer_class", web.getClass().getName());
                    evidence.putString("content_interface", item.getName());
                    WebSettings settings = web.getSettings();
                    JSONObject configuration = new JSONObject();
                    configuration.put("javascript", settings.getJavaScriptEnabled());
                    configuration.put("domStorage", settings.getDomStorageEnabled());
                    configuration.put("allowFileAccess", settings.getAllowFileAccess());
                    configuration.put("allowContentAccess", settings.getAllowContentAccess());
                    configuration.put("blockNetworkLoads", settings.getBlockNetworkLoads());
                    configuration.put("mixedContentMode", settings.getMixedContentMode());
                    configuration.put("userAgent", settings.getUserAgentString());
                    configuration.put("webViewClient", web.getWebViewClient().getClass().getName());
                    configuration.put("webChromeClient", web.getWebChromeClient().getClass().getName());
                    evidence.putString("webview_settings", configuration.toString());
                } catch (Exception error) { throw new IllegalStateException("Real renderer construction failed", error); }
            });

            JSONObject ready = null;
            long readyUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 20000);
            while (SystemClock.elapsedRealtime() < readyUntil) {
                ready = snapshot();
                if (ready.optBoolean("githubLoad")) break;
                Thread.sleep(250);
            }
            evidence.putString("before_load", String.valueOf(ready));
            require(ready != null && ready.optBoolean("githubLoad"), "Original window.github.load did not become ready");
            startFrames(ORIGINAL, "first");
            long coldLoadedAt = SystemClock.elapsedRealtime(), coldReadyAt = -1;
            evidence.putString("load_result", load(HTML));
            JSONObject state = null;
            long observeUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 30000);
            while (SystemClock.elapsedRealtime() < observeUntil) {
                state = snapshot();
                if (evidence.getString("after_load") == null && !state.optString("probe").isEmpty())
                    evidence.putString("after_load", state.toString());
                if (allTranslated(state)) { coldReadyAt = SystemClock.elapsedRealtime(); break; }
                Thread.sleep(25);
            }
            evidence.putString("last_document", String.valueOf(state));
            boolean automatic = state != null && allTranslated(state);
            evidence.putBoolean("automatic_translation", automatic);
            if (automatic) evidence.putLong("cold_translation_ready_delay_ms", coldReadyAt - coldLoadedAt);
            evidence.putBoolean("code_unchanged", state != null && CODE.equals(state.optString("code")));
            if (!automatic) {
                // Diagnostic interventions happen only after the unassisted observation failed.
                // Never call poll ourselves: doing so could consume the real session's work queue.
                try {
                    evidence.putString("mutation_marker_result", eval("(function(){var p=document.getElementById('probe'),parent=p&&p.parentNode;if(!parent)return {marked:false};var marker=document.createComment('ghcn-renderer-diagnostic');parent.appendChild(marker);parent.removeChild(marker);return {marked:true};})()"));
                    Thread.sleep(2000);
                    JSONObject mutated = snapshot();
                    evidence.putString("after_mutation_document", mutated.toString());
                    evidence.putBoolean("rescanned_after_mutation", chinese(mutated.optString("probe")));
                    main(() -> {
                        try { immersive.getMethod("setEnabled", boolean.class).invoke(null, false); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    });
                    Thread.sleep(1100);
                    evidence.putString("disabled_document", snapshot().toString());
                    main(() -> {
                        try { immersive.getMethod("setEnabled", boolean.class).invoke(null, true); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    });
                    Thread.sleep(5000);
                    JSONObject toggled = snapshot();
                    evidence.putString("after_toggle_document", toggled.toString());
                    evidence.putBoolean("rescanned_after_toggle", chinese(toggled.optString("probe")));
                } catch (Throwable diagnostic) { evidence.putString("intervention_error", diagnostic.toString()); }
            }
            require(automatic,
                "Real renderer did not automatically translate the probe within 30 seconds");
            require(CODE.equals(state.optString("code")), "Real renderer changed the code sample");
            assertAtomic(state, "cold");
            JSONArray firstTranslations = state.getJSONObject("frame").getJSONArray("translations");
            assertDisplay(state, ORIGINAL, firstTranslations, false);
            evidence.putBoolean("cold_atomic_translation", true);
            evidence.putBoolean("cold_post_load_marker", true);

            startFrames(ORIGINAL, "first");
            JSONArray warmDelays = new JSONArray();
            JSONObject warm = state;
            for (int repeat = 0; repeat < 10; repeat++) {
                long loadedAt = SystemClock.elapsedRealtime();
                load(HTML);
                warm = waitForDocument("warm_" + repeat, 8000, RealRendererCheck::allTranslated);
                warmDelays.put(SystemClock.elapsedRealtime() - loadedAt);
                assertDisplay(warm, ORIGINAL, firstTranslations, false);
            }
            assertAtomic(warm, "warm");
            require(warm.getJSONObject("frame").optInt("offeredRequests") == 0, "Warm same-source loads requested translation again");
            evidence.putString("warm_load_delays_ms", warmDelays.toString());
            evidence.putString("warm_delay_scope", "decorated load plus WebView observation; includes test callback overhead");
            evidence.putBoolean("warm_refresh_stable", true);
            evidence.putBoolean("warm_post_load_marker", true);

            for (int repeat = 0; repeat < 3; repeat++) {
                mode(true);
                JSONObject dual = waitForDocument("bilingual_on_" + repeat, 8000, value -> {
                    JSONObject frame = value.optJSONObject("frame");
                    return frame != null && frame.optInt("originalsVisible") == PARAGRAPHS && allTranslated(value);
                });
                assertDisplay(dual, ORIGINAL, firstTranslations, true);
                mode(false);
                JSONObject single = waitForDocument("bilingual_off_" + repeat, 8000, value -> {
                    JSONObject frame = value.optJSONObject("frame");
                    return frame != null && frame.optInt("originalsVisible") == 0 && allTranslated(value);
                });
                assertDisplay(single, ORIGINAL, firstTranslations, false);
                require(single.getJSONObject("frame").optInt("offeredRequests") == 0, "Bilingual display switching requested translation again");
            }
            evidence.putBoolean("bilingual_three_cycles_no_duplicates", true);
            evidence.putBoolean("bilingual_post_load_replayed", true);

            startFrames(SECOND, "second");
            evidence.putString("dynamic_load_result", load(SECOND_HTML));
            JSONObject dynamic = waitForDocument("dynamic", 30000, RealRendererCheck::allTranslated);
            require(CODE.equals(dynamic.optString("code")), "Dynamic renderer update changed the code sample");
            require(!dynamic.optString("main").contains(ORIGINAL), "Dynamic update retained the old English paragraph");
            assertAtomic(dynamic, "dynamic");
            JSONArray secondTranslations = dynamic.getJSONObject("frame").getJSONArray("translations");
            assertDisplay(dynamic, SECOND, secondTranslations, false);
            evidence.putBoolean("dynamic_automatic_translation", true);
            evidence.putBoolean("dynamic_code_unchanged", true);

            main(() -> {
                try { immersive.getMethod("setEnabled", boolean.class).invoke(null, false); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            JSONObject restored = waitForDocument("restore", 8000, value -> {
                JSONObject frame = value.optJSONObject("frame");
                return frame != null && frame.optBoolean("allSource");
            });
            require(CODE.equals(restored.optString("code")), "Disabling translation changed the code sample");
            require("marked".equals(restored.getJSONObject("frame").optString("afterMarker")), "Post-render fixture callback was not replayed when restoring the original");
            require("true".equals(eval("(function(){var expected=document.createElement('div');expected.innerHTML=" + JSONObject.quote(SECOND_HTML)
                + ";expected.querySelector('#repo-link').setAttribute('data-ghcn-after','marked');return document.getElementById('content').innerHTML===expected.innerHTML;})()")), "Disabling translation did not restore exact source HTML plus its expected post-render fixture marker");
            evidence.putBoolean("restored_after_disable", true);
            evidence.putBoolean("exact_source_html_restored", true);
            evidence.putBoolean("source_restore_post_load_marker", true);
            evidence.putBoolean("restore_code_unchanged", true);

            main(() -> {
                try { immersive.getMethod("setEnabled", boolean.class).invoke(null, true); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            JSONObject enabled = waitForDocument("reenable", 30000, RealRendererCheck::allTranslated);
            require(CODE.equals(enabled.optString("code")), "Re-enabling translation changed the code sample");
            assertDisplay(enabled, SECOND, secondTranslations, false);
            evidence.putBoolean("retranslated_after_enable", true);
            evidence.putBoolean("reenable_code_unchanged", true);

            // Probe only this fixture's known command, never enumerate the page cache.
            Field pagesField = immersive.getDeclaredField("PAGES");
            pagesField.setAccessible(true);
            Object pages = pagesField.get(null);
            java.lang.reflect.Method cacheGet = android.util.LruCache.class.getMethod("get", Object.class);
            java.lang.reflect.Method cacheHits = android.util.LruCache.class.getMethod("hitCount");
            String cachedCommand = lastNativeCommand;
            require(cachedCommand != null && cachedCommand.startsWith("javascript:github.load(") && cachedCommand.contains(ID),
                "No actual native synthetic github.load command was captured");
            String cacheKey = "zh\u0000" + cachedCommand;
            String cached = null;
            long cacheUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 10000);
            while (SystemClock.elapsedRealtime() < cacheUntil) {
                cached = (String) cacheGet.invoke(pages, cacheKey);
                if (cached != null) break;
                Thread.sleep(25);
            }
            require(cached != null, "Synthetic second-page command was not exported to the native page cache");
            JSONObject cachedPage = new JSONObject(cached);
            require(SECOND_HTML.equals(cachedPage.getString("source")), "Cached fixture source differs from the exact second HTML");
            require(!cachedPage.getString("translated").isEmpty() && !cachedPage.getString("bilingual").isEmpty(), "Synthetic page cache is incomplete");
            evidence.putBoolean("cross_view_cache_source_exact", true);
            evidence.putInt("cross_view_cache_entry_chars", cached.length());
            int hitsBefore = (Integer) cacheHits.invoke(pages);
            evidence.putInt("cross_view_cache_hits_before", hitsBefore);

            main(() -> {
                try {
                    WebView previousWeb = web;
                    View previousHolder = holder;
                    require(previousHolder.getParent() instanceof FrameLayout, "Fixture holder parent is not a FrameLayout");
                    FrameLayout parent = (FrameLayout) previousHolder.getParent();
                    Field previousContent = previousWeb.getClass().getDeclaredField("F");
                    previousContent.setAccessible(true);
                    Object syntheticItem = previousContent.get(previousWeb);
                    require(syntheticItem != null && Proxy.isProxyClass(syntheticItem.getClass()), "Previous item is not this fixture's synthetic proxy");
                    evidence.putInt("cross_view_previous_identity", System.identityHashCode(previousWeb));
                    previousWeb.stopLoading();
                    if (previousWeb.getParent() instanceof ViewGroup) ((ViewGroup) previousWeb.getParent()).removeView(previousWeb);
                    parent.removeView(previousHolder);
                    previousWeb.destroy();
                    Class<?> holderType = previousHolder.getClass();
                    holder = (View) holderType.getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
                    parent.addView(holder, new FrameLayout.LayoutParams(-1, -2));
                    web = (WebView) holderType.getField("u").get(holder);
                    require(web != null && web != previousWeb, "Renderer recreation did not create a fresh WebView");
                    Field replacementContent = web.getClass().getDeclaredField("F");
                    replacementContent.setAccessible(true);
                    replacementContent.set(web, syntheticItem);
                    evidence.putInt("cross_view_new_identity", System.identityHashCode(web));
                } catch (Exception error) { throw new IllegalStateException("Real WebView recreation failed", error); }
            });
            // Waiting for the normal constructor session also installs the passive poll observer.
            waitForDocument("cross_view_ready", 20000, value -> value.optBoolean("githubLoad") && value.optInt("hookVersion") == 2);
            startFrames(SECOND, "second");
            long crossLoadedAt = SystemClock.elapsedRealtime();
            evidence.putString("cross_view_load_result", load(SECOND_HTML));
            require(cachedCommand.equals(lastNativeCommand), "Recreated renderer emitted a different native load command");
            JSONObject crossView = waitForDocument("cross_view_warm", 8000, value -> {
                JSONObject frame = value.optJSONObject("frame");
                return allTranslated(value) && frame != null && frame.optInt("frames") > 0;
            });
            evidence.putLong("cross_view_warm_delay_ms", SystemClock.elapsedRealtime() - crossLoadedAt);
            assertAtomic(crossView, "cross_view");
            assertDisplay(crossView, SECOND, secondTranslations, false);
            require(crossView.getJSONObject("frame").optInt("offeredRequests") == 0, "Fresh WebView requested translation instead of using the native warm cache");
            int hitsAfter = (Integer) cacheHits.invoke(pages);
            require(hitsAfter - hitsBefore >= 1, "Production beforeLoad did not hit the native page cache for the recreated WebView");
            evidence.putInt("cross_view_cache_hits_after", hitsAfter);
            evidence.putInt("cross_view_cache_hit_delta", hitsAfter - hitsBefore);
            evidence.putBoolean("cross_view_cache_hit", true);
            evidence.putBoolean("cross_view_no_requests", true);
            evidence.putBoolean("cross_view_warm_translated", true);
            evidence.putBoolean("cross_view_post_load_marker", true);
            passed = true;
        } catch (Throwable failure) {
            evidence.putString("failure", failure.toString());
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            evidence.putString("error_class", cause.getClass().getName());
            evidence.putString("error_message", String.valueOf(cause.getMessage()));
            if (web != null && remaining() > 1000) try { evidence.putString("failure_document", snapshot().toString()); }
            catch (Throwable diagnostic) { evidence.putString("diagnostic_error", diagnostic.toString()); }
        } finally {
            if (monitor != null) removeMonitor(monitor);
            try {
                main(() -> {
                    try {
                        if (savedSettings) {
                            immersive.getMethod("setTarget", String.class).invoke(null, oldTarget);
                            immersive.getMethod("setBilingual", boolean.class).invoke(null, oldBilingual);
                            immersive.getMethod("setEnabled", boolean.class).invoke(null, oldEnabled);
                            boolean restored = oldTarget.equals(immersive.getMethod("target").invoke(null))
                                && oldBilingual == (Boolean) immersive.getMethod("bilingual").invoke(null)
                                && oldEnabled == (Boolean) immersive.getMethod("enabled").invoke(null);
                            evidence.putBoolean("cleanup_settings_restored", restored);
                            require(restored, "Original translation settings were not restored");
                        }
                        if (web != null) {
                            web.stopLoading();
                            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
                            web.destroy();
                        }
                        if (holder != null && holder.getParent() instanceof ViewGroup) ((ViewGroup) holder.getParent()).removeView(holder);
                        if (activity != null) activity.finish();
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
            } catch (Throwable cleanup) { evidence.putString("cleanup_error", cleanup.toString()); passed = false; }
            evidence.putBoolean("passed", passed);
            evidence.putInt("native_entry_invocations", nativeLoads);
            evidence.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
            evidence.putString("stream", (passed ? "PASS" : "FAIL") + ": real renderer pre-display atomic translation, warm refresh, bilingual, exact restore and cross-WebView cache\n");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, evidence);
        }
    }

    private JSONObject snapshot() throws Exception {
        String script = "(function(){"
            + "if(!window.__ghcnRendererErrors){window.__ghcnRendererErrors=[];window.addEventListener('error',function(e){window.__ghcnRendererErrors.push(String(e.message||e.error||e));});window.addEventListener('unhandledrejection',function(e){window.__ghcnRendererErrors.push(String(e.reason));});}"
            + "var api=window.__ghcnImmersive;if(api&&typeof api.poll==='function'&&!api.poll.__ghcnRendererObserved){var original=api.poll;var observed=function(){try{var result=original.apply(this,arguments);window.__ghcnRendererLastPoll=result;window.__ghcnRendererOffered=(window.__ghcnRendererOffered||0)+(result&&result.items?result.items.length:0);return result;}catch(e){window.__ghcnRendererPollError=String(e.stack||e);throw e;}};observed.__ghcnRendererObserved=true;api.poll=observed;}"
            + "var main=document.querySelector('main'),probe=document.getElementById('probe'),code=document.getElementById('code');"
            + "var l=location,isLocal=(l.protocol==='https:'&&l.hostname==='appassets.androidplatform.net'&&l.pathname.indexOf('/android_asset/webview/')===0)||((l.href==='about:blank'||l.protocol==='file:'||l.protocol==='content:')&&!!document.querySelector('main#content.markdown-body')&&!!document.querySelector('script[src*=\"/android_asset/webview/\"]'));"
            + "var selector=isLocal?'.markdown-body,#readme':'.markdown-body,#readme,.comment-body,[itemprop=\"about\"],[itemprop=\"description\"],[data-testid=\"repository-description\"]';"
            + "var skip='pre,code,kbd,samp,script,style,svg,input,textarea,select,button,[contenteditable=\"true\"],[translate=\"no\"],.notranslate';"
            + "var matches=Array.prototype.slice.call(document.querySelectorAll(selector));var roots=matches.filter(function(n,i){return !matches.some(function(other,j){return j!==i&&other.contains(n);});});"
            + "var texts=[];roots.forEach(function(root){var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),n;while((n=walker.nextNode())&&texts.length<80){var excluded=n.parentElement&&n.parentElement.closest(skip);texts.push({text:n.data,parent:n.parentElement?n.parentElement.tagName:null,skip:excluded?excluded.outerHTML.slice(0,1000):null,contains:document.contains(n)});}});"
            + "var probeSkip=probe&&probe.parentElement&&probe.parentElement.closest(skip);"
            + "return {url:location.href,ready:document.readyState,github:!!window.github,githubLoad:!!(window.github&&typeof window.github.load==='function'),nativeBridge:typeof window.native,hook:!!api,hookVersion:api?api.version:null,hookKeys:api?Object.keys(api):[],lastPoll:window.__ghcnRendererLastPoll||null,pollError:window.__ghcnRendererPollError||null,loadError:window.__ghcnRendererLoadError||null,jsErrors:window.__ghcnRendererErrors.slice(-8),main:main?main.outerHTML.slice(0,10000):null,body:document.body?document.body.innerHTML.slice(0,12000):null,scripts:Array.prototype.map.call(document.scripts,function(s){return s.src||'[inline]';}),probe:probe?probe.textContent:'',probeGeneration:probe?probe.getAttribute('data-ghcn-generation'):null,code:code?code.textContent:'',local:isLocal,rootSelector:selector,rootMatches:matches.length,rootCount:roots.length,roots:roots.map(function(n){return n.outerHTML.slice(0,10000);}),probeParentSkip:probeSkip?probeSkip.outerHTML.slice(0,1000):null,probeContained:!!probe&&document.contains(probe),treeTexts:texts,mutationObserverType:typeof window.MutationObserver,frame:window.__ghcnRendererFrames?window.__ghcnRendererFrames.read():null};})()";
        String raw = eval(script);
        require(raw != null && !raw.equals("null"), "Renderer diagnostic callback returned no JSON");
        return new JSONObject(raw);
    }

    private JSONObject waitForDocument(String stage, long timeout, Predicate<JSONObject> ready) throws Exception {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + timeout);
        while (SystemClock.elapsedRealtime() < until) {
            JSONObject state = snapshot();
            evidence.putString(stage + "_document", state.toString());
            if (ready.test(state)) return state;
            Thread.sleep(25);
        }
        throw new AssertionError("Real renderer " + stage + " timed out");
    }

    private static String fixture(String paragraph, String generation) {
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < PARAGRAPHS; index++) {
            html.append("<p id='").append(index == 0 ? "probe" : "probe-" + index)
                .append("' data-ghcn-probe='").append(index).append("' data-ghcn-generation='")
                .append(generation).append("'>").append(source(paragraph, index)).append("</p>");
        }
        return html.append("<span id='technical'>FLOSS</span><code id='code'>").append(CODE).append("</code><a id='repo-link' href='")
            .append(LINK).append("'>").append(LINK).append("</a>").toString();
    }

    private static String source(String paragraph, int index) { return "Section " + (index + 1) + ". " + paragraph; }

    private void mode(boolean bilingual) throws Exception {
        main(() -> {
            try { immersive.getMethod("setBilingual", boolean.class).invoke(null, bilingual); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
    }

    /** Invoke the actual f.c(): it reads F.I(), escapes the HTML, and executes a JavaScript URL. */
    private String load(String html) throws Exception {
        main(() -> {
            try {
                currentHtml = html;
                java.lang.reflect.Method actualLoad = web.getClass().getDeclaredMethod("c");
                actualLoad.setAccessible(true);
                try {
                    actualLoad.invoke(web);
                } catch (java.lang.reflect.InvocationTargetException invocation) {
                    Throwable cause = invocation.getCause();
                    while (cause != null && cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                    if (cause == null || cause.getClass() != IllegalStateException.class
                        || !"no activity user returned".equals(cause.getMessage())) throw invocation;
                    // SimplifiedLogin has no activity user for f.c()'s post-load mention styling.
                    // Only that exact tail failure is allowed; real hook/DOM/frame checks still gate success.
                    evidence.putBoolean("post_load_account_style_unavailable", true);
                }
                nativeLoads++;
                // Only inspect this fixture WebView's own hook state, not other sessions or accounts.
                Field sessionsField = immersive.getDeclaredField("SESSIONS");
                sessionsField.setAccessible(true);
                Object session = ((java.util.Map<?, ?>) sessionsField.get(null)).get(web);
                require(session != null, "Actual native renderer did not create an immersive session");
                Field lastCommand = session.getClass().getDeclaredField("lastCommand");
                lastCommand.setAccessible(true);
                lastNativeCommand = (String) lastCommand.get(session);
                require(lastNativeCommand != null && lastNativeCommand.startsWith("javascript:github.load(")
                    && lastNativeCommand.contains(ID), "Production hook did not capture the actual github.load URL");
                evidence.putString("native_load_path", "com.github.android.webview.viewholders.f.c() -> WebView.loadUrl");
                // The fixture has no activity user, so simulate only its skipped mention-style tail.
                // The production afterLoad wrapper must defer/replay this callback across real renders.
                String marker = "var link=document.getElementById('repo-link');if(link)link.setAttribute('data-ghcn-after','marked');";
                String wrapped = (String) immersive.getMethod("afterLoad", WebView.class, String.class).invoke(null, web, marker);
                require(wrapped != null && !wrapped.isEmpty(), "Production afterLoad returned no fixture callback wrapper");
                web.evaluateJavascript(wrapped, null);
                evidence.putBoolean("post_load_fixture_callback_wrapped", true);
            } catch (Exception error) { throw new IllegalStateException("Actual production f.c() load failed", error); }
        });
        // loadUrl has no result callback; the caller waits for actual DOM and frame conditions.
        return "actual f.c() dispatched";
    }

    /** Sampling is DOM-read-only; animation frames catch flashes between the Java callbacks. */
    private void startFrames(String paragraph, String generation) throws Exception {
        JSONArray originals = new JSONArray();
        for (int index = 0; index < PARAGRAPHS; index++) originals.put(source(paragraph, index));
        String javascript = "(function(){if(window.__ghcnRendererFrames)window.__ghcnRendererFrames.stop=true;"
            + "window.__ghcnRendererOffered=0;var sources=" + originals + ",generation=" + JSONObject.quote(generation) + ";"
            + "var stats={frames:0,englishFrames:0,partialFrames:0,placeholderFrames:0,emptyFramesAfterVisible:0,heightChanges:0,firstReadyMs:null,lastHeight:null,started:Date.now(),seenContent:false};"
            + "function shown(e){if(!e||!document.contains(e))return false;for(var p=e;p&&p.nodeType===1;p=p.parentElement){var s=getComputedStyle(p);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||parseFloat(s.opacity)===0)return false;}return e.getClientRects().length>0;}"
            + "function visibleText(e,skipOriginal){if(!e)return '';var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),n,out='';while((n=walker.nextNode())){var p=n.parentElement;if(!shown(p)||(skipOriginal&&p.closest('[data-ghcn-original]')))continue;out+=n.data;}return out.trim();}"
            + "function read(){var main=document.getElementById('content'),nodes=Array.prototype.slice.call(document.querySelectorAll('[data-ghcn-probe]')).filter(function(p){return p.getAttribute('data-ghcn-generation')===generation&&shown(p);});"
            + "var paragraphs=nodes.map(function(p){return visibleText(p,false);}),translations=nodes.map(function(p){return visibleText(p,true);}),originals=Array.prototype.slice.call(document.querySelectorAll('[data-ghcn-original]')).filter(shown).map(function(p){return visibleText(p,false);});"
            + "var text=visibleText(main,false),sourceCounts=sources.map(function(s){return text.split(s).length-1;}),translated=translations.filter(function(s){return /[\\u3400-\\u9fff]/.test(s);}).length;"
            + "var loading=Array.prototype.slice.call(document.querySelectorAll('[data-ghcn-loading]')).some(shown),code=document.getElementById('code'),link=document.getElementById('repo-link');"
            + "return {paragraphCount:nodes.length,paragraphs:paragraphs,translations:translations,originalTexts:originals,originalsVisible:originals.length,sourceCounts:sourceCounts,translatedCount:translated,allTranslated:nodes.length===sources.length&&translated===sources.length,allSource:nodes.length===sources.length&&paragraphs.every(function(p,i){return p===sources[i];}),placeholder:loading,visibleText:text,technical:document.getElementById('technical')?document.getElementById('technical').textContent:null,code:code?code.textContent:null,href:link?link.getAttribute('href'):null,afterMarker:link?link.getAttribute('data-ghcn-after'):null,frames:stats.frames,englishFrames:stats.englishFrames,partialFrames:stats.partialFrames,placeholderFrames:stats.placeholderFrames,emptyFramesAfterVisible:stats.emptyFramesAfterVisible,firstReadyMs:stats.firstReadyMs,heightChanges:stats.heightChanges,lastHeight:stats.lastHeight,offeredRequests:window.__ghcnRendererOffered||0};}"
            + "var monitor={stop:false,read:read};window.__ghcnRendererFrames=monitor;"
            + "function tick(){if(monitor.stop)return;var view=read();stats.frames++;if(view.sourceCounts.some(function(n){return n>0;}))stats.englishFrames++;if((view.paragraphCount>0&&view.paragraphCount<sources.length)||(view.translatedCount>0&&view.translatedCount<sources.length))stats.partialFrames++;if(view.placeholder)stats.placeholderFrames++;if(stats.seenContent&&view.paragraphCount===0&&!view.placeholder)stats.emptyFramesAfterVisible++;if(view.paragraphCount>0||view.placeholder)stats.seenContent=true;if(view.allTranslated&&stats.firstReadyMs===null)stats.firstReadyMs=Date.now()-stats.started;var h=window.github&&window.github._lastHeight;if(typeof h==='number'&&h!==stats.lastHeight){stats.lastHeight=h;stats.heightChanges++;}requestAnimationFrame(tick);}requestAnimationFrame(tick);return true;})()";
        require("true".equals(eval(javascript)), "Frame monitor did not start");
    }

    private void assertAtomic(JSONObject document, String stage) throws Exception {
        JSONObject frame = document.getJSONObject("frame");
        require(document.optInt("hookVersion") == 2, "Production JS API v2 is not installed");
        require(frame.optInt("frames") > 0, stage + " had no observable animation frames");
        require(frame.has("firstReadyMs") && !frame.isNull("firstReadyMs"), stage + " never sampled a fully translated animation frame");
        require(frame.optInt("englishFrames") == 0, stage + " exposed English source before translation: " + frame);
        require(frame.optInt("partialFrames") == 0, stage + " exposed a partially translated document: " + frame);
        require(frame.optInt("emptyFramesAfterVisible") == 0, stage + " lost both placeholder and content");
        evidence.putString(stage + "_frame_metrics", frame.toString());
        main(() -> {
            try {
                JSONObject height = new JSONObject();
                Field cached = web.getClass().getDeclaredField("y"); cached.setAccessible(true);
                height.put("native_cached_height", cached.getInt(web));
                height.put("view_height", web.getHeight());
                height.put("content_height", web.getContentHeight());
                height.put("holder_height", holder.getHeight());
                evidence.putString(stage + "_native_height", height.toString());
            } catch (Exception error) { throw new IllegalStateException("Native height observation failed", error); }
        });
    }

    private static void assertDisplay(JSONObject document, String paragraph, JSONArray translations, boolean bilingual) throws Exception {
        JSONObject frame = document.getJSONObject("frame");
        require(frame.getInt("paragraphCount") == PARAGRAPHS && translations.length() == PARAGRAPHS, "Fixture paragraph count changed");
        require(frame.optInt("originalsVisible") == (bilingual ? PARAGRAPHS : 0), "Bilingual original spans accumulated or remained visible");
        JSONArray paragraphs = frame.getJSONArray("paragraphs"), sourceCounts = frame.getJSONArray("sourceCounts");
        for (int index = 0; index < PARAGRAPHS; index++) {
            String visible = paragraphs.getString(index), translated = translations.getString(index);
            require(sourceCounts.getInt(index) == (bilingual ? 1 : 0), "Original text is missing or duplicated: " + source(paragraph, index));
            require(!translated.isEmpty() && occurrences(visible, translated) == 1, "Translation is missing or duplicated in paragraph " + index);
        }
        require(CODE.equals(frame.getString("code")), "Code changed while switching display mode");
        require("FLOSS".equals(frame.getString("technical")), "Unchanged technical term was duplicated in bilingual mode");
        require(LINK.equals(frame.getString("href")), "Repository link address changed");
        require("marked".equals(frame.optString("afterMarker")), "Production post-render callback was not retained/replayed");
    }

    private static int occurrences(String text, String needle) {
        int count = 0, from = 0, found;
        while (!needle.isEmpty() && (found = text.indexOf(needle, from)) >= 0) { count++; from = found + needle.length(); }
        return count;
    }

    private static boolean allTranslated(JSONObject document) {
        JSONObject frame = document == null ? null : document.optJSONObject("frame");
        return frame != null && frame.optBoolean("allTranslated")
            && frame.has("firstReadyMs") && !frame.isNull("firstReadyMs");
    }

    private String eval(String javascript) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main(() -> web.evaluateJavascript(javascript, value -> { result.set(value); done.countDown(); }));
        require(done.await(Math.min(5000, Math.max(1, remaining())), TimeUnit.MILLISECONDS), "Renderer JavaScript callback timed out");
        return result.get();
    }
    private void main(Runnable work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { work.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new Exception("Renderer main-thread operation failed", error.get());
    }
    private long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    private static boolean chinese(String text) { return text != null && text.matches("(?s).*[\\u3400-\\u9fff].*"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
