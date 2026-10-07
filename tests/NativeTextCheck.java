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
        System.out.println("Native description HTML checks passed: exact tags, links, quotes, comments, malformed input.");
    }
}
