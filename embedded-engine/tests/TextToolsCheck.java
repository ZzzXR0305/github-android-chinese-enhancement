package cn.local.githubcn.engine;

public final class TextToolsCheck {
    public static void main(String[] args) {
        assert TextTools.scriptLanguage("こんにちは").equals("ja");
        assert TextTools.scriptLanguage("한국어").equals("ko");
        assert TextTools.scriptLanguage("中文").equals("zh");
        String text = "Repository `git status` https://github.com/test/repo CamelCase";
        TextTools.Protected protectedText = new TextTools.Protected(text, true);
        String restored = protectedText.restore(protectedText.input);
        assert restored.equals("仓库 `git status` https://github.com/test/repo CamelCase") : restored;
        assert new TextTools.Protected("Repository", false).restore("Repository").equals("Repository");
        assert TextTools.keepTechnical("git status").equals("git status");
        String longText = "a".repeat(1799) + "😀" + "b".repeat(2000);
        java.util.List<String> chunks = TextTools.chunks(longText);
        assert String.join("", chunks).equals(longText);
        for (String chunk : chunks) {
            assert chunk.length() <= 1800;
            assert !Character.isHighSurrogate(chunk.charAt(chunk.length() - 1));
        }
        System.out.println("Embedded text protections and chunking PASS");
    }
}
