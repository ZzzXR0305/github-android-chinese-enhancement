package cn.local.githubcn.engine;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.components.ComponentRegistrar;
import com.google.mlkit.common.internal.CommonComponentRegistrar;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.languageid.bundled.internal.ThickLanguageIdRegistrar;
import com.google.mlkit.nl.languageid.internal.LanguageIdRegistrar;
import com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar;
import com.google.mlkit.nl.translate.TranslateLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same-process engine loaded from the host APK, never an installed helper app. */
public final class Translation {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean initialized;
    private static LanguageIdentifier identifier;

    private Translation() {}

    private static synchronized void initialize(Context context) {
        if (initialized) return;
        if (context == null) throw new IllegalArgumentException("Context is required");
        // Keep SDK discovery inside this isolated loader; all files and assets stay in the host UID.
        Context engineContext = new ContextWrapper(context.getApplicationContext()) {
            @Override public ClassLoader getClassLoader() { return Translation.class.getClassLoader(); }
            @Override public Context getApplicationContext() { return this; }
        };
        MlKitContext.initializeIfNeeded(engineContext, Arrays.<ComponentRegistrar>asList(
            new CommonComponentRegistrar(), new NaturalLanguageTranslateRegistrar(),
            new LanguageIdRegistrar(), new ThickLanguageIdRegistrar()));
        initialized = true;
    }

    /** Called reflectively with boot-class-loader Android types. Run translation off the UI thread. */
    public static Bundle call(Context context, String method, Bundle extras) {
        if ("languages".equals(method)) {
            Bundle answer = new Bundle();
            ArrayList<String> codes = new ArrayList<>(TranslateLanguage.getAllLanguages());
            Collections.sort(codes);
            answer.putStringArrayList("languages", codes);
            return answer;
        }
        if (!("identify".equals(method) || "translate".equals(method)) || extras == null)
            return error("不支持的翻译请求");
        String text = extras.getString("text", "");
        if (text == null || text.length() > 24000) return error("文本长度不受支持");
        if ("identify".equals(method)) {
            String script = TextTools.scriptLanguage(text);
            if (!script.isEmpty()) return value("language", script);
        } else {
            String source = language(extras.getString("source", "en"));
            String target = language(extras.getString("target", "zh"));
            if (source == null || target == null) return error("翻译语言不受支持");
            if (source.equals(target) || text.isEmpty()) return value("text", text);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return error("请在后台调用内置翻译");
        try { initialize(context); }
        catch (Exception | LinkageError failure) {
            android.util.Log.w("GitHubCNEngine", "Engine initialization failed", failure);
            return error("内置翻译引擎初始化失败：" + failure.getClass().getSimpleName());
        }
        final Bundle answer = new Bundle();
        final CountDownLatch finished = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                if ("identify".equals(method)) {
                    if (identifier == null) identifier = LanguageIdentification.getClient();
                    identifier.identifyLanguage(text).addOnSuccessListener(code -> {
                        String recognized = language(code);
                        if (recognized == null) answer.putString("error", "无法确定来源语言，请手动选择");
                        else answer.putString("language", recognized);
                        finished.countDown();
                    }).addOnFailureListener(failure -> {
                        answer.putString("error", "本机语言识别暂不可用，请手动选择来源语言");
                        finished.countDown();
                    });
                } else {
                    String source = language(extras.getString("source", "en"));
                    String target = language(extras.getString("target", "zh"));
                    Engine.get(source, target).translate(text, new Engine.Result() {
                        public void done(String result) { answer.putString("text", result); finished.countDown(); }
                        public void error(String message) { answer.putString("error", message); finished.countDown(); }
                    });
                }
            } catch (Exception | LinkageError failure) {
                android.util.Log.w("GitHubCNEngine", "Engine request failed", failure);
                answer.putString("error", "内置翻译暂不可用：" + failure.getClass().getSimpleName());
                finished.countDown();
            }
        });
        try {
            if (!finished.await("identify".equals(method) ? 20 : 120, TimeUnit.SECONDS))
                return error("内置模型准备或翻译超时，请连接可访问模型服务的 Wi-Fi 后重试");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("翻译已取消");
        }
        return answer;
    }

    private static String language(String tag) {
        if (tag == null) return null;
        String code = TranslateLanguage.fromLanguageTag(tag);
        return code != null && TranslateLanguage.getAllLanguages().contains(code) ? code : null;
    }
    private static Bundle error(String message) { return value("error", message); }
    private static Bundle value(String key, String value) {
        Bundle result = new Bundle(); result.putString(key, value); return result;
    }
}
