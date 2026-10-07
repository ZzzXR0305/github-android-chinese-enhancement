package cn.local.githubcn.check;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Uses synthetic search terms, local signed translation service, and unauthenticated public repository search. */
public final class BilingualCheck {
    public static Bundle run(Instrumentation runner, Context target, long deadline) {
        Bundle evidence = new Bundle();
        long started = SystemClock.elapsedRealtime();
        try {
            Class<?> query = Class.forName("cn.local.githubcn.SearchQuery", true, target.getClassLoader());
            Class<?> languages = Class.forName("cn.local.githubcn.LanguageOptions", true, target.getClassLoader());
            String[] codes = (String[]) languages.getField("CODES").get(null);
            Bundle supported = provider(target, "languages", null, deadline);
            require(codes.length == 59 && supported != null && supported.getStringArrayList("languages") != null, "The supported language catalog is missing");
            require(new HashSet<>(Arrays.asList(codes)).equals(new HashSet<>(supported.getStringArrayList("languages"))), "Native language choices differ from the installed ML Kit SDK");
            for (String code : codes) {
                require((Boolean) languages.getMethod("supports", String.class).invoke(null, code), "Native catalog rejects its own supported code: " + code);
                Bundle noDownload = new Bundle();
                noDownload.putString("text", "synthetic"); noDownload.putString("source", code); noDownload.putString("target", code);
                Bundle accepted = provider(target, "translate", noDownload, deadline);
                require(accepted != null && "synthetic".equals(accepted.getString("text")), "Supported source/target rejected: " + code);
            }
            String[][] identification = {
                {"en", "This application helps developers browse open source projects and translate repository documentation."},
                {"de", "Diese Anwendung hilft Entwicklern, Open-Source-Projekte zu durchsuchen und die Dokumentation ihrer Repositories zu übersetzen."},
                {"fr", "Cette application aide les développeurs à parcourir les projets open source et à traduire la documentation des dépôts."}
            };
            for (String[] sample : identification) {
                Bundle request = new Bundle(); request.putString("text", sample[1]);
                Bundle identified = provider(target, "identify", request, deadline);
                require(identified != null && sample[0].equals(identified.getString("language")), "Local language identifier failed for " + sample[0] + ": " + identified);
                evidence.putString("multilingual_identified_" + sample[0], identified.getString("language"));
            }
            evidence.putInt("multilingual_language_count", codes.length);
            evidence.putBoolean("multilingual_catalog_matches_sdk", true);
            String english = translate(target, "马尾辫", "zh", "en", deadline);
            require(english.toLowerCase(Locale.ROOT).contains("ponytail"), "Chinese model did not translate 马尾辫 to ponytail: " + english);
            String keywords = (String) method(query, "translatedKeywords", String.class, String.class).invoke(null, "马尾辫skill", english);
            require(keywords.toLowerCase(Locale.ROOT).contains("ponytail") && keywords.contains("skill"), "Translated query lost ponytail or skill: " + keywords);
            String searchQuery = (String) method(query, "build", String.class).invoke(null, keywords);
            evidence.putString("bilingual_query", searchQuery);
            evidence.putString("bilingual_zh_to_en", english);

            String chinese = translate(target, "ponytail", "en", "zh", deadline);
            require(chinese.matches("(?s).*[\\u3400-\\u9fff].*"), "English model did not return Chinese: " + chinese);
            String reverse = (String) method(query, "translatedKeywords", String.class, String.class).invoke(null, "ponytail skill", chinese);
            require(reverse.contains("skill"), "Reverse query translated the skill marker: " + reverse);
            evidence.putString("bilingual_en_to_zh", chinese);
            evidence.putString("bilingual_reverse_query", reverse);
            for (String untouched : new String[]{"codex++", "马尾辫 in:readme", "https://github.com/example/project", "owner/repo"})
                require(!(Boolean) method(query, "canTranslate", String.class).invoke(null, untouched), "Advanced/identifier search should remain untouched: " + untouched);

            Class<?> search = Class.forName("cn.local.githubcn.SearchActivity", true, target.getClassLoader());
            AtomicReference<Object> activity = new AtomicReference<>();
            runner.runOnMainSync(() -> {
                try { activity.set(search.getConstructor().newInstance()); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            Method fetch = method(search, "fetch", String.class);
            FutureTask<JSONObject> request = new FutureTask<>(() -> (JSONObject) fetch.invoke(activity.get(), searchQuery + " in:name,description,readme"));
            new Thread(request, "githubcn-bilingual-public-check").start();
            JSONObject reply = request.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            require(reply.getJSONArray("items").length() > 0, "No public repository matched bilingual ponytail skill search");
            evidence.putLong("bilingual_search_total_count", reply.optLong("total_count"));
            evidence.putInt("bilingual_search_display_count", reply.getJSONArray("items").length());
            evidence.putBoolean("bilingual_terms_preserved", true);
            evidence.putBoolean("bilingual_local_both_directions", true);
            evidence.putBoolean("bilingual_passed", true);
            Bundle status = new Bundle();
            status.putString("stream", "59 language choices match ML Kit; local en/de/fr identification passed. Chinese/English search translated 马尾辫skill → " + keywords + "; public GitHub returned " + reply.optLong("total_count") + " repositories\n");
            runner.sendStatus(0, status);
        } catch (Throwable error) {
            evidence.putBoolean("bilingual_passed", false);
            evidence.putString("bilingual_failure", error.toString());
            while (error.getCause() != null) error = error.getCause();
            evidence.putString("bilingual_failure_cause", error.toString());
        }
        evidence.putLong("bilingual_elapsed_ms", SystemClock.elapsedRealtime() - started);
        return evidence;
    }

    /** Separate model check so a first-download failure cannot prevent other feature checks from running. */
    public static Bundle runFrench(Instrumentation runner, Context target, long deadline) {
        Bundle evidence = new Bundle();
        evidence.putBoolean("french_model_ready", false);
        evidence.putBoolean("multilingual_french_model", false);
        long started = SystemClock.elapsedRealtime();
        try {
            String original = "This application helps developers browse open source projects.";
            String french = translate(target, original, "en", "fr", deadline);
            require(!french.equals(original) && !french.matches("(?s).*[\\u3400-\\u9fff].*"), "French target returned the original text or Chinese: " + french);
            require(french.toLowerCase(Locale.ROOT).matches("(?s).*(cette|développeurs|projets).*"), "French model output did not contain expected French words: " + french);
            evidence.putString("multilingual_french_translated", french);
            evidence.putBoolean("multilingual_french_model", true);
            evidence.putBoolean("french_model_ready", true);
            Bundle status = new Bundle();
            status.putString("stream", "English → French model translated the synthetic sentence: " + french + "\n");
            runner.sendStatus(0, status);
        } catch (Throwable error) {
            evidence.putString("french_failure", error.toString());
            while (error.getCause() != null) error = error.getCause();
            evidence.putString("french_failure_cause", error.toString());
            Bundle status = new Bundle();
            status.putString("stream", "French model readiness check failed: " + error + "\n");
            runner.sendStatus(0, status);
        }
        evidence.putLong("french_elapsed_ms", SystemClock.elapsedRealtime() - started);
        return evidence;
    }

    private static String translate(Context context, String text, String source, String target, long deadline) throws Exception {
        Bundle extras = new Bundle();
        extras.putString("text", text); extras.putString("source", source); extras.putString("target", target);
        Bundle reply = provider(context, "translate", extras, deadline);
        require(reply != null && reply.getString("text") != null, "Signed translation provider failed: " + (reply == null ? "empty reply" : reply.getString("error")));
        return reply.getString("text");
    }
    private static Bundle provider(Context context, String method, Bundle extras, long deadline) throws Exception {
        FutureTask<Bundle> task = new FutureTask<>(() -> context.getContentResolver().call(Uri.parse("content://cn.local.repochinese.translation"), method, null, extras));
        new Thread(task, "githubcn-bilingual-model-check").start();
        return task.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }
    private static Method method(Class<?> owner, String name, Class<?>... types) throws Exception {
        Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
