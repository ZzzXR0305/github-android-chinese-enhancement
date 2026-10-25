package cn.local.githubcn.engine;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread-owned translator state. Public requests arrive through Translation.call(). */
final class Engine {
    interface Result { void done(String value); void error(String message); }
    private static final Map<String, Engine> INSTANCES = new HashMap<>();
    private final Translator translator;
    private final String source, target;
    private boolean ready, downloading;
    private final List<Result> waiting = new ArrayList<>();
    private final LinkedHashMap<String, String> cache = new LinkedHashMap<String, String>(100, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 160; }
    };

    private Engine(String source, String target) {
        this.source = source; this.target = target;
        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(target).build());
    }
    static Engine get(String source, String target) {
        String pair = source + ":" + target;
        Engine engine = INSTANCES.get(pair);
        if (engine == null) { engine = new Engine(source, target); INSTANCES.put(pair, engine); }
        return engine;
    }
    private void prepare(Result result) {
        if (ready) { result.done("内置翻译模型已就绪"); return; }
        waiting.add(result);
        if (downloading) return;
        downloading = true;
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener(unused -> {
                ready = true; downloading = false;
                List<Result> requests = new ArrayList<>(waiting); waiting.clear();
                for (Result request : requests) {
                    try { request.done("内置翻译模型已就绪"); }
                    catch (Exception | LinkageError failure) { failed(request, failure); }
                }
            }).addOnFailureListener(failure -> {
                downloading = false;
                List<Result> requests = new ArrayList<>(waiting); waiting.clear();
                for (Result request : requests) request.error("内置模型准备失败，请连接可访问 Google 模型服务的 Wi-Fi 后重试。\n" + failure.getMessage());
            });
    }
    void translate(String input, Result result) {
        try { translateReady(input, result); }
        catch (Exception | LinkageError failure) { failed(result, failure); }
    }
    private void translateReady(String input, Result result) {
        if (!ready) {
            prepare(new Result() {
                public void done(String unused) { translate(input, result); }
                public void error(String message) { result.error(message); }
            });
            return;
        }
        TextTools.Protected protectedText = new TextTools.Protected(input, source.equals("en") && target.equals("zh"));
        List<String> parts = new ArrayList<>();
        for (String paragraph : protectedText.input.split("\\n\\s*\\n")) parts.addAll(TextTools.chunks(paragraph));
        translatePart(parts, 0, new StringBuilder(), new Result() {
            public void done(String value) { result.done(protectedText.restore(value)); }
            public void error(String message) { result.error(message); }
        });
    }
    private void translatePart(List<String> parts, int index, StringBuilder output, Result result) {
        try { translateNext(parts, index, output, result); }
        catch (Exception | LinkageError failure) { failed(result, failure); }
    }
    private void translateNext(List<String> parts, int index, StringBuilder output, Result result) {
        if (index >= parts.size()) { result.done(output.toString()); return; }
        if (index > 0) output.append("\n\n");
        String text = parts.get(index);
        boolean translatable = source.equals("en") ? TextTools.english(text) : TextTools.letters(text);
        if (TextTools.keepTechnical(text) != null || !translatable || text.trim().matches("ZXQ[0-9]+QXZ")) {
            output.append(text); translatePart(parts, index + 1, output, result); return;
        }
        String cached = cache.get(text);
        if (cached != null) { output.append(cached); translatePart(parts, index + 1, output, result); return; }
        translator.translate(text).addOnSuccessListener(value -> {
            try {
                cache.put(text, value); output.append(value); translatePart(parts, index + 1, output, result);
            } catch (Exception | LinkageError failure) { failed(result, failure); }
        }).addOnFailureListener(failure -> result.error("翻译失败：" + failure.getMessage()));
    }
    private static void failed(Result result, Throwable failure) {
        android.util.Log.w("GitHubCNEngine", "Translation callback failed", failure);
        result.error("内置翻译暂不可用：" + failure.getClass().getSimpleName());
    }
}
