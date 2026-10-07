package cn.local.repochinese;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView state,translated,permission;
    private EditText source;
    private Button translate,fetch;
    private long generation;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);getWindow().setStatusBarColor(Ui.GREEN);getWindow().setNavigationBarColor(Ui.PAPER);
        ScrollView scroll=new ScrollView(this);LinearLayout page=Ui.column(this);scroll.addView(page);setContentView(scroll);
        page.addView(Ui.text(this,"GITHUB · 中文阅读",12));page.addView(Ui.title(this,"仓库中文助手"));
        page.addView(Ui.text(this,"README、项目介绍与讨论，随手译成中文。",16));
        page.addView(Ui.text(this,"在 GitHub 内点击「译为中文」读取当前可见页；长文可分享仓库链接，读取公开 README 全文。",14));
        permission=Ui.text(this,"",14);page.addView(permission);
        Button enable=Ui.button(this,"开启 GitHub 页内翻译");page.addView(enable);
        enable.setOnClickListener(v->{Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);startActivity(i);});
        state=Ui.text(this,"首次使用：通过 Wi-Fi 下载约 30 MB 的英译中模型。",14);page.addView(state);
        Button prepare=Ui.button(this,"准备 / 检查离线模型");page.addView(prepare);prepare.setOnClickListener(v->prepare());
        Button github=Ui.button(this,"打开 GitHub");page.addView(github);github.setOnClickListener(v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.github.android");if(i!=null)startActivity(i);else state.setText("未找到 GitHub 手机版。");});
        page.addView(Ui.text(this,"分享、粘贴或输入英文 / GitHub 链接",14));
        source=new EditText(this);source.setTextSize(15);source.setTextColor(Ui.INK);source.setHint("英文内容或 https://github.com/所有者/仓库");source.setMinLines(3);source.setMaxLines(9);source.setGravity(Gravity.TOP);page.addView(source);
        fetch=Ui.button(this,"读取公开链接正文");page.addView(fetch);fetch.setOnClickListener(v->fetch());
        translate=Ui.button(this,"翻译文本");page.addView(translate);translate.setOnClickListener(v->translate());
        translated=Ui.text(this,"译文会显示在这里，可长按复制。",17);Ui.selectable(translated);page.addView(translated);
        page.addView(Ui.text(this,"按需读取 · 本地翻译\n正文在本机处理；模型准备阶段需要联网。公开链接仅向 GitHub 请求正文，不使用你的账号或令牌。私有内容用页内翻译。\n\n机器翻译用于辅助阅读；代码块和链接尽量保留原文。图片里的文字暂不识别。\n\n关闭方式：系统设置 → 无障碍 → 仓库中文助手 → 关闭。卸载不会改动 GitHub。",12));
        receive(getIntent());
    }
    @Override protected void onResume(){super.onResume();String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);permission.setText(enabled!=null && enabled.contains(getPackageName()+"/")?"✓ 页内翻译已启用，仅在 GitHub 显示按钮":"页内翻译待启用；分享和粘贴翻译可独立使用");}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);}
    private void receive(Intent i) {
        CharSequence s=i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if(s==null)s=i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if(s!=null){generation++;source.setText(s);fetch.setEnabled(true);translate.setEnabled(true);translated.setText("收到内容，可读取公开链接或翻译文本。");}
    }
    private void prepare(){state.setText("正在检查 / 下载模型，请保持 Wi-Fi 连接…");Engine.get().prepare(new Engine.Result(){public void done(String s){if(!isDestroyed())state.setText(s);}public void error(String s){if(!isDestroyed())state.setText(s);}});}
    private void translate() {
        String s=source.getText().toString().trim();if(s.isEmpty()){translated.setText("请先输入或分享英文内容。");return;}
        if(s.startsWith("https://github.com/") && !s.contains("\n")){translated.setText("这是链接，请先点击「读取公开链接正文」。");return;}
        long id=++generation;translate.setEnabled(false);translated.setText("正在本地翻译…");
        Engine.get().translate(s,new Engine.Result(){public void done(String t){if(isDestroyed()||generation!=id)return;translated.setText(t);translate.setEnabled(true);}public void error(String t){if(isDestroyed()||generation!=id)return;translated.setText(t);translate.setEnabled(true);}});
    }
    private void fetch() {
        String s=source.getText().toString().trim();fetch.setEnabled(false);translate.setEnabled(false);translated.setText("正在向 GitHub 读取公开正文…");long id=++generation;
        java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();executor.execute(()->{
            try{String content=PublicContent.fetch(s);runOnUiThread(()->{if(isDestroyed()||generation!=id)return;source.setText(content);fetch.setEnabled(true);translate.setEnabled(true);translated.setText("已读取公开正文，点击「翻译文本」。");});}
            catch(Exception e){runOnUiThread(()->{if(isDestroyed()||generation!=id)return;fetch.setEnabled(true);translate.setEnabled(true);translated.setText(e.getMessage());});}
            finally{executor.shutdown();}
        });
    }
}
