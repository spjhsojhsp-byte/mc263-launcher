package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The check the Play button runs while {@link #ENABLED} is set. It reads the device and the
 * launcher state, unpacks the bundled Java 25 runtime if needed, and shows the result in a dialog
 * that can be shared. It never downloads or starts Minecraft and never asks for an account.
 */
public final class DiagnosticReport {
    /** true: Play runs this check instead of downloading and starting the game. */
    public static final boolean ENABLED = false;

    private static final String GAME_VERSION = "26.3.1";
    private static final String MOJANG_VERSION_LIST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private DiagnosticReport() {}

    /** Runs the check on a background thread and shows the report on the UI thread. */
    public static void run(Activity activity) {
        Toast.makeText(activity, R.string.diag_running, Toast.LENGTH_SHORT).show();
        // Two runs at once would unpack the runtime into the same folder, so the second press is ignored.
        if (!RUNNING.compareAndSet(false, true)) return;
        Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            String report = buildReport(appContext);
            RUNNING.set(false);
            activity.runOnUiThread(() -> showReport(activity, report));
        }, "launcher-diagnostics").start();
    }

    private interface Section {
        void write(StringBuilder out) throws Exception;
    }

    private static String buildReport(Context context) {
        StringBuilder out = new StringBuilder();
        section(out, "App", o -> {
            o.append("application id: ").append(BuildConfig.APPLICATION_ID).append('\n');
            o.append("version: ").append(BuildConfig.VERSION_NAME).append('\n');
            o.append("debug build: ").append(BuildConfig.DEBUG).append('\n');
        });
        section(out, "Device", o -> {
            o.append("model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            o.append("android: ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            o.append("abis: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        });
        section(out, "Memory and storage", o -> {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            o.append("total RAM: ").append(info.totalMem / 1048576L).append(" MB\n");
            o.append("available RAM now: ").append(info.availMem / 1048576L).append(" MB\n");
            o.append("launcher RAM setting: ").append(LauncherPreferences.PREF_RAM_ALLOCATION).append(" MB\n");
            o.append("free space in app data: ")
                    .append(new File(Tools.DIR_DATA).getUsableSpace() / 1048576L).append(" MB\n");
        });
        section(out, "Graphics", o -> {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ConfigurationInfo config = am.getDeviceConfigurationInfo();
            o.append("OpenGL ES (reported): 0x").append(Integer.toHexString(config.reqGlEsVersion)).append('\n');
            int level = -1;
            int vulkan = -1;
            FeatureInfo[] features = context.getPackageManager().getSystemAvailableFeatures();
            if (features != null) {
                for (FeatureInfo feature : features) {
                    if (PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL.equals(feature.name)) level = feature.version;
                    if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(feature.name)) vulkan = feature.version;
                }
            }
            o.append("Vulkan level: ").append(level < 0 ? "not reported" : String.valueOf(level)).append('\n');
            if (vulkan < 0) {
                o.append("Vulkan version: not reported\n");
            } else {
                o.append("Vulkan version: ").append((vulkan >> 22) & 0x7f).append('.')
                        .append((vulkan >> 12) & 0x3ff).append('.').append(vulkan & 0xfff)
                        .append(" (raw 0x").append(Integer.toHexString(vulkan)).append(")\n");
            }
        });
        section(out, "Launcher profile", o -> {
            LauncherProfiles.load();
            String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            o.append("selected profile: ").append(key).append('\n');
            MinecraftProfile profile = null;
            if (key != null && LauncherProfiles.mainProfileJson != null) {
                profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            }
            if (profile == null) {
                o.append("profile entry: missing\n");
            } else {
                o.append("game version: ").append(profile.lastVersionId).append('\n');
                o.append("java: ").append(profile.javaDir).append('\n');
            }
        });
        section(out, "Accounts", o -> {
            String[] saved = new File(Tools.DIR_ACCOUNT_NEW).list();
            o.append("saved accounts: ").append(saved == null ? 0 : saved.length).append('\n');
        });
        section(out, "Minecraft " + GAME_VERSION + " files (this check does not download them)", o -> {
            File dir = new File(Tools.DIR_HOME_VERSION, GAME_VERSION);
            File json = new File(dir, GAME_VERSION + ".json");
            File jar = new File(dir, GAME_VERSION + ".jar");
            o.append("json: ").append(json.exists() ? json.length() + " bytes" : "missing").append('\n');
            o.append("jar: ").append(jar.exists() ? jar.length() + " bytes" : "missing").append('\n');
        });
        section(out, "Bundled Java 25 runtime", o -> {
            String result;
            try {
                result = NewJREUtil.diagnoseBundledJava25(context);
            } catch (Throwable t) {
                result = "FAILED: " + describe(t);
            }
            o.append("unpack: ").append(result).append('\n');
            File home = MultiRTUtils.getRuntimeHome("Internal-25");
            o.append("runtime folder: ").append(home.isDirectory() ? "present" : "missing").append('\n');
            o.append("bin/java: ").append(new File(home, "bin/java").exists() ? "present" : "missing").append('\n');
            o.append("lib/modules: ").append(new File(home, "lib/modules").exists() ? "present" : "missing").append('\n');
        });
        section(out, "Network", o -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(MOJANG_VERSION_LIST).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            try {
                o.append("Mojang version list: HTTP ").append(connection.getResponseCode()).append('\n');
            } finally {
                connection.disconnect();
            }
        });
        return out.toString();
    }

    private static void section(StringBuilder out, String title, Section body) {
        out.append("\n== ").append(title).append(" ==\n");
        try {
            body.write(out);
        } catch (Throwable t) {
            out.append("ERROR: ").append(describe(t)).append('\n');
        }
    }

    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(t.toString());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(3, frames.length); i++) {
            text.append("\n    at ").append(frames[i]);
        }
        if (t.getCause() != null) text.append("\n    caused by ").append(t.getCause());
        return text.toString();
    }

    private static void showReport(Activity activity, String report) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        TextView text = new TextView(activity);
        text.setText(report);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.diag_title)
                .setView(scroll)
                .setPositiveButton(R.string.diag_share, (dialog, which) -> share(activity, report))
                .setNegativeButton(R.string.diag_close, null)
                .show();
    }

    private static void share(Activity activity, String report) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, report);
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.diag_share)));
    }
}
