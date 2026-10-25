package cn.local.githubcn;
import java.util.ArrayList;
public class NativeTextCheck {
    public static void main(String[] args) {
        String html = "<p title=\"a > b\">Readable description <a href='https://example.com?a=1&b=2'>linked words</a>.</p><!-- > comment --><pre>npm install x</pre>";
        ArrayList<String> pieces = NativeText.splitHtml(html);
        if (!String.join("", pieces).equals(html)) throw new AssertionError("Markup changed");
        if (!pieces.get(0).equals("<p title=\"a > b\">")) throw new AssertionError("Quoted tag terminator");
        if (!pieces.contains("<a href='https://example.com?a=1&b=2'>")) throw new AssertionError("Link changed");
        if (!pieces.contains("<!-- > comment -->")) throw new AssertionError("Comment split");
        if (!NativeText.splitHtml("<p unfinished='").get(0).equals("<p unfinished='")) throw new AssertionError("Malformed markup");
        String original = "Read the documentation.", translated = "阅读文档。";
        if (!NativeText.displayText(original, translated, false).equals(translated)) throw new AssertionError("Translation-only display");
        if (!NativeText.displayText(original, translated, true).equals(original + '\n' + translated)) throw new AssertionError("Bilingual display");
        if (!NativeText.displayText(original, original, true).equals(original)) throw new AssertionError("Untranslated text duplicated");
        if (!NativeText.displayHtml("<p>Original</p>", "<p>译文</p>", true)
            .equals("<div><p>Original</p></div><div><p>译文</p></div>")) throw new AssertionError("Bilingual HTML blocks");
        if (!NativeText.displayHtml(html, html, true).equals(html)) throw new AssertionError("Protected-only markup duplicated");
        System.out.println("Native description checks passed: exact markup, links, quotes, comments and bilingual display.");
    }
}
