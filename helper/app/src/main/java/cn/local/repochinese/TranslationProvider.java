package cn.local.repochinese;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

public final class TranslationProvider extends ContentProvider {
    private LanguageIdentifier identifier;
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        Bundle answer=new Bundle();
        String caller=getCallingPackage();
        if(!"com.github.android.chinese".equals(caller)){answer.putString("error","仅允许本机签名的 GitHub 中文增强版调用");return answer;}
        if("languages".equals(method)){
            ArrayList<String> languages=new ArrayList<>(TranslateLanguage.getAllLanguages());Collections.sort(languages);
            answer.putStringArrayList("languages",languages);return answer;
        }
        if(!("translate".equals(method)||"identify".equals(method)) || extras==null){answer.putString("error","不支持的翻译请求");return answer;}
        String text=extras.getString("text",""),source=extras.getString("source","en"),target=extras.getString("target","zh");
        if(text==null || text.length()>24000){answer.putString("error","文本长度不受支持");return answer;}
        if("identify".equals(method))return identify(text);
        source=language(source);target=language(target);
        if(source==null || target==null){answer.putString("error","翻译语言不受支持");return answer;}
        if(source.equals(target) || text.isEmpty()){answer.putString("text",text);return answer;}
        if(Looper.myLooper()==Looper.getMainLooper()){answer.putString("error","请在后台调用翻译");return answer;}
        CountDownLatch latch=new CountDownLatch(1);
        final String from=source,to=target;
        new Handler(Looper.getMainLooper()).post(()->Engine.get(from,to).translate(text,new Engine.Result(){
            public void done(String translated){answer.putString("text",translated);latch.countDown();}
            public void error(String message){answer.putString("error",message);latch.countDown();}
        }));
        try{if(!latch.await(90,TimeUnit.SECONDS)){Bundle timeout=new Bundle();timeout.putString("error","模型准备或翻译超时，请连接 Wi-Fi 后重试");return timeout;}}
        catch(InterruptedException e){Thread.currentThread().interrupt();answer.putString("error","翻译已取消");}
        return answer;
    }
    private Bundle identify(String text){
        Bundle answer=new Bundle();String script=TextTools.scriptLanguage(text);
        if(!script.isEmpty()){answer.putString("language",script);return answer;}
        if(Looper.myLooper()==Looper.getMainLooper()){answer.putString("error","请在后台识别语言");return answer;}
        CountDownLatch latch=new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(()->{
            try{
                if(identifier==null)identifier=LanguageIdentification.getClient();
                identifier.identifyLanguage(text).addOnSuccessListener(code->{
                    String recognized=language(code);
                    if(recognized==null)answer.putString("error","无法确定支持的来源语言，请手动选择");
                    else answer.putString("language",recognized);
                    latch.countDown();
                }).addOnFailureListener(error->{answer.putString("error","本机语言识别暂不可用，请手动选择来源语言");latch.countDown();});
            }catch(Exception error){answer.putString("error","本机语言识别暂不可用，请手动选择来源语言");latch.countDown();}
        });
        try{if(!latch.await(15,TimeUnit.SECONDS)){Bundle timeout=new Bundle();timeout.putString("error","语言识别超时，请手动选择来源语言");return timeout;}}
        catch(InterruptedException error){Thread.currentThread().interrupt();answer.putString("error","语言识别已取消");}
        return answer;
    }
    private static String language(String tag){
        if(tag==null)return null;
        String code=TranslateLanguage.fromLanguageTag(tag);
        return code!=null&&TranslateLanguage.getAllLanguages().contains(code)?code:null;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
