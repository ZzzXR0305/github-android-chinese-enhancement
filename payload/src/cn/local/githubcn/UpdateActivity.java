package cn.local.githubcn;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.*;

/** Releases and official-app updates are separate, so existing clone data stays intact. */
public final class UpdateActivity extends Activity {
    private TextView status;
    private Button check;
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)-cn(\\d+)$");
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(18));
        page.setBackgroundColor(Color.WHITE);
        TextView heading = text("更新与开源项目", 23);
        page.addView(heading);
        page.addView(text("当前增强版：" + BuildInfo.OFFICIAL_VERSION + " / " + BuildInfo.PATCH_VERSION, 16));
        String official = "官方原版尚未安装。";
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.github.android", 0);
            official = "手机官方原版：" + info.versionName;
            if (info.getLongVersionCode() > BuildInfo.OFFICIAL_CODE)
                official += "\n原版已更新，可按开源项目的更新说明重新适配。";
        } catch (Exception ignored) {}
        page.addView(text(official, 15));
        page.addView(text("增强版通过自己的发行页更新。官方原版继续从商店更新；连接电脑，使用项目提供的重补丁工具生成增强版，可保留增强版的登录和设置。新版需通过适配检查。", 15));
        status = text("点击检查增强版更新。", 15);
        page.addView(status);
        check = button("检查增强版更新", this::checkRelease);
        page.addView(check);
        page.addView(button("查看增强版发行页", () -> open(repositoryUrl("/releases/latest"))));
        page.addView(button("更新官方 GitHub", () -> open("https://play.google.com/store/apps/details?id=com.github.android")));
        page.addView(button("开源代码与更新说明", () -> open(repositoryUrl("#updates"))));
        page.addView(button("返回", this::finish));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }
    private void checkRelease() {
        if (!BuildInfo.REPOSITORY.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            status.setText("尚未配置开源项目地址。"); return;
        }
        check.setEnabled(false);
        status.setText("正在检查增强版发行页…");
        new Thread(() -> {
            String message;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + BuildInfo.REPOSITORY + "/releases/latest").openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "GitHubChineseEnhancementUpdate/" + BuildInfo.PATCH_VERSION);
                int code = connection.getResponseCode();
                if (code == 404) message = "开源项目尚未发布增强版更新。当前基于官方 " + BuildInfo.OFFICIAL_VERSION + "。";
                else if (code == 403 || code == 429) message = "GitHub 暂时限制了检查请求，请稍后再试。";
                else if (code != 200) message = "检查暂时不可用（HTTP " + code + "）。";
                else {
                    StringBuilder body = new StringBuilder();
                    try (InputStreamReader input = new InputStreamReader(connection.getInputStream(), "UTF-8")) {
                        char[] block = new char[4096]; int count;
                        while ((count = input.read(block)) != -1) {
                            if (body.length() + count > 512000) throw new IOException("Response too large");
                            body.append(block, 0, count);
                        }
                    }
                    JSONObject release = new JSONObject(body.toString());
                    String tag = release.optString("tag_name");
                    if (tag.length() > 120) throw new IOException("Unexpected release tag");
                    message = newer(tag, BuildInfo.RELEASE_TAG) ? "发现增强版 " + tag + "，请查看发行说明与支持的官方版本。"
                        : tag.equals(BuildInfo.RELEASE_TAG) ? "当前已是最新增强版。" : "发行页版本：" + tag + "。当前版本不低于该正式发行版。";
                }
            } catch (Exception error) { message = "无法连接更新服务，请检查网络后重试。"; }
            finally { if (connection != null) connection.disconnect(); }
            final String answer = message;
            runOnUiThread(() -> { if (!isDestroyed()) { status.setText(answer); check.setEnabled(true); } });
        }, "githubcn-update").start();
    }
    static boolean newer(String candidate, String current) {
        Matcher a = VERSION.matcher(candidate), b = VERSION.matcher(current);
        if (!a.matches() || !b.matches()) return false;
        for (int i = 1; i <= 4; i++) {
            try {
                int left = Integer.parseInt(a.group(i)), right = Integer.parseInt(b.group(i));
                if (left != right) return left > right;
            } catch (NumberFormatException ignored) { return false; }
        }
        return false;
    }
    private String repositoryUrl(String suffix) {
        return "https://github.com/" + BuildInfo.REPOSITORY + suffix;
    }
    private void open(String link) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (Exception ignored) { Toast.makeText(this, "手机没有可打开这个链接的浏览器。", Toast.LENGTH_LONG).show(); }
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); return button;
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(Color.rgb(31,35,40));
        view.setTextSize(size); view.setPadding(0,dp(7),0,dp(7)); return view;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
