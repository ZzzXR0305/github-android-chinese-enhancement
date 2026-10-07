package cn.local.githubcn;

/** ML Kit's 59 translation languages; common choices first, remaining codes alphabetical. */
public final class LanguageOptions {
    private static final String[][] LANGUAGES = {
        {"zh", "简体中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"},
        {"es", "西班牙语"}, {"fr", "法语"}, {"de", "德语"}, {"ru", "俄语"},
        {"pt", "葡萄牙语"}, {"ar", "阿拉伯语"}, {"hi", "印地语"}, {"id", "印度尼西亚语"},
        {"vi", "越南语"}, {"th", "泰语"},
        {"af", "南非荷兰语"}, {"be", "白俄罗斯语"}, {"bg", "保加利亚语"}, {"bn", "孟加拉语"},
        {"ca", "加泰罗尼亚语"}, {"cs", "捷克语"}, {"cy", "威尔士语"}, {"da", "丹麦语"},
        {"el", "希腊语"}, {"eo", "世界语"}, {"et", "爱沙尼亚语"}, {"fa", "波斯语"},
        {"fi", "芬兰语"}, {"ga", "爱尔兰语"}, {"gl", "加利西亚语"}, {"gu", "古吉拉特语"},
        {"he", "希伯来语"}, {"hr", "克罗地亚语"}, {"ht", "海地克里奥尔语"}, {"hu", "匈牙利语"},
        {"is", "冰岛语"}, {"it", "意大利语"}, {"ka", "格鲁吉亚语"}, {"kn", "卡纳达语"},
        {"lt", "立陶宛语"}, {"lv", "拉脱维亚语"}, {"mk", "马其顿语"}, {"mr", "马拉地语"},
        {"ms", "马来语"}, {"mt", "马耳他语"}, {"nl", "荷兰语"}, {"no", "挪威语"},
        {"pl", "波兰语"}, {"ro", "罗马尼亚语"}, {"sk", "斯洛伐克语"}, {"sl", "斯洛文尼亚语"},
        {"sq", "阿尔巴尼亚语"}, {"sv", "瑞典语"}, {"sw", "斯瓦希里语"}, {"ta", "泰米尔语"},
        {"te", "泰卢固语"}, {"tl", "他加禄语"}, {"tr", "土耳其语"}, {"uk", "乌克兰语"},
        {"ur", "乌尔都语"}
    };
    public static final String[] CODES = new String[LANGUAGES.length];
    static { for (int i = 0; i < CODES.length; i++) CODES[i] = LANGUAGES[i][0]; }
    private LanguageOptions() {}

    public static String[] labels() {
        String[] labels = new String[LANGUAGES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = LANGUAGES[i][1];
        return labels;
    }
    public static boolean supports(String code) { return index(code) >= 0; }
    public static int index(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return -1;
    }
    public static String name(String code) { int index = index(code); return index >= 0 ? LANGUAGES[index][1] : code == null ? "" : code; }
}
