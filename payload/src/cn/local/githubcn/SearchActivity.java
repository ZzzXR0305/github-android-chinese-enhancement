package cn.local.githubcn;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class SearchActivity extends Activity {
    private EditText input;
    private Button submit;
    private CheckBox bilingual;
    private Spinner sourceLanguage, targetLanguage;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout results;
    private boolean loading;
    private int generation;
    private static final int INK = Color.rgb(31, 35, 40), BLUE = Color.rgb(9, 105, 218);

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        page.setBackgroundColor(Color.WHITE);
        Button back = new Button(this);
        back.setText("返回 GitHub");
        back.setOnClickListener(v -> finish());
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView title = text("仓库增强搜索", 23);
        title.setTypeface(null, Typeface.BOLD);
        page.addView(title);
        input = new EditText(this);
        input.setTextColor(INK);
        input.setHintTextColor(Color.DKGRAY);
        input.setSingleLine(true);
        input.setHint("项目名称，例如 codex++、马尾辫 skill");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setContentDescription("搜索仓库名称或 GitHub 链接");
        page.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        bilingual = new CheckBox(this);
        bilingual.setText("多语言互译搜索");
        bilingual.setTextColor(INK);
        bilingual.setChecked(getSharedPreferences("githubcn", MODE_PRIVATE).getBoolean("bilingual_search", true));
        page.addView(bilingual, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(text("关键词来源语言", 13));
        sourceLanguage = languageSpinner("search_source", "自动识别（短关键词可手动选择）");
        page.addView(sourceLanguage, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(text("补搜语言", 13));
        targetLanguage = languageSpinner("search_target", "自动（英语补搜中文，其余补搜英语）");
        page.addView(targetLanguage, new LinearLayout.LayoutParams(-1, dp(44)));
        sourceLanguage.setEnabled(bilingual.isChecked());
        targetLanguage.setEnabled(bilingual.isChecked());
        bilingual.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("githubcn", MODE_PRIVATE).edit().putBoolean("bilingual_search", checked).apply();
            sourceLanguage.setEnabled(checked && !loading);
            targetLanguage.setEnabled(checked && !loading);
        });
        submit = new Button(this);
        submit.setText("搜索仓库");
        submit.setOnClickListener(v -> search());
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                search(); return true;
            }
            return false;
        });
        page.addView(submit, new LinearLayout.LayoutParams(-1, dp(48)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        status = text("支持 +、++、plus；59 种语言按需下载本机模型，保留原关键词并补搜所选语言。高级语法和仓库链接保持原样。", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        String initial = getIntent().getStringExtra("query");
        if (initial != null && !initial.trim().isEmpty()) {
            input.setText(initial);
            input.setSelection(input.length());
            search();
        }
    }

    private void search() {
        String entered = input.getText().toString().trim();
        if (loading) return;
        if (entered.isEmpty()) { input.setError("请输入项目名称"); return; }
        String query = SearchQuery.build(entered);
        boolean translate = bilingual.isChecked() && SearchQuery.canTranslate(entered);
        String chosenSource = selectedLanguage(sourceLanguage), chosenTarget = selectedLanguage(targetLanguage);
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        loading = true;
        submit.setEnabled(false);
        bilingual.setEnabled(false);
        sourceLanguage.setEnabled(false);
        targetLanguage.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        results.removeAllViews();
        status.setText(translate ? "正在本机识别 / 互译关键词，再合并搜索结果…首次使用模型需 Wi-Fi。" : "正在搜索…");
        int ticket = ++generation;
        new Thread(() -> {
            try {
                String translated = "", translationProblem = "", languagePair = "";
                if (translate) {
                    try {
                        Bundle request = new Bundle();
                        String source = chosenSource.equals("auto") ? SearchQuery.translationSource(entered) : chosenSource;
                        String translationText = SearchQuery.translationText(entered);
                        if (source.equals("auto")) {
                            Bundle identification = new Bundle();
                            identification.putString("text", translationText);
                            Bundle identified = getContentResolver().call(Uri.parse("content://cn.local.repochinese.translation"), "identify", null, identification);
                            if (identified == null || !LanguageOptions.supports(identified.getString("language"))) throw new IOException("无法识别来源语言");
                            source = identified.getString("language");
                        }
                        String target = chosenTarget.equals("auto") ? SearchQuery.defaultTranslationTarget(source) : chosenTarget;
                        request.putString("source", source);
                        request.putString("target", target);
                        request.putString("text", translationText);
                        Bundle answer = getContentResolver().call(Uri.parse("content://cn.local.repochinese.translation"), "translate", null, request);
                        if (answer == null || answer.getString("text") == null) throw new IOException("翻译模型暂不可用");
                        translated = SearchQuery.translatedKeywords(entered, answer.getString("text"));
                        languagePair = LanguageOptions.name(source) + " → " + LanguageOptions.name(target);
                    } catch (Exception unavailable) {
                        translationProblem = " 互译暂不可用，已搜索原关键词；可手动选择来源语言，并在仓库中文助手中检查离线模型。";
                    }
                }
                List<String> queries = new ArrayList<>();
                queries.add(query + (translate ? " in:name,description,readme" : ""));
                if (!translated.isEmpty()) {
                    String alternate = SearchQuery.build(translated) + " in:name,description,readme";
                    if (!alternate.equalsIgnoreCase(queries.get(0))) queries.add(alternate);
                }
                LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
                JSONObject reply = null;
                Exception failed = null;
                int succeeded = 0;
                boolean incomplete = false;
                for (String searchQuery : queries) {
                    try {
                        reply = fetch(searchQuery);
                        succeeded++;
                        incomplete |= reply.optBoolean("incomplete_results");
                        JSONArray items = reply.getJSONArray("items");
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject repo = items.getJSONObject(i);
                            unique.put(repo.getString("full_name").toLowerCase(Locale.ROOT), repo);
                        }
                    } catch (Exception error) { failed = error; }
                }
                if (succeeded == 0) throw failed;
                List<JSONObject> repos = new ArrayList<>(unique.values());
                final String alternateKeywords = translated;
                Collections.sort(repos, (a, b) -> Integer.compare(Math.min(SearchQuery.rank(entered, a.optString("name")), SearchQuery.rank(alternateKeywords, a.optString("name"))), Math.min(SearchQuery.rank(entered, b.optString("name")), SearchQuery.rank(alternateKeywords, b.optString("name")))));
                long total = reply.optLong("total_count", repos.size());
                String notice = translationProblem + (succeeded < queries.size() ? " 部分搜索请求失败，当前显示已获得的结果。" : "")
                        + (incomplete ? " 搜索未完成，结果可能不全。" : "");
                String summary = repos.isEmpty() ? "未找到仓库。可试试其他名称或粘贴仓库链接。" :
                        queries.size() > 1 ? "合并显示 " + repos.size() + " 个仓库；" + languagePair + "：" + translated + "。" :
                        "找到 " + total + " 个仓库，显示 " + repos.size() + " 个；名称匹配优先。";
                android.util.Log.i("GitHubCN", "Repository search succeeded: " + repos.size() + " results");
                runOnUiThread(() -> {
                    if (!current(ticket)) return;
                    stopLoading();
                    status.setText(summary + notice);
                    for (JSONObject repo : repos) showRepo(repo);
                });
            } catch (Exception error) {
                android.util.Log.w("GitHubCN", "Repository search failed: " + error.getClass().getSimpleName());
                String message = error instanceof SocketTimeoutException ? "搜索超时，请检查网络后重试。" : error instanceof UnknownHostException ? "无法连接 GitHub，请检查手机网络。" : error.getMessage();
                runOnUiThread(() -> {
                    if (!current(ticket)) return;
                    stopLoading();
                    status.setText(message == null ? "搜索失败，请稍后重试。" : message);
                });
            }
        }, "githubcn-search").start();
    }

    private JSONObject fetch(String query) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.github.com/search/repositories?per_page=100&q=" + URLEncoder.encode(query, "UTF-8")).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "GitHubChineseRepositorySearch/2.0");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            int code = connection.getResponseCode();
            String body = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code == 403 || code == 429) {
                String lower = body.toLowerCase(Locale.ROOT);
                if (code == 429 || "0".equals(connection.getHeaderField("X-RateLimit-Remaining")) || lower.contains("rate limit")) {
                    String retry = "请稍后再试。";
                    String reset = connection.getHeaderField("X-RateLimit-Reset");
                    try { if (reset != null) retry = "约 " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(Long.parseLong(reset) * 1000)) + " 可重试。"; } catch (NumberFormatException ignored) {}
                    throw new IOException("GitHub 搜索请求受限（HTTP " + code + "）。" + retry);
                }
                throw new IOException("GitHub 拒绝搜索请求（HTTP 403），请稍后重试。");
            }
            if (code == 422) throw new IOException("GitHub 无法处理这个搜索语法，请检查关键词和限定符（HTTP 422）。");
            if (code < 200 || code >= 300) throw new IOException("GitHub 搜索暂时不可用（HTTP " + code + "），请稍后重试。");
            return new JSONObject(body);
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 4 * 1024 * 1024) throw new IOException("搜索结果过大，请缩小搜索范围。");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }

    private void showRepo(JSONObject repo) {
        String fullName = repo.optString("full_name"), description = repo.optString("description");
        if (description.equals("null")) description = "";
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(9), dp(8), dp(9));
        TextView name = text(fullName, 18);
        name.setTextColor(BLUE);
        name.setTypeface(null, Typeface.BOLD);
        card.addView(name);
        if (!description.isEmpty()) {
            TextView intro = text(description, 14);
            card.addView(intro);
            NativeText.attach(intro);
        }
        card.addView(text("★ " + repo.optLong("stargazers_count") + (repo.optBoolean("fork") ? "  · 分支仓库" : ""), 13));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(fullName + "，" + description + "，" + repo.optLong("stargazers_count") + " 星标，打开仓库");
        card.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + fullName)).setPackage(getPackageName()));
            } catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "请先在 GitHub 中文增强中登录，再打开仓库。", Toast.LENGTH_LONG).show();
                startActivity(new Intent().setClassName(getPackageName(), "com.github.android.main.MainActivity"));
            }
        });
        results.addView(card, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(216, 222, 228));
        results.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private Spinner languageSpinner(String preference, String automatic) {
        Spinner spinner = new Spinner(this);
        String[] labels = LanguageOptions.labels();
        List<String> choices = new ArrayList<>();
        choices.add(automatic);
        Collections.addAll(choices, labels);
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        String saved = getSharedPreferences("githubcn", MODE_PRIVATE).getString(preference, "auto");
        spinner.setSelection(LanguageOptions.index(saved) + 1);
        spinner.setContentDescription(preference.equals("search_source") ? "关键词来源语言" : "补搜语言");
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences("githubcn", MODE_PRIVATE).edit().putString(preference, selectedLanguage(spinner)).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        return spinner;
    }

    private static String selectedLanguage(Spinner spinner) {
        int index = spinner.getSelectedItemPosition() - 1;
        return index >= 0 && index < LanguageOptions.CODES.length ? LanguageOptions.CODES[index] : "auto";
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean current(int ticket) { return ticket == generation && !isFinishing() && !isDestroyed(); }
    private void stopLoading() { loading = false; submit.setEnabled(true); bilingual.setEnabled(true); sourceLanguage.setEnabled(bilingual.isChecked()); targetLanguage.setEnabled(bilingual.isChecked()); progress.setVisibility(View.GONE); }
    @Override protected void onDestroy() { generation++; super.onDestroy(); }
}
