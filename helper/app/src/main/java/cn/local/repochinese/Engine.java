package cn.local.repochinese;

import android.content.Context;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import java.util.*;

final class Engine {
    interface Result {void done(String value);void error(String message);}
    private static final Map<String,Engine> instances=new HashMap<>();
    private final Translator translator;
    private final String source,target;
    private boolean ready,downloading;
    private final List<Result> waiting=new ArrayList<>();
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<String,String>(100,0.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>160;}
    };
    private Engine(String source,String target) {
        this.source=source;this.target=target;
        translator=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(target).build());
    }
    static Engine get(){return get("zh");}
    static Engine get(String target){return get("en",target);}
    static synchronized Engine get(String source,String target){String pair=source+":"+target;Engine instance=instances.get(pair);if(instance==null){instance=new Engine(source,target);instances.put(pair,instance);}return instance;}
    boolean ready(){return ready;}
    void prepare(Result result) {
        if(ready){result.done("翻译模型已就绪，可离线翻译");return;}
        waiting.add(result);if(downloading)return;downloading=true;
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener(v->{ready=true;downloading=false;for(Result r:new ArrayList<>(waiting))r.done("翻译模型已就绪，可离线翻译");waiting.clear();})
            .addOnFailureListener(e->{downloading=false;for(Result r:new ArrayList<>(waiting))r.error("模型准备失败，请连接可访问 Google 模型服务的 Wi-Fi 后重试。\n"+e.getMessage());waiting.clear();});
    }
    void translate(String input,Result result) {
        if(!ready){prepare(new Result(){public void done(String s){translate(input,result);}public void error(String s){result.error(s);}});return;}
        TextTools.Protected protectedText=new TextTools.Protected(input,source.equals("en")&&target.equals("zh"));
        List<String> parts=new ArrayList<>();
        for(String paragraph:protectedText.input.split("\\n\\s*\\n"))parts.addAll(TextTools.chunks(paragraph));
        StringBuilder output=new StringBuilder();
        translatePart(parts,0,output,new Result(){public void done(String s){result.done(protectedText.restore(s));}public void error(String s){result.error(s);}});
    }
    private void translatePart(List<String> parts,int i,StringBuilder output,Result result) {
        if(i>=parts.size()){result.done(output.toString());return;}
        if(i>0)output.append("\n\n");
        String s=parts.get(i);String kept=TextTools.keepTechnical(s);
        boolean translatable=source.equals("en")?TextTools.english(s):TextTools.letters(s);
        if(kept!=null || !translatable || s.trim().matches("ZXQ[0-9]+QXZ")){output.append(s);translatePart(parts,i+1,output,result);return;}
        String cached=cache.get(s);
        if(cached!=null){output.append(cached);translatePart(parts,i+1,output,result);return;}
        translator.translate(s).addOnSuccessListener(t->{cache.put(s,t);output.append(t);translatePart(parts,i+1,output,result);})
            .addOnFailureListener(e->result.error("翻译失败："+e.getMessage()));
    }
}
