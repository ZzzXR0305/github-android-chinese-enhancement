package cn.local.githubcn;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SearchQuery {
    private static final Pattern ADVANCED = Pattern.compile("(^|\\s)([\\w.-]+:|AND\\b|OR\\b|NOT\\b|-[^\\s]+)|[\"()]");
    private static final Pattern REPO = Pattern.compile("(?i)^(?:https?://(?:www\\.)?github\\.com/)?([a-z0-9_.-]+)/([a-z0-9_.-]+)(?:[/?#].*)?$");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern KANA = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern HANGUL = Pattern.compile("[\\p{IsHangul}]");
    private static final Pattern NON_LATIN = Pattern.compile("[\\p{L}&&[^\\p{IsLatin}]]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9_+.-]*");
    private static final Pattern MARKERS = Pattern.compile("(?i)\\b(?:skills?|plugins?|github|codex)\\b");

    static String build(String input) {
        String raw = input == null ? "" : input.trim();
        Matcher repo = REPO.matcher(raw);
        if (repo.matches()) {
            String name = repo.group(2).replaceFirst("(?i)\\.git$", "");
            return "repo:" + repo.group(1) + "/" + name;
        }
        if (ADVANCED.matcher(raw).find()) return raw;
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String key = nameKey(text);
        int count = key.endsWith("plusplus") ? 2 : key.endsWith("plus") ? 1 : 0;
        if (count == 0) return text;
        String stem = key.substring(0, key.length() - count * 4);
        if (stem.isEmpty()) return text;
        String stemSlug = text.replaceFirst("(?:[\\s_.-]*(?:plus|\\+))+$", "")
                .replaceAll("[\\s_.-]+", "-");
        if (stemSlug.isEmpty()) stemSlug = stem;
        // ponytail: expand a plus suffix only; general search keeps GitHub's own syntax and ranking.
        if (count == 2) return key + " OR " + stemSlug + "-plusplus OR " + stemSlug + "-plus-plus in:name";
        return key + " OR " + stemSlug + "-plus in:name";
    }

    static String nameKey(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace("+", "plus").replaceAll("[\\s_.-]", "");
    }

    static boolean canTranslate(String input) {
        String raw = input == null ? "" : input.trim();
        return !raw.isEmpty() && raw.length() <= 512 && !REPO.matcher(raw).matches()
                && !ADVANCED.matcher(raw).find() && !build(raw).contains(" OR ")
                && !translationText(raw).isEmpty();
    }

    static String translationSource(String input) {
        if (KANA.matcher(input).find()) return "ja";
        if (HANGUL.matcher(input).find()) return "ko";
        if (HAN.matcher(input).find()) return "zh";
        return "auto"; // Latin-script languages need the local identifier rather than an English guess.
    }

    static String defaultTranslationTarget(String source) { return "en".equals(source) ? "zh" : "en"; }

    static String translationText(String input) {
        Pattern preserved = NON_LATIN.matcher(input).find() ? LATIN : MARKERS;
        return preserved.matcher(input).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    static String translatedKeywords(String input, String translated) {
        String value = translated == null ? "" : translated.trim().replaceAll("^[\"'“”]+|[\"'“”。.]+$", "");
        if (value.isEmpty() || value.equalsIgnoreCase(translationText(input))) return "";
        // Search terms need to preserve identifiers such as skill even when the surrounding phrase is translated.
        Matcher kept = (NON_LATIN.matcher(input).find() ? LATIN : MARKERS).matcher(input);
        StringBuilder words = new StringBuilder(value);
        while (kept.find()) words.append(' ').append(kept.group());
        return words.toString().replaceAll("\\s+", " ").trim();
    }

    static int rank(String input, String name) {
        if (input == null || ADVANCED.matcher(input).find()) return 3;
        String wanted = nameKey(input.trim()), actual = nameKey(name);
        if (wanted.isEmpty()) return 3;
        if (actual.equals(wanted)) return 0;
        if (actual.startsWith(wanted)) return 1;
        if (actual.contains(wanted)) return 2;
        return 3;
    }
}
