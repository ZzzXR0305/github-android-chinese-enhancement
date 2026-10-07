package cn.local.repochinese;

/** Pure Java check: translated prose follows the target language while technical identifiers remain exact. */
public final class TextToolsCheck {
    public static void main(String[] args) {
        String prose="repository license pull request Free/Libre Open Source Software";
        TextTools.Protected chinese=new TextTools.Protected(prose);
        assert chinese.restore(chinese.input).equals("仓库 许可证 拉取请求 自由开源软件");
        TextTools.Protected other=new TextTools.Protected(prose,false);
        assert other.input.equals(prose) : "Common words must reach the Japanese/Spanish model";
        assert other.restore(other.input).equals(prose) : "Non-Chinese targets must not inject Chinese glossary entries";

        String technical="`repository` https://example.com/license camelCase Google Play Services FLOSS";
        TextTools.Protected protectedOther=new TextTools.Protected(technical,false);
        assert !protectedOther.input.contains("repository") && !protectedOther.input.contains("camelCase");
        assert !protectedOther.input.contains("https://") && !protectedOther.input.contains("Google Play Services");
        assert protectedOther.restore(protectedOther.input).equals(technical);
        String chineseTechnical="`repository` https://example.com/license camelCase";
        TextTools.Protected protectedChinese=new TextTools.Protected(chineseTechnical,true);
        assert protectedChinese.restore(protectedChinese.input).equals(chineseTechnical);
        TextTools.Protected chineseSource=new TextTools.Protected("马尾辫",false);
        assert chineseSource.input.equals("马尾辫") && chineseSource.tokens.isEmpty();
        assert TextTools.keepTechnical("马尾辫")==null;
        for(String text:new String[]{"日本語", "한국어", "привет", "γειά", "مرحبا", "नमस्ते", "สวัสดี", "français"})assert TextTools.letters(text);
        assert !TextTools.letters("123 + 456");
        assert TextTools.scriptLanguage("日本語プロジェクト").equals("ja");
        assert TextTools.scriptLanguage("한국어").equals("ko");
        assert TextTools.scriptLanguage("马尾辫").equals("zh");
        assert TextTools.scriptLanguage("français").isEmpty();
        assert TextTools.scriptLanguage("Deutsch").isEmpty();
        System.out.println("TextToolsCheck: OK (target-specific glossary, technical identifiers, Unicode text and script hints)");
    }
}
