package cn.local.githubcn;

import java.util.Arrays;
import java.util.HashSet;

public final class LanguageOptionsCheck {
    public static void main(String[] args) {
        String[] expected = "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu id is it ja ka kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw ta te th tl tr uk ur vi zh".split(" ");
        HashSet<String> actual = new HashSet<>(Arrays.asList(LanguageOptions.CODES));
        check(LanguageOptions.CODES.length == 59 && actual.size() == 59, "exactly 59 unique language codes");
        check(actual.equals(new HashSet<>(Arrays.asList(expected))), "matches the complete official ML Kit set");
        check("zh".equals(LanguageOptions.CODES[0]) && "en".equals(LanguageOptions.CODES[1]) && "ja".equals(LanguageOptions.CODES[2]) && "ko".equals(LanguageOptions.CODES[3]), "common languages have stable first positions");
        for (int i = 15; i < LanguageOptions.CODES.length; i++) check(LanguageOptions.CODES[i - 1].compareTo(LanguageOptions.CODES[i]) < 0, "remaining codes are alphabetical");
        String[] labels = LanguageOptions.labels();
        check(labels.length == 59, "label count matches codes");
        for (int i = 0; i < LanguageOptions.CODES.length; i++) {
            String code = LanguageOptions.CODES[i];
            check(LanguageOptions.supports(code) && LanguageOptions.index(code) == i && labels[i].equals(LanguageOptions.name(code)), "code/label/index compatibility: " + code);
            check(labels[i].matches(".*[\\u3400-\\u9fff].*"), "Chinese display name: " + code);
        }
        labels[0] = "changed";
        check("简体中文".equals(LanguageOptions.labels()[0]), "labels are independent copies");
        check(!LanguageOptions.supports(null) && !LanguageOptions.supports("xx") && LanguageOptions.index("auto") == -1 && "xx".equals(LanguageOptions.name("xx")), "unknown/source-auto values are not translation targets");
        System.out.println("PASS: all 59 official languages, Chinese labels, order, lookup and copy isolation");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
