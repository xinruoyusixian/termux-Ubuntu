package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();
        setPadMenuButtonsView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        // 从「所有文件访问」授权页返回时，若刚才正是因为缺权限跳过去的，自动补挂载
        PadMenu.resumePendingMount(this);

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();
        LinuxLauncher.trace("onServiceConnected empty=" + mTermuxService.isTermuxSessionsEmpty() + " visible=" + mIsVisible);

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    launchLinuxIfNeeded();
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // Custom: sessions restored from a previous run. Still make sure we are in Linux.
            if (mIsVisible) launchLinuxIfNeeded();

            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    /** Custom: ensure a Linux session exists; create it once on first launch. */
    private void launchLinuxIfNeeded() {
        LinuxLauncher.trace("launchLinuxIfNeeded entered, svc=" + (mTermuxService != null));
        if (mTermuxService == null) return;
        // If a Linux session already exists, just select it.
        for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts
                : mTermuxService.getTermuxSessions()) {
            String name = ts.getExecutionCommand().shellName;
            if (name != null && name.startsWith("Linux")) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(ts.getTerminalSession());
                return;
            }
        }
        // Otherwise prepare rootfs + start a fresh Linux session (off the UI thread).
        new Thread(() -> {
            final LinuxLauncher.LaunchPlan plan = LinuxLauncher.prepare(TermuxActivity.this);
            runOnUiThread(() -> {
                if (mTermuxService == null || isFinishing()) return;
                try {
                    if (plan.executable != null) {
                        mTermuxTerminalSessionActivityClient.addNewSessionWithCommand(
                                plan.executable, plan.arguments, "Linux (" + plan.message + ")");
                    } else {
                        mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                        com.termux.shared.interact.MessageDialogUtils.showMessage(
                                TermuxActivity.this, "无法进入 Linux", plan.message, null);
                    }
                } catch (WindowManager.BadTokenException ignored) {
                    // Activity finished.
                }
            });
        }, "linux-launcher").start();
    }

    /**
     * 模式切换后的「立即重启」：桌面栈 + 终端会话一起按新模式重建（v40）。
     *
     * 为什么不能只调 PadMenu.restartDesktop()：那只会杀掉 X server / XFCE / 音频
     * 再按新模式拉桌面，但终端里的 shell 会话是 App 冷启动时 launchLinuxIfNeeded()
     * 建立的，一直挂着旧模式（非 root 就是普通 sh，root 才是 chroot bash）。
     * 结果就是「提示已切 root、桌面也是 root，但终端提示符还是 $」——用户只能杀 App
     * 让会话重建才生效。这里把会话也换掉，才是真正的整系统重启。
     *
     * 顺序：① 清理旧桌面栈（root 全量脚本通杀任何模式）→ ② kill 所有 "Linux" 会话
     * → ③ 按新标记重新 prepare() → ④ 新建会话（其启动命令自带拉桌面）。
     */
    public void restartSystemForModeSwitch() {
        showToast("正在重启 Ubuntu 系统（终端+桌面）…", true);
        new Thread(() -> {
            // ① 清理旧桌面栈。root 版清理脚本按命令行通杀 X server/XFCE/proot 并
            //    卸载 chroot 挂载，无论旧会话是哪种模式都能清干净；等待完成再往下走。
            LinuxLauncher.ensureRootCleanupScript(this);
            final boolean hasSu = LinuxLauncher.isSuAvailable();
            final String files = "/data/data/com.termux/files";
            String cleanup = hasSu
                    ? "sh " + files + "/linux-cleanup.sh"
                    : "sh " + LinuxLauncher.NOROOT_CLEANUP_SCRIPT;
            String shell = hasSu ? LinuxLauncher.suBinary() : "/system/bin/sh";
            try {
                Process p = Runtime.getRuntime().exec(new String[]{shell, "-c",
                        "rm -f " + files + "/.session-closing; mkdir -p " + files + "/log; "
                                + cleanup + " >/dev/null 2>&1"});
                if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (Exception ignored) {}

            // ② 回 UI 线程 kill 旧会话并重建（会话操作必须在主线程，和冷启动同款）。
            runOnUiThread(() -> {
                if (isFinishing() || mTermuxService == null) return;
                for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts
                        : new java.util.ArrayList<>(mTermuxService.getTermuxSessions())) {
                    String name = ts.getExecutionCommand().shellName;
                    if (name != null && name.startsWith("Linux")) {
                        // SIGKILL 旧会话，然后直接从列表移除对象。
                        // 不能用 removeTermuxSession()/finish()：SIGKILL 会把
                        // ExecutionCommand 置成 failed，finish() 在 failed 状态直接
                        // return，会话变僵尸 —— 下次冷启动 launchLinuxIfNeeded() 会
                        // 选中它，显示 "[Process completed] (code 137)" 不再重建。
                        // 这里照上游 killAllTermuxExecutionCommands 的做法：kill 后
                        // 显式从 mTermuxSessions（getTermuxSessions 返回其引用）移除。
                        // processResult=false：普通会话无需走插件结果处理。
                        ts.killIfExecuting(this, false);
                        mTermuxService.getTermuxSessions().remove(ts);
                    }
                }
                // 删掉旧的模式标记，让新会话的 boot-log.sh 重写，供下面校验
                new java.io.File("/data/data/com.termux/files/.boot-mode").delete();

                // ③④ 按新标记 prepare + 新建会话（与 launchLinuxIfNeeded 的会话创建段一致）
                final boolean targetNonRoot = LinuxLauncher.isNonRootMode();
                new Thread(() -> {
                    final LinuxLauncher.LaunchPlan plan = LinuxLauncher.prepare(TermuxActivity.this);
                    runOnUiThread(() -> {
                        if (mTermuxService == null || isFinishing()) return;
                        try {
                            if (plan.executable != null) {
                                mTermuxTerminalSessionActivityClient.addNewSessionWithCommand(
                                        plan.executable, plan.arguments, "Linux (" + plan.message + ")");
                                showToast("已按新模式重启（终端+桌面）：" + plan.message, true);
                                // v41 兜底：延时校验终端会话真正跑起来的模式，
                                // 不符/没起来就杀 App 冷启动（冷启动必按新标记）。
                                verifyModeAndRelaunchIfNeeded(targetNonRoot);
                            } else {
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                                com.termux.shared.interact.MessageDialogUtils.showMessage(
                                        TermuxActivity.this, "无法进入 Linux", plan.message, null);
                                verifyModeAndRelaunchIfNeeded(targetNonRoot);
                            }
                        } catch (WindowManager.BadTokenException ignored) {
                            // Activity 已结束
                        }
                    });
                }, "relaunch-linux-session").start();
            });
        }, "restart-system-mode-switch").start();
    }

    /**
     * v41 模式切换兜底：延时读 boot-log.sh 写下的 .boot-mode（终端会话真正启动的模式），
     * 若与目标模式不符或会话已死，就杀掉 App 再自动拉起——冷启动必定按新标记
     * prepare，是最彻底的兜底。用户提的"切不过来就杀自己再拉起来"。
     */
    private void verifyModeAndRelaunchIfNeeded(final boolean targetNonRoot) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            LinuxLauncher.trace("mode verify tick: want=" + (targetNonRoot ? "proot" : "root")
                    + " finishing=" + isFinishing());
            if (isFinishing()) return;
            String want = targetNonRoot ? "proot" : "root";
            String actual = "";
            try {
                java.io.File f = new java.io.File("/data/data/com.termux/files/.boot-mode");
                if (f.exists()) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                    String line = r.readLine();
                    actual = line == null ? "" : line.trim();
                    r.close();
                }
            } catch (Exception ignored) {}

            // 会话是否还活着（没变成 [Process completed]）
            boolean sessionAlive = false;
            if (mTermuxService != null) {
                for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts
                        : mTermuxService.getTermuxSessions()) {
                    String name = ts.getExecutionCommand() == null ? null : ts.getExecutionCommand().shellName;
                    if (name != null && name.startsWith("Linux") && ts.getTerminalSession().isRunning()) {
                        sessionAlive = true;
                        break;
                    }
                }
            }

            LinuxLauncher.trace("mode verify result: want=" + want + " actual=" + actual
                    + " alive=" + sessionAlive);
            if (!want.equals(actual) || !sessionAlive) {
                LinuxLauncher.trace("mode mismatch/failed: want=" + want + " actual=" + actual
                        + " alive=" + sessionAlive + " -> force relaunch");
                forceRelaunchApp();
            }
        }, 6000);
    }

    /** 杀掉 App 并安排自动拉起（su am start 主路径 + AlarmManager 双保险，冷启动按新标记重来）。 */
    private void forceRelaunchApp() {
        showToast("终端未切到目标模式，正在重启 App 重试…", true);
        new Thread(() -> {
            // 1) 停前台服务，否则进程杀不干净
            try {
                Intent svc = new Intent(this, com.termux.app.TermuxService.class);
                svc.setAction("com.termux.service_stop");
                startService(svc);
            } catch (Exception ignored) {}
            try { stopService(new Intent(this, com.termux.app.TermuxService.class)); }
            catch (Exception ignored) {}
            // 2) 主拉起路径：su 执行 am start。
            //    不能用 App 自己的 AlarmManager+PendingIntent.getActivity：
            //    Android 12+ 禁止后台广播拉 Activity，alarm 触发了但 Activity 起不来
            //    （实测 pid 杀掉后始终为空）。su 是 root uid，不受该限制。
            //    sleep 2 等本进程 killProcess 死透再拉，避免拉到正在死的旧进程。
            //    注意命令末尾不能加 & 后台化：su 会话一退出会连带清掉后台子进程，
            //    am start 根本不会执行（实测）。Runtime.exec 本身已是独立进程，
            //    让 su 前台跑完 sleep+am start 即可，主进程死后它照样继续。
            try {
                String su = LinuxLauncher.suBinary();
                String relaunch = "sleep 2; am start -n com.termux/"
                        + "com.termux.app.TermuxActivity";
                Runtime.getRuntime().exec(new String[]{su, "-c", relaunch});
                LinuxLauncher.trace("force relaunch via su am start scheduled");
            } catch (Exception e) {
                LinuxLauncher.trace("su relaunch failed: " + e.getMessage());
            }
            // 2b) 双保险：AlarmManager 3 秒后再拉一次（某些版本 su 不可用时兜底）
            try {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 9411, new Intent(this, TermuxActivity.class),
                        android.app.PendingIntent.FLAG_IMMUTABLE
                                | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                am.setExact(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 3500, pi);
                LinuxLauncher.trace("force relaunch scheduled");
            } catch (Exception e) {
                LinuxLauncher.trace("force relaunch schedule failed: " + e.getMessage());
            }
            // 3) 关 Activity + 杀进程
            runOnUiThread(() -> {
                try { finishAffinity(); } catch (Exception ignored) {}
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 400);
            });
        }, "force-relaunch").start();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    /** 侧边菜单里的 Ubuntu 桌面控制按钮 */
    private void setPadMenuButtonsView() {
        findViewById(R.id.pad_drawer_button).setOnClickListener(v -> getDrawer().openDrawer(Gravity.RIGHT));
        findViewById(R.id.pad_desktop_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.openDesktop(this);
        });
        findViewById(R.id.pad_restart_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.restartDesktop(this);
        });
        findViewById(R.id.pad_audio_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.restartAudio(this);
        });
        findViewById(R.id.pad_import_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.importRootfs(this);
        });
        findViewById(R.id.pad_stop_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            // 与 X11 悬浮菜单一致：关闭是破坏性操作，先确认
            new AlertDialog.Builder(this)
                    .setTitle("关闭 Ubuntu")
                    .setMessage("会终止桌面、X server 与音频并退出 App，未保存的程序会丢失。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("关闭并退出", (d, w) -> PadMenu.stopDesktop(this))
                    .show();
        });
        findViewById(R.id.pad_lifecycle_button).setOnClickListener(v -> {
            boolean now = !LinuxLauncher.isCloseWithApp(this);
            LinuxLauncher.setCloseWithApp(this, now);
            LinuxLauncher.syncWatchdog(this);
            setPadLifecycleButtonText();
            Logger.showToast(this, now
                    ? "已开启：App 退出后系统也一并关闭"
                    : "已关闭：App 退出后系统继续在后台运行", true);
        });
        findViewById(R.id.pad_mountsd_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.mountSd(this);
        });
        // root / 非 root 模式切换：只改启动标记，确认后重启整套系统生效。
        // 设备没有真正可用的 su 时（装了管理器≠su 可用）明确提示，不允许切过去。
        findViewById(R.id.pad_mode_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            final android.view.View btn = v;
            btn.setEnabled(false);
            // su 实测要起进程，放到子线程：主线程直接调会在授权弹窗时卡死界面（ANR）
            new Thread(() -> {
                final boolean toRoot = LinuxLauncher.isNonRootMode();
                final boolean canRoot = toRoot ? LinuxLauncher.isSuAvailable() : true;
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    if (!canRoot) {
                        Logger.showToast(this, "此设备没有可用的 root，无法切换到 root 模式", true);
                        return;
                    }
                    String mode = LinuxLauncher.toggleMode();
                    if (mode.startsWith("切换失败")) {
                        Logger.showToast(this, mode, true);
                        return;
                    }
                    setPadModeButtonText();
                    new AlertDialog.Builder(this)
                            .setTitle("已切换到 " + mode)
                            .setMessage("新模式会在下次启动 Ubuntu 时生效。是否立即重启系统？")
                            .setNegativeButton("稍后", null)
                            // v40: 模式切换必须连终端会话一起重建。
                            // PadMenu.restartDesktop() 只重启桌面栈，终端 shell 还是
                            // 冷启动时按旧模式建的会话 —— 表现为"提示已切 root 但终端
                            // 仍是 $ / Ubuntu(proot)"，只有杀 App 才切换。
                            .setPositiveButton("立即重启", (d, w) -> restartSystemForModeSwitch())
                            .show();
                });
            }, "check-root-for-mode").start();
        });
        findViewById(R.id.pad_backup_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.backupSystem(this);
        });
        findViewById(R.id.pad_restore_button).setOnClickListener(v -> {
            getDrawer().closeDrawers();
            PadMenu.restoreSystem(this);
        });
        findViewById(R.id.pad_scale_button).setOnClickListener(v -> {
            int next = LinuxLauncher.nextDisplayScale(LinuxLauncher.getDisplayScale(this));
            LinuxLauncher.setDisplayScale(this, next);
            setPadScaleButtonText();
            Logger.showToast(this, "界面缩放 " + next + "%", true);
            getDrawer().closeDrawers();
        });
        setPadLifecycleButtonText();
        setPadScaleButtonText();
        setPadModeButtonText();
        // 「挂载下载」在 root / 非 root 下都保留：
        // 非 root 走 Android「所有文件访问权限」授权，授权后 proot 会把真实
        // /sdcard/Download 映射到 Ubuntu 的 /mnt/download，用于数据交换。
    }

    private void setPadModeButtonText() {
        Button b = findViewById(R.id.pad_mode_button);
        if (b == null) return;
        b.setText(LinuxLauncher.isNonRootMode() ? "模式：非root" : "模式：root");
    }

    private void setPadScaleButtonText() {
        Button b = findViewById(R.id.pad_scale_button);
        if (b == null) return;
        b.setText("界面缩放 " + LinuxLauncher.getDisplayScale(this) + "%");
    }

    private void setPadLifecycleButtonText() {
        Button b = findViewById(R.id.pad_lifecycle_button);
        if (b == null) return;
        boolean on = LinuxLauncher.isCloseWithApp(this);
        b.setText(on ? "随 App 关闭 ✓" : "随 App 关闭 ✗");
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.RIGHT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
