package cn.local.githubcn.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TextTools {
    static boolean english(String text) { return text != null && Pattern.compile("[A-Za-z]{2,}").matcher(text).find(); }
    static boolean letters(String text) { return text != null && Pattern.compile("[\\p{L}]").matcher(text).find(); }
    static String scriptLanguage(String text) {
        if (Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}]").matcher(text).find()) return "ja";
        if (Pattern.compile("[\\p{IsHangul}]").matcher(text).find()) return "ko";
        if (Pattern.compile("[\\p{IsHan}]").matcher(text).find()) return "zh";
        return "";
    }
    static List<String> chunks(String text) {
        ArrayList<String> result = new ArrayList<>();
        while (!text.isEmpty()) {
            if (text.length() <= 1800) { result.add(text); break; }
            int cut = text.lastIndexOf('\n', 1800);
            if (cut < 700) cut = text.lastIndexOf(' ', 1800);
            if (cut < 700) cut = 1800;
            if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
            result.add(text.substring(0, cut)); text = text.substring(cut);
        }
        return result;
    }
    static String keepTechnical(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```") || trimmed.matches("https?://\\S+") ||
            trimmed.matches("(?s)(\\$ |npm |pip |git |sudo |docker |pnpm |yarn |curl |wget ).*")) return text;
        return null;
    }
    static final class Protected {
        final LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
        final String input;
        Protected(String text, boolean chineseGlossary) {
            String common = chineseGlossary ? "|\\b(?i:Free/Libre Open Source Software|pull requests?|repository|repositories|license)\\b" : "";
            Matcher matches = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`|https?://[^\\s)]+|\\b(?i:Google Play Services|Play Services|FLOSS)\\b" + common +
                "|\\b[A-Za-z0-9_]*[a-z][A-Z][A-Za-z0-9_]*\\b", Pattern.MULTILINE).matcher(text);
            StringBuffer prepared = new StringBuffer(); int index = 0;
            while (matches.find()) {
                String token = "ZXQ" + index++ + "QXZ";
                tokens.put(token, chineseGlossary ? glossary(matches.group()) : matches.group());
                matches.appendReplacement(prepared, token);
            }
            matches.appendTail(prepared); input = prepared.toString();
        }
        private static String glossary(String text) {
            switch (text.toLowerCase(Locale.ROOT)) {
                case "google play services": case "play services": return "Google Play 服务";
                case "floss": return "FLOSS";
                case "free/libre open source software": return "自由开源软件";
                case "license": return "许可证";
                case "pull request": case "pull requests": return "拉取请求";
                case "repository": case "repositories": return "仓库";
                default: return text;
            }
        }
        String restore(String text) {
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                String pattern = entry.getKey().replace("ZXQ", "Z\\s*X\\s*Q\\s*").replace("QXZ", "\\s*Q\\s*X\\s*Z");
                text = text.replaceAll("(?i)" + pattern, Matcher.quoteReplacement(entry.getValue()));
            }
            return text;
        }
    }
}
