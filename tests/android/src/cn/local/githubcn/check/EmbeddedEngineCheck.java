package cn.local.githubcn.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Synthetic strings only: calls the target's embedded engine, never the helper or account data. */
public final class EmbeddedEngineCheck extends Instrumentation {
    private final Bundle evidence = new Bundle();
    private final ArrayList<String> checks = new ArrayList<>();
    private Method entry;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        super.onStart();
        new Thread(this::checkEngine, "githubcn-check-embedded").start();
    }

    private void checkEngine() {
        long started = SystemClock.elapsedRealtime();
        boolean passed = false;
        try {
            Context target = getTargetContext();
            evidence.putString("target_package", target.getPackageName());
            evidence.putInt("target_uid", target.getApplicationInfo().uid);
            evidence.putInt("process_uid", Process.myUid());
            evidence.putInt("process_pid", Process.myPid());
            require("com.github.android.chinese".equals(target.getPackageName()), "Wrong target package");
            require(Process.myUid() == target.getApplicationInfo().uid, "Runner is not under the target UID");
            require(Looper.myLooper() != Looper.getMainLooper(), "Engine checks are running on the main thread");
            entry = Class.forName("cn.local.githubcn.LocalTranslation", true, target.getClassLoader())
                .getMethod("call", Context.class, String.class, Bundle.class);
            checked("target_uid_and_embedded_entry");

            Bundle catalog = call(target, "languages", new Bundle());
            ArrayList<String> languages = catalog.getStringArrayList("languages");
            require(languages != null && languages.size() == 59, "Expected 59 supported languages");
            require(new HashSet<>(languages).size() == 59, "Language catalog contains duplicates");
            require(languages.containsAll(Arrays.asList("en", "zh", "ja", "ko")), "Missing common languages");
            evidence.putInt("language_count", languages.size());
            checked("languages_59");

            String english = "This application helps developers browse open source projects.";
            String chinese = call(target, "translate", request(english, "en", "zh")).getString("text");
            require(chinese != null && !english.equals(chinese) && chinese.matches("(?s).*[\\u3400-\\u9fff].*"),
                "English to Chinese did not return Chinese");
            evidence.putString("en_to_zh", chinese);
            checked("translate_en_zh");

            Bundle japanese = call(target, "identify", request("このアプリは開発者がオープンソースのプロジェクトを閲覧するのに役立ちます。", "", ""));
            evidence.putString("identified_language", japanese.getString("language"));
            require("ja".equals(japanese.getString("language")), "Japanese language identification failed");
            checked("identify_ja");

            String originalChinese = "这个应用程序帮助开发者浏览开源项目。";
            String translatedEnglish = call(target, "translate", request(originalChinese, "zh", "en")).getString("text");
            require(translatedEnglish != null && !originalChinese.equals(translatedEnglish)
                && translatedEnglish.matches("(?s).*[A-Za-z]{2,}.*"), "Chinese to English did not return English");
            evidence.putString("zh_to_en", translatedEnglish);
            checked("translate_zh_en");

            long repeatedAt = SystemClock.elapsedRealtime();
            String repeated = call(target, "translate", request(english, "en", "zh")).getString("text");
            require(chinese.equals(repeated), "Repeated translation changed the result");
            evidence.putLong("repeat_elapsed_ms", SystemClock.elapsedRealtime() - repeatedAt);
            checked("repeat_translation");

            char[] oversized = new char[24001];
            Arrays.fill(oversized, 'a');
            Bundle rejected = invoke(target, "translate", request(new String(oversized), "en", "zh"));
            String error = rejected.getString("error");
            require(error != null && !error.trim().isEmpty(), "Oversized input was not rejected with an error");
            require(!rejected.containsKey("text"), "Oversized input unexpectedly returned translated text");
            evidence.putString("oversized_error", error);
            checked("reject_24001_chars");
            passed = true;
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            evidence.putString("error_class", cause.getClass().getName());
            evidence.putString("error_message", String.valueOf(cause.getMessage()));
            evidence.putString("failure", failure.toString());
        } finally {
            evidence.putBoolean("passed", passed);
            evidence.putStringArrayList("checks", new ArrayList<>(checks));
            evidence.putInt("check_count", checks.size());
            evidence.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
            evidence.putString("stream", (passed ? "PASS" : "FAIL") + ": embedded translation engine"
                + (passed ? "" : " - " + evidence.getString("error_class") + ": " + evidence.getString("error_message")) + "\n");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, evidence);
        }
    }

    private Bundle invoke(Context target, String method, Bundle request) throws Exception {
        Object answer = entry.invoke(null, target, method, request);
        require(answer instanceof Bundle, method + " returned no result Bundle");
        return (Bundle) answer;
    }

    private Bundle call(Context target, String method, Bundle request) throws Exception {
        Bundle answer = invoke(target, method, request);
        require(answer.getString("error") == null, method + " failed: " + answer.getString("error"));
        return answer;
    }

    private static Bundle request(String text, String source, String target) {
        Bundle request = new Bundle();
        request.putString("text", text);
        request.putString("source", source);
        request.putString("target", target);
        return request;
    }

    private void checked(String name) {
        checks.add(name);
        Bundle status = new Bundle();
        status.putString("stream", "PASS: " + name + "\n");
        sendStatus(0, status);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
