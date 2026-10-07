package cn.local.githubcn;

public final class SearchQueryCheck {
    public static void main(String[] args) {
        String doublePlus = "codexplusplus OR codex-plusplus OR codex-plus-plus in:name";
        assert SearchQuery.build("codex++").equals(doublePlus);
        assert SearchQuery.build("  Ｃｏｄｅｘ＋＋  ").equals(doublePlus);
        assert SearchQuery.build("codex plus plus").equals(doublePlus);
        assert SearchQuery.build("CodexPlusPlus").equals(doublePlus);
        assert SearchQuery.build("codex plus").equals("codexplus OR codex-plus in:name");
        assert SearchQuery.build("codex+").equals("codexplus OR codex-plus in:name");
        assert SearchQuery.build("codex++ in:name  fork:true").equals("codex++ in:name  fork:true");
        assert SearchQuery.build("user:example codex++").equals("user:example codex++");
        assert SearchQuery.build("codex++ OR cpp").equals("codex++ OR cpp");
        assert SearchQuery.build("\"codex++\"").equals("\"codex++\"");
        assert SearchQuery.build("https://github.com/example/project.git").equals("repo:example/project");
        assert SearchQuery.build("example/project").equals("repo:example/project");
        assert SearchQuery.rank("codex++", "codex-plus-plus") == 0;
        assert SearchQuery.rank("codex plus", "CodexPlusPlus") == 1;
        assert SearchQuery.rank("codex++", "codex-plusplus-addon") == 1;
        assert SearchQuery.rank("codex++ in:name", "codexplusplus") == 3;
        assert SearchQuery.build("microG").equals("microg");
        assert SearchQuery.build("").isEmpty();
        assert SearchQuery.canTranslate("马尾辫skill");
        assert SearchQuery.translationSource("马尾辫skill").equals("zh");
        assert SearchQuery.translationText("马尾辫skill").equals("马尾辫");
        assert SearchQuery.translatedKeywords("马尾辫skill", "ponytail").equals("ponytail skill");
        assert SearchQuery.translationSource("ponytail skill").equals("auto");
        assert SearchQuery.translationText("ponytail skill").equals("ponytail");
        assert SearchQuery.translatedKeywords("ponytail skill", "马尾辫").equals("马尾辫 skill");
        assert SearchQuery.translatedKeywords("马尾辫skill", "马尾辫").isEmpty();
        assert SearchQuery.translatedKeywords("马尾辫skill", null).isEmpty();
        assert !SearchQuery.canTranslate("codex++");
        assert !SearchQuery.canTranslate("codex plus plus");
        assert !SearchQuery.canTranslate("skill");
        assert !SearchQuery.canTranslate("马尾辫 in:readme");
        assert !SearchQuery.canTranslate("https://github.com/example/project.git");
        assert !SearchQuery.canTranslate("owner/repo");
        assert SearchQuery.build("马尾辫skill").equals("马尾辫skill"); // Translation disabled keeps this query exact.
        assert SearchQuery.translationSource("日本語のプロジェクトskill").equals("ja");
        assert SearchQuery.translationSource("한국어skill").equals("ko");
        assert SearchQuery.translationSource("documentation française plugin").equals("auto");
        assert SearchQuery.translationSource("Maschinelles Lernen plugin").equals("auto");
        assert SearchQuery.defaultTranslationTarget("fr").equals("en");
        assert SearchQuery.defaultTranslationTarget("ja").equals("en");
        assert SearchQuery.defaultTranslationTarget("en").equals("zh");
        assert SearchQuery.translationText("машинное обучение skill").equals("машинное обучение");
        assert SearchQuery.translatedKeywords("машинное обучение skill", "machine learning").equals("machine learning skill");
        assert SearchQuery.translationText("apprentissage automatique plugin").equals("apprentissage automatique");
        assert SearchQuery.translatedKeywords("apprentissage automatique plugin", "machine learning").equals("machine learning plugin");
        System.out.println("SearchQueryCheck: OK (aliases, syntax, URL, ranking, multilingual terms, scripts and exclusions)");
    }
}
