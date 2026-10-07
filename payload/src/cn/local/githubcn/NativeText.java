package cn.local.githubcn;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only called from the original repository short-description Compose renderer. */
public final class NativeText {
    private static Handler main;
    private static ContentResolver resolver;
    private static final WeakHashMap<Object, Boolean> SCOPES = new WeakHashMap<>();
    private static final WeakHashMap<TextView, TextRecord> VIEWS = new WeakHashMap<>();
    private static final HashSet<String> PENDING = new HashSet<>();
    private static final HashMap<String, Long> RETRY = new HashMap<>();
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 80; }
    };
    private static final Pattern TAG = Pattern.compile("^<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)");
    private static final Pattern SKIP = Pattern.compile("(?i)pre|code|kbd|samp|script|style|svg|input|textarea|select|button");
    private NativeText() {}

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    public static String render(final String html, Object composer) {
        if (html == null || html.isEmpty()) return html;
        try {
            Object scope = composer.getClass().getMethod("z").invoke(composer);
            if (scope != null) {
                // Mark this restart scope as used so Compose retains its restart callback.
                Field flags = scope.getClass().getField("b");
                flags.setInt(scope, flags.getInt(scope) | 1);
                SCOPES.put(scope, Boolean.TRUE);
            }
            if (resolver == null) {
                ClassLoader loader = composer.getClass().getClassLoader();
                Object contextKey = Class.forName("g52", true, loader).getField("b").get(null);
                Method readLocal = composer.getClass().getMethod("j", Class.forName("qgv", true, loader));
                Context context = (Context) readLocal.invoke(composer, contextKey);
                resolver = context.getApplicationContext().getContentResolver();
            }
        } catch (Exception ignored) { return html; }
        if (!Immersive.enabled() || Immersive.target().equals("en")) return html;
        final String language = Immersive.target(), key = language + '\u0000' + html;
        synchronized (NativeText.class) {
            String cached = CACHE.get(key);
            if (cached != null) return cached;
            Long retry = RETRY.get(key);
            if (PENDING.contains(key) || (retry != null && retry > System.currentTimeMillis())) return html;
            PENDING.add(key);
        }
        try {
            Immersive.execute(() -> {
                boolean success = false;
                try {
                    String translated = translateHtml(html, language);
                    synchronized (NativeText.class) { CACHE.put(key, translated); RETRY.remove(key); }
                    success = true;
                } catch (Exception ignored) {
                    synchronized (NativeText.class) { RETRY.put(key, System.currentTimeMillis() + 60000); }
                    main().postDelayed(NativeText::refresh, 60100);
                } finally {
                    synchronized (NativeText.class) { PENDING.remove(key); }
                }
                if (success) refresh();
            });
        } catch (RuntimeException full) {
            synchronized (NativeText.class) {
                PENDING.remove(key); RETRY.put(key, System.currentTimeMillis() + 15000);
            }
            main().postDelayed(NativeText::refresh, 15100);
        }
        return html;
    }
    public static void refresh() {
        main().post(() -> {
            for (Object scope : new ArrayList<>(SCOPES.keySet())) {
                try { scope.getClass().getMethod("b", Object.class).invoke(scope, new Object[]{null}); }
                catch (Exception ignored) { /* Disposed scopes are harmless and weakly retained. */ }
            }
            for (TextRecord record : new ArrayList<>(VIEWS.values())) {
                TextView view = record.view.get();
                if (view != null) attach(view);
            }
        });
    }
    /** Caller restricts this to repository descriptions, never repository names or user handles. */
    public static void attach(final TextView view) {
        if (view == null || view instanceof EditText) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { main().post(() -> attach(view)); return; }
        if (resolver == null) resolver = view.getContext().getApplicationContext().getContentResolver();
        TextRecord record = VIEWS.get(view);
        if (record == null) {
            record = new TextRecord(view); VIEWS.put(view, record);
        }
        final TextRecord r = record;
        String current = view.getText().toString();
        if (!current.equals(r.original.toString()) && !current.equals(r.translated)) {
            r.original = view.getText(); r.translated = null; r.pending = false;
            r.retryAt = 0; r.generation++;
        }
        String language = Immersive.target();
        boolean on = Immersive.enabled() && !language.equals("en");
        if (!language.equals(r.language) || on != r.enabled) {
            if (r.translated != null && current.equals(r.translated)) view.setText(r.original);
            r.translated = null; r.pending = false; r.retryAt = 0; r.generation++;
            r.language = language; r.enabled = on;
        }
        final String source = r.original.toString();
        if (!on || r.translated != null || r.pending || r.retryAt > System.currentTimeMillis() || !english(source.trim())) return;
        final String key = language + "\u0000text\u0000" + source;
        final long generation = r.generation;
        synchronized (NativeText.class) {
            String cached = CACHE.get(key);
            if (cached != null) { r.translated = cached; view.setText(cached); return; }
        }
        r.pending = true;
        try {
            Immersive.execute(() -> {
                String translated = null;
                try {
                    translated = translatePlain(source, language);
                    synchronized (NativeText.class) { CACHE.put(key, translated); }
                } catch (Exception ignored) {}
                final String result = translated;
                main().post(() -> {
                    TextView target = r.view.get();
                    if (target == null || r.generation != generation) return;
                    r.pending = false;
                    if (!Immersive.enabled() || !Immersive.target().equals(language)) { attach(target); return; }
                    if (!target.getText().toString().equals(source)) { attach(target); return; }
                    if (result == null) { r.retryAt = System.currentTimeMillis() + 60000; retry(r, 60100); return; }
                    r.translated = result; target.setText(result);
                });
            });
        } catch (RuntimeException full) { r.pending = false; r.retryAt = System.currentTimeMillis() + 15000; retry(r, 15100); }
    }
    private static void retry(TextRecord record, long delay) {
        main().postDelayed(() -> {
            TextView view = record.view.get();
            if (view != null) attach(view);
        }, delay);
    }
    private static final class TextRecord {
        final WeakReference<TextView> view;
        CharSequence original;
        String translated, language;
        boolean enabled, pending;
        long generation, retryAt;
        TextRecord(TextView value) { view = new WeakReference<>(value); original = value.getText(); }
    }
    private static boolean english(String text) {
        return text.length() >= 3 && text.length() <= 24000 && text.matches("(?s).*[A-Za-z].*") &&
            !text.matches("(?s).*[\\u3400-\\u9fff\\u3040-\\u30ff].*") &&
            !text.matches("(?i)@?[A-Za-z0-9_.-]+|https?://\\S+");
    }
    private static String translatePlain(String plain, String language) throws Exception {
        String text = plain.trim();
        Bundle extras = new Bundle();
        extras.putString("text", text); extras.putString("target", language);
        Bundle answer = resolver.call(Uri.parse("content://cn.local.repochinese.translation"), "translate", null, extras);
        String translated = answer == null ? null : answer.getString("text");
        if (answer == null || answer.getString("error") != null || translated == null || translated.trim().isEmpty())
            throw new IOException("translation unavailable");
        int first = plain.indexOf(text), last = first + text.length();
        return plain.substring(0, first) + translated.trim() + plain.substring(last);
    }
    private static String translateHtml(String html, String language) throws Exception {
        StringBuilder output = new StringBuilder();
        int excluded = 0;
        for (String part : splitHtml(html)) {
            if (part.startsWith("<")) {
                Matcher tag = TAG.matcher(part);
                if (tag.find() && SKIP.matcher(tag.group(2)).matches()) {
                    if (!tag.group(1).isEmpty()) excluded = Math.max(0, excluded - 1);
                    else if (!part.endsWith("/>")) excluded++;
                }
                output.append(part);
                continue;
            }
            if (excluded > 0) { output.append(part); continue; }
            String plain = Html.fromHtml(part, Html.FROM_HTML_MODE_LEGACY).toString();
            String text = plain.trim();
            if (!english(text)) {
                output.append(part); continue;
            }
            output.append(TextUtils.htmlEncode(translatePlain(plain, language)));
        }
        return output.toString();
    }
    /** Preserve original markup byte-for-byte, including > inside quoted attributes. */
    static ArrayList<String> splitHtml(String html) {
        ArrayList<String> pieces = new ArrayList<>();
        int position = 0;
        while (position < html.length()) {
            if (html.charAt(position) != '<') {
                int end = html.indexOf('<', position);
                if (end < 0) end = html.length();
                pieces.add(html.substring(position, end)); position = end;
                continue;
            }
            int end;
            if (html.startsWith("<!--", position)) {
                end = html.indexOf("-->", position + 4);
                end = end < 0 ? html.length() : end + 3;
            } else {
                char quote = 0;
                end = position + 1;
                for (; end < html.length(); end++) {
                    char c = html.charAt(end);
                    if (quote != 0) { if (c == quote) quote = 0; }
                    else if (c == '\'' || c == '"') quote = c;
                    else if (c == '>') { end++; break; }
                }
            }
            pieces.add(html.substring(position, end)); position = end;
        }
        return pieces;
    }
}
