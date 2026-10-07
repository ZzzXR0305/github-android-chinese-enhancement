package cn.local.repochinese;

import java.util.*;
import java.util.regex.*;

final class TextTools {
    static boolean english(String s) {
        if(s==null || s.trim().isEmpty())return false;
        return Pattern.compile("[A-Za-z]{2,}").matcher(s).find();
    }
    static boolean letters(String s) {return s!=null&&Pattern.compile("[\\p{L}]").matcher(s).find();}
    static String scriptLanguage(String s) {
        if(Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}]").matcher(s).find())return "ja";
        if(Pattern.compile("[\\p{IsHangul}]").matcher(s).find())return "ko";
        if(Pattern.compile("[\\p{IsHan}]").matcher(s).find())return "zh";
        return "";
    }
    static List<String> chunks(String s) {
        ArrayList<String> out=new ArrayList<>();
        while(!s.isEmpty()) {
            if(s.length()<=1800){out.add(s);break;}
            int cut=s.lastIndexOf('\n',1800);
            if(cut<700)cut=s.lastIndexOf(' ',1800);
            if(cut<700)cut=1800;
            if(Character.isHighSurrogate(s.charAt(cut-1)))cut--;
            out.add(s.substring(0,cut));s=s.substring(cut);
        }
        return out;
    }
    static String keepTechnical(String s) {
        // Whole code blocks, commands, and bare links should remain exact.
        String t=s.trim();
        if(t.startsWith("```") || t.matches("https?://\\S+") || t.matches("(?s)(\\$ |npm |pip |git |sudo |docker |pnpm |yarn |curl |wget ).*"))return s;
        return null;
    }
    static final class Protected {
        private static String glossary(String text) {
            switch(text.toLowerCase(Locale.ROOT)) {
                case "google play services":case "play services":return "Google Play 服务";
                case "floss":return "FLOSS";
                case "free/libre open source software":return "自由开源软件";
                case "license":return "许可证";
                case "pull request":case "pull requests":return "拉取请求";
                case "repository":case "repositories":return "仓库";
                default:return text;
            }
        }
        final LinkedHashMap<String,String> tokens=new LinkedHashMap<>();
        final String input;
        Protected(String s) {this(s,true);}
        Protected(String s,boolean chineseGlossary) {
            String common=chineseGlossary?"|\\b(?i:Free/Libre Open Source Software|pull requests?|repository|repositories|license)\\b":"";
            Matcher m=Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`|https?://[^\\s)]+|\\b(?i:Google Play Services|Play Services|FLOSS)\\b"+common+"|\\b[A-Za-z0-9_]*[a-z][A-Z][A-Za-z0-9_]*\\b",Pattern.MULTILINE).matcher(s);
            StringBuffer b=new StringBuffer();int n=0;
            while(m.find()){String token="ZXQ"+(n++)+"QXZ";tokens.put(token,chineseGlossary?glossary(m.group()):m.group());m.appendReplacement(b,token);}
            m.appendTail(b);input=b.toString();
        }
        String restore(String s) {
            for(Map.Entry<String,String> e:tokens.entrySet()) {
                String pattern=e.getKey().replace("ZXQ","Z\\s*X\\s*Q\\s*").replace("QXZ","\\s*Q\\s*X\\s*Z");
                s=s.replaceAll("(?i)"+pattern,Matcher.quoteReplacement(e.getValue()));
            }
            return s;
        }
    }
}
