package cn.local.githubcn;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.LruCache;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** An isolated library inside this APK, never a separately installed helper. */
public final class LocalTranslation {
    private static final int CACHE_CHARACTERS = 512 * 1024;
    private static final LruCache<String, String> TRANSLATIONS = new LruCache<String, String>(CACHE_CHARACTERS) {
        @Override protected int sizeOf(String key, String value) { return key.length() + value.length(); }
    };
    private static Method entry;
    private static Context engineContext;
    private LocalTranslation() {}

    public static Bundle call(Context context, String method, Bundle extras) {
        try {
            String key = translationKey(method, extras);
            if (key != null && context != null) {
                String cached = TRANSLATIONS.get(key);
                if (cached != null) {
                    Bundle answer = new Bundle(); answer.putString("text", cached); return answer;
                }
            }
            initialize(context.getApplicationContext());
            Bundle answer = (Bundle) entry.invoke(null, engineContext, method, extras);
            if (key != null && answer != null && answer.getString("error") == null) {
                String translated = answer.getString("text");
                if (translated != null && key.length() + translated.length() <= CACHE_CHARACTERS)
                    TRANSLATIONS.put(key, translated);
            }
            return answer;
        } catch (Exception | LinkageError failure) {
            Log.e("GitHubCN", "Embedded translation initialization/call failed", failure);
            Bundle answer = new Bundle();
            answer.putString("error", "内置翻译暂不可用，请连接 Wi-Fi 后重试");
            return answer;
        }
    }

    private static String translationKey(String method, Bundle extras) {
        if (!"translate".equals(method) || extras == null) return null;
        String text = extras.getString("text", ""), source = extras.getString("source", "en"),
            target = extras.getString("target", "zh");
        if (text == null || text.length() > 24000 || source == null || target == null ||
            source.length() > 32 || target.length() > 32 || source.indexOf('\u0000') >= 0 || target.indexOf('\u0000') >= 0)
            return null;
        // LruCache synchronizes its operations. Only immutable strings remain in memory, never on disk.
        return source + '\u0000' + target + '\u0000' + text;
    }

    private static synchronized void initialize(Context context) throws Exception {
        if (entry != null) return;
        File directory = new File(context.getCodeCacheDir(), "githubcn-engine-" + BuildInfo.ENGINE_SHA256);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Engine cache unavailable");
        File archive = new File(directory, "engine.apk");
        if (!archive.isFile()) {
            File temporary = File.createTempFile("engine-", ".tmp", directory);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getAssets().open("githubcn-engine.apk");
                 FileOutputStream out = new FileOutputStream(temporary)) {
                // Mark before writing so Android 14+ never sees writable dynamic code.
                if (!temporary.setReadOnly()) throw new IOException("Cannot protect embedded code");
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) { hash.update(buffer, 0, count); out.write(buffer, 0, count); }
                out.getFD().sync();
            }
            StringBuilder digest = new StringBuilder();
            for (byte value : hash.digest()) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!BuildInfo.ENGINE_SHA256.equals(digest.toString())) {
                temporary.delete(); throw new IOException("Embedded engine checksum mismatch");
            }
            if (!temporary.renameTo(archive)) { temporary.delete(); throw new IOException("Cannot finalize engine"); }
        }
        File libraries = new File(directory, "lib");
        if (!libraries.isDirectory() && !libraries.mkdir()) throw new IOException("Native cache unavailable");
        try (ZipFile zip = new ZipFile(archive)) {
            String abi = null;
            for (String candidate : Build.SUPPORTED_ABIS) {
                if (zip.getEntry("lib/" + candidate + "/libtranslate_jni.so") != null) { abi = candidate; break; }
            }
            if (abi == null) throw new IOException("No embedded translator for this CPU");
            String prefix = "lib/" + abi + "/";
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry item = entries.nextElement();
                if (!item.getName().startsWith(prefix)) continue;
                String name = item.getName().substring(prefix.length());
                if (!name.matches("[A-Za-z0-9_.+-]+\\.so")) continue;
                File target = new File(libraries, name);
                if (target.isFile() && target.length() == item.getSize()) continue;
                File temporary = File.createTempFile("native-", ".tmp", libraries);
                try (InputStream in = zip.getInputStream(item); FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.getFD().sync();
                }
                if (!temporary.setReadOnly() || !temporary.renameTo(target)) {
                    temporary.delete(); throw new IOException("Cannot prepare native translation library");
                }
            }
        }
        // Platform-only parent prevents GitHub's R8-obfuscated GMS/AndroidX from
        // accidentally satisfying incompatible library references in ML Kit.
        final ClassLoader loader = new DexClassLoader(archive.getAbsolutePath(), directory.getAbsolutePath(),
            libraries.getAbsolutePath(), Context.class.getClassLoader());
        PackageInfo container = context.getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
        if (container == null || container.applicationInfo == null) throw new IOException("Invalid embedded archive");
        ApplicationInfo information = container.applicationInfo;
        information.sourceDir = archive.getAbsolutePath();
        information.publicSourceDir = archive.getAbsolutePath();
        final Resources resources = context.getPackageManager().getResourcesForApplication(information);
        Context wrapped = new ContextWrapper(context) {
            @Override public ClassLoader getClassLoader() { return loader; }
            @Override public Context getApplicationContext() { return this; }
            @Override public Resources getResources() { return resources; }
            @Override public AssetManager getAssets() { return resources.getAssets(); }
        };
        Method loaded = loader.loadClass("cn.local.githubcn.engine.Translation")
            .getMethod("call", Context.class, String.class, Bundle.class);
        engineContext = wrapped;
        entry = loaded;
        Log.i("GitHubCN", "Embedded translation loaded in app UID " + android.os.Process.myUid());
    }
}
