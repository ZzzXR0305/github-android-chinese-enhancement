package cn.local.githubcn;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/** Small integration points called from the original application and search fragment. */
public final class Hooks {
    private static final int MENU_ID = 0x6f010101;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Screen> SCREENS = new WeakHashMap<>();
    private static SharedPreferences settings;
    private static boolean initialized;
    private Hooks() {}

    public static void initialize(Application app) {
        if (initialized) return;
        initialized = true;
        settings = app.getSharedPreferences("github_cn", Context.MODE_PRIVATE);
        Immersive.setEnabled(settings.getBoolean("enabled", true));
        Immersive.setTarget(settings.getString("target", "zh"));
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) { attach(a); }
            public void onActivityPaused(Activity a) { pause(a); }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {
                pause(a);
                View button = a.getWindow().getDecorView().findViewWithTag(FloatingButton.TAG);
                if (button != null && button.getTag(FloatingButton.CONTROLLER_TAG) instanceof FloatingButton)
                    ((FloatingButton) button.getTag(FloatingButton.CONTROLLER_TAG)).dispose();
            }
        });
    }

    public static void attach(Activity activity) {
        if (SCREENS.containsKey(activity)) return;
        String name = activity.getClass().getName();
        if (!name.equals("com.github.android.main.MainActivity") &&
            !name.equals("com.github.android.main.GenericRouteHostActivity") &&
            !name.equals("com.github.android.activities.WebViewActivity") &&
            !name.equals(SearchActivity.class.getName())) return;
        Screen screen = new Screen(activity);
        SCREENS.put(activity, screen);
        if (screen.button != null) {
            screen.button.updatePage(pageKey(activity));
            screen.button.setForeground(true);
        }
        MAIN.postDelayed(screen, 450);
    }

    private static void pause(Activity activity) {
        Screen screen = SCREENS.remove(activity);
        if (screen == null) return;
        MAIN.removeCallbacks(screen);
        if (screen.button != null) screen.button.setForeground(false);
    }

    private static final class Screen implements Runnable {
        final WeakReference<Activity> activity;
        final FloatingButton button;
        Screen(Activity value) { activity = new WeakReference<>(value); button = FloatingButton.attach(value, () -> showMenu(value)); }
        public void run() {
            Activity a = activity.get();
            if (a == null || SCREENS.get(a) != this || a.isDestroyed()) return;
            visit(a.getWindow().getDecorView(), a);
            if (button != null) button.updatePage(pageKey(a));
            MAIN.postDelayed(this, 1400);
        }
    }

    private static void visit(View view, Activity activity) {
        if (view instanceof WebView) Immersive.attach((WebView) view);
        if (view instanceof TextView && view.getId() != View.NO_ID) {
            try {
                if (view.getResources().getResourceEntryName(view.getId()).equals("repository_description"))
                    NativeText.attach((TextView) view);
            } catch (Exception ignored) {}
        }
        if (view.getClass().getName().contains("Toolbar")) {
            try {
                Object value = view.getClass().getMethod("getMenu").invoke(view);
                if (value instanceof Menu && view.isShown()) {
                    Menu m = (Menu) value;
                    MenuItem item = m.findItem(MENU_ID);
                    if (item == null) item = m.add(0, MENU_ID, 999, "翻译与增强搜索");
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                    item.setOnMenuItemClickListener(i -> { showMenu(activity); return true; });
                }
            } catch (Exception ignored) {}
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i), activity);
        }
    }

    /** Fragment root identities and titles change on navigation without tracking list/body mutations. */
    public static String pageKey(Activity activity) {
        StringBuilder key = new StringBuilder(activity.getClass().getName()).append('|').append(activity.getTitle());
        int fragmentTag = activity.getResources().getIdentifier("fragment_container_view_tag", "id", activity.getPackageName());
        pageKey(activity.getWindow().getDecorView(), key, fragmentTag, false);
        return key.toString();
    }

    private static void pageKey(View view, StringBuilder key, int fragmentTag, boolean titleContainer) {
        if (!view.isShown() || FloatingButton.TAG.equals(view.getTag())) return;
        if (fragmentTag != 0) {
            Object fragment = view.getTag(fragmentTag);
            if (fragment != null) key.append("|fragment:").append(fragment.getClass().getName()).append('@').append(System.identityHashCode(fragment));
        }
        String entry = "";
        try { if (view.getId() != View.NO_ID) entry = view.getResources().getResourceEntryName(view.getId()); }
        catch (Exception ignored) {}
        titleContainer |= entry.contains("toolbar") ||
            (view.getClass().getName().equals("androidx.compose.ui.platform.ComposeView") && entry.endsWith("_title"));
        if (view instanceof TextView && titleContainer) key.append('|').append(((TextView) view).getText());
        if (view.getAccessibilityPaneTitle() != null) key.append("|pane:").append(view.getAccessibilityPaneTitle());
        try {
            Object owner = view.getClass().getMethod("getSemanticsOwner").invoke(view);
            Object root = owner.getClass().getMethod("a").invoke(owner);
            ClassLoader loader = view.getClass().getClassLoader();
            Class<?> properties = Class.forName("gk30", false, loader);
            composeTitles(root, key, properties.getField("C").get(null), properties.getField("h").get(null), properties.getField("d").get(null), titleContainer, new int[]{128});
        } catch (Exception ignored) { /* Ordinary views have no Compose semantics owner. */ }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) pageKey(group.getChildAt(i), key, fragmentTag, titleContainer);
        }
    }

    private static void composeTitles(Object node, StringBuilder key, Object text, Object heading, Object pane, boolean titleContainer, int[] budget) throws Exception {
        // ponytail: this APK's Compose property names are version-specific; update with the APK patch.
        if (--budget[0] < 0) return;
        Object config = node.getClass().getMethod("n").invoke(node);
        Object values = config.getClass().getField("p").get(config);
        java.lang.reflect.Method read = values.getClass().getMethod("g", Object.class);
        Object paneTitle = read.invoke(values, pane);
        if (paneTitle != null) key.append("|compose-pane:").append(paneTitle);
        if (titleContainer || read.invoke(values, heading) != null) {
            Object content = read.invoke(values, text);
            if (content != null) key.append("|compose-title:").append(content);
        }
        List<?> children = (List<?>) node.getClass().getMethod("i", boolean.class, boolean.class).invoke(node, true, true);
        for (Object child : children) composeTitles(child, key, text, heading, pane, titleContainer, budget);
    }

    private static void showMenu(Activity a) {
        String[] entries = {"仓库增强搜索", Immersive.enabled() ? "关闭翻译，恢复原文" : "开启全文翻译", "目标语言：" + language(), "翻译说明", "恢复译 / 搜按钮", "更新与开源项目", "原版搜索接入增强搜索：" + (redirectSearch() ? "开" : "关")};
        new AlertDialog.Builder(a).setTitle("GitHub 中文增强").setItems(entries, (d, which) -> {
            if (which == 0) a.startActivity(new Intent(a, SearchActivity.class));
            if (which == 1) {
                Immersive.setEnabled(!Immersive.enabled());
                save();
                Toast.makeText(a, Immersive.enabled() ? "已开启，将自动替换英文正文" : "已关闭，将恢复原文", Toast.LENGTH_SHORT).show();
            }
            if (which == 2) {
                String[] labels = LanguageOptions.labels();
                labels[LanguageOptions.index("en")] = "英语（显示原文）";
                new AlertDialog.Builder(a).setTitle("选择目标语言").setSingleChoiceItems(labels,
                    LanguageOptions.index(Immersive.target()), (dialog, choice) -> {
                        Immersive.setTarget(LanguageOptions.CODES[choice]); save(); dialog.dismiss();
                    }).show();
            }
            if (which == 3) new AlertDialog.Builder(a).setTitle("全文翻译").setMessage(
                "自动替换 README 和讨论正文，保留代码、链接与操作控件；关闭后恢复原文。\n\n仓库中文助手提供本机翻译模型，首次使用其他语言需要 Wi-Fi 下载模型。")
                .setPositiveButton("知道了", null).show();
            if (which == 4) {
                FloatingButton button = FloatingButton.attach(a, () -> showMenu(a));
                if (button != null) { button.setForeground(true); button.restore(); }
            }
            if (which == 5) a.startActivity(new Intent(a, UpdateActivity.class));
            if (which == 6) {
                settings.edit().putBoolean("search_redirect", !redirectSearch()).apply();
                Toast.makeText(a, redirectSearch() ? "已开启原版搜索接入增强搜索" : "已关闭，原版搜索保持正常", Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    /** Opt-in routing; the original search stays intact unless the user enables this setting. */
    public static void searchEntry(View root) {
        if (!redirectSearch()) return;
        Context context = root.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        if (!(context instanceof Activity)) return;
        final Activity activity = (Activity) context;
        root.post(() -> {
            if (redirectSearch() && !activity.isFinishing()) activity.startActivity(new Intent(activity, SearchActivity.class));
        });
    }

    private static boolean redirectSearch() { return settings != null && settings.getBoolean("search_redirect", false); }

    private static String language() {
        return LanguageOptions.name(Immersive.target());
    }
    private static void save() {
        if (settings != null) settings.edit().putBoolean("enabled", Immersive.enabled()).putString("target", Immersive.target()).apply();
    }
}
