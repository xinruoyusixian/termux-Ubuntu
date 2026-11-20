package com.termux.app;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.termux.BuildConfig;
import com.termux.x11.LorieApp;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

/**
 * 继承 {@link LorieApp}：Termux:X11 的代码里到处 `(LorieApp) getApplicationContext()`，
 * 直接继承可以让 X11 与终端共存于同一个 Application 实例（也就同一个 APK）。
 * LorieApp.onCreate() 负责 X11 的偏好与通知渠道初始化，随后再走 Termux 的初始化。
 */
public class TermuxApplication extends LorieApp {

    private static final String LOG_TAG = "TermuxApplication";

    /** 自动取消注释 termux.properties 里的 allow-external-apps=true（幂等） */
    private static void enableAllowExternalApps(Context context) {
        try {
            File f = new File(TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/.termux/termux.properties");
            if (!f.exists()) return;
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.equals("# allow-external-apps = true") || line.equals("#allow-external-apps=true")) {
                    lines.set(i, "allow-external-apps = true");
                    changed = true;
                    break;
                }
                // 已显式设为 false 的尊重用户选择，不动
                if (line.equals("allow-external-apps = false") || line.equals("allow-external-apps=false")) {
                    return;
                }
            }
            if (changed) {
                Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
                Logger.logInfo(LOG_TAG, "Enabled allow-external-apps in termux.properties");
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not enable allow-external-apps: " + e.getMessage());
        }
    }

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // 默认非 root（proot）模式：首次安装建标记，之后尊重用户在菜单里的切换
        LinuxLauncher.ensureDefaultMode();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // 让 root 模式的音频兜底可用：start-xfce.sh 用 RunCommandService 拉起 pulseaudio，
        // Termux 默认把 allow-external-apps 注释掉，导致插件调用被拒（Error 2 通知）。
        // 本 App 的 RUN_COMMAND 调用都是自己发给自己（启动音频），自动开启该开关。
        enableAllowExternalApps(context);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);

            // 上一次 App 进程已经不在了，但它拉起的 X server / 桌面还在后台跑
            // （su 起的进程 PPID 会被 reparent 到 init，跟 App 脱钩）。
            // 这里负责把遗留会话清掉，等价于「杀 App = 关机」。
            try {
                LinuxLauncher.onAppProcessStart(this);
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "linux session takeover failed\n" + e);
            }
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
