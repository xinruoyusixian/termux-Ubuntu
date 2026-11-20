package com.termux.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

/**
 * 侧边菜单 / X11 悬浮菜单里的「桌面控制」动作。
 *
 * 全部动作都需要 root（起 X server / chroot / 音频），所以统一走 su。
 * 对 com.termux.x11 包可见，因此成员必须是 public。
 */
public final class PadMenu {

    private static final String X11_ACTIVITY = "com.termux.x11.MainActivity";
    private static final String TERMUX_ACTIVITY = "com.termux.app.TermuxActivity";
    private static final String FILES_DIR = "/data/data/com.termux/files";
    private static final String START_SCRIPT = FILES_DIR + "/start-xfce.sh";
    private static final String AUDIO_SCRIPT = FILES_DIR + "/home/start-audio.sh";

    private PadMenu() {}

    /** 因为缺存储权限跳去了系统设置，回来后要自动补上挂载 */
    private static boolean sPendingMount = false;

    private static void toast(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            // 贴底部显示时会被 X11 桌面底部的面板/导航栏挡住，统一放到顶部
            Toast t = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT);
            t.setGravity(android.view.Gravity.TOP, 0, 160);
            t.show();
        });
    }

    /** 只把桌面栈拉起来（幂等：已在运行会直接跳过），不切换界面 */
    public static void startDesktopStack(Context ctx) {
        if (LinuxLauncher.hasRoot(ctx)) {
            runSu(ctx, "rm -f " + FILES_DIR + "/.session-closing; "
                    + "sh " + START_SCRIPT + " >" + FILES_DIR + "/log/desktop-start.log 2>&1", "桌面已拉起");
        } else {
            runSh(ctx, "rm -f " + FILES_DIR + "/.session-closing; "
                    + "mkdir -p " + FILES_DIR + "/log; "
                    + "sh " + LinuxLauncher.NOROOT_START_SCRIPT
                    + " >" + FILES_DIR + "/log/desktop-start.log 2>&1", "桌面已拉起");
        }
    }

    /**
     * 桌面栈是否已在运行：看 X server 的 unix socket 是否存在。
     * 从 X11 切到终端时桌面并没有关，socket 仍在 —— 此时只需把 X11 切回前台，
     * 绝不能再跑 startDesktopStack，否则会重复拉起 X server / 出现第二个 X11 窗口。
     */
    private static boolean isDesktopRunning() {
        try {
            String x11sock = FILES_DIR + "/ubuntu/tmp/.X11-unix/X0";
            String norootSock = "/data/data/com.termux/files/usr/tmp/.X11-unix/X0";
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "test -S " + x11sock + " || test -S " + norootSock});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动/切回 X 显示界面；桌面栈没在跑时才拉起（幂等，杜绝第二个 X11 窗口） */
    public static void openDesktop(Context ctx) {
        boolean alreadyRunning = isDesktopRunning();
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(ctx.getPackageName(), X11_ACTIVITY));
            // singleInstance + CLEAR_TOP：复用已有 X11 窗口并切到前台，不新建第二个窗口
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "X 显示界面启动失败：" + e.getMessage());
            return;
        }
        if (alreadyRunning) {
            toast(ctx, "已切回 Ubuntu 桌面");
        } else {
            toast(ctx, "正在启动 Ubuntu 桌面…");
            startDesktopStack(ctx);
        }
    }

    /** 从 X11 桌面切回 Termux 终端界面（桌面继续在后台跑，不退出） */
    public static void openTermux(Context ctx) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(ctx.getPackageName(), TERMUX_ACTIVITY));
            // REORDER_TO_FRONT：终端界面之前启动过，直接把它搬到前台，
            // 不新建实例，桌面 Activity 仍留在任务栈里随时切回。
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "终端界面启动失败：" + e.getMessage());
        }
    }

    /**
     * 关闭整套 Linux（X server + 桌面 + 音频 + 卸载挂载），然后关掉 App 回到安卓桌面。
     * 清理放在后台线程；完成后切回主线程 finishAffinity() 关掉所有 Activity。
     * 两个入口（X11 悬浮面板、终端抽屉）都只调这一个方法，关 App 逻辑集中在这里。
     */
    public static void stopDesktop(final Context ctx) {
        toast(ctx, "正在关闭 Ubuntu…");
        new Thread(() -> {
            boolean ok = LinuxLauncher.killSession(ctx);
            toast(ctx, ok ? "Ubuntu 已关闭，正在退出…" : "关闭遇到问题，仍将退出");
            // 关掉整个 App（所有 Activity）→ 回到安卓桌面。
            // finishAffinity 必须在主线程调用。
            if (ctx instanceof android.app.Activity) {
                final android.app.Activity act = (android.app.Activity) ctx;
                act.runOnUiThread(() -> {
                    try { act.finishAffinity(); } catch (Exception ignored) {}
                });
            }
        }, "stop-desktop").start();
    }

    /** 重启整个系统：先彻底关闭 X server / 桌面 / 音频，再重新拉起全套 */
    public static void restartDesktop(Context ctx) {
        toast(ctx, "正在重启 Ubuntu 系统…");
        // 注意 1：X server 是 app_process 起的，/proc/<pid>/comm 是 "main"，
        //         pkill -x termux-x11 永远匹配不到，必须按命令行匹配。
        // 注意 2：不能用 pkill -f termux-x11：这条命令行本身就含 "termux-x11"，
        //         会把执行它的 shell 自己一起杀掉。用 [t] 括号技巧规避。
        // 注意 3：必须先确认进程真的退出了再删 socket，否则删掉 socket 而 server 还活着，
        //         会出现"X server 未启动"却连不上的死局。
        String kill = "for i in 1 2 3 4 5 6; do "
                + "  p=$(pgrep -f '[t]ermux-x11'); "
                + "  [ -z \"$p\" ] && break; "
                + "  kill -9 $p 2>/dev/null; sleep 1; "
                + "done;";
        String cmd = "rm -f " + FILES_DIR + "/.session-closing; " + kill
                + " pkill -x xfce4-session 2>/dev/null; pkill -x startxfce4 2>/dev/null; "
                + "rm -f " + FILES_DIR + "/ubuntu/tmp/.X0-lock " + FILES_DIR + "/ubuntu/tmp/.X11-unix/X0; "
                + "sleep 1; sh " + START_SCRIPT;
        if (LinuxLauncher.hasRoot(ctx)) {
            runSu(ctx, cmd, "系统已重启");
        } else {
            // 非 root：直接跑非 root 清理脚本（会连 X server 一起停），再整套拉起
            runSh(ctx, "rm -f " + FILES_DIR + "/.session-closing; "
                    + "mkdir -p " + FILES_DIR + "/log; "
                    + "sh " + LinuxLauncher.NOROOT_CLEANUP_SCRIPT + " >/dev/null 2>&1; "
                    + "sleep 1; sh " + LinuxLauncher.NOROOT_START_SCRIPT
                    + " >" + FILES_DIR + "/log/desktop-start.log 2>&1", "桌面已重启");
        }
    }

    /** 只重启音频（Termux 侧 PulseAudio） */
    public static void restartAudio(Context ctx) {
        toast(ctx, "正在重启音频…");
        // 运行时目录必须归 10183（termux）所有：以前若被 root 起过一次，
        // .pulse-rt 会变成 root:root，之后普通身份启动会直接 EACCES 失败（表现为完全无声）。
        // 所以每次都删掉重建，别去猜里面残留了什么。
        String cmd = "pkill -x pulseaudio; sleep 1; "
                + "rm -rf " + FILES_DIR + "/home/.pulse-rt; "
                + "mkdir -p " + FILES_DIR + "/home/.pulse-rt; "
                + "chown 10183:10183 " + FILES_DIR + "/home/.pulse-rt; "
                + "su 10183 -c \"sh " + AUDIO_SCRIPT + "\"";
        if (LinuxLauncher.hasRoot(ctx)) {
            runSu(ctx, cmd, "音频已重启");
        } else {
            // 非 root：App 自己就是 10183，不用 chown / su，直接跑
            runSh(ctx, "pkill -x pulseaudio; sleep 1; "
                    + "rm -rf " + FILES_DIR + "/home/.pulse-rt; "
                    + "mkdir -p " + FILES_DIR + "/home/.pulse-rt; "
                    + "sh " + AUDIO_SCRIPT, "音频已重启");
        }
    }

    /**
     * 菜单「挂载 SD」。
     *
     * 桌面里点不了这件事：存储权限是 App 侧的，chroot 内的 alan 与 com.termux
     * 同 uid(10183)，没这个权限桌面里就是一个打不开的空目录。
     * 所以：没权限先解释清楚并把人送到系统设置页；有权限就直接挂。
     */
    public static void mountSd(final android.app.Activity act) {
        if (!LinuxLauncher.hasStorageAccess(act)) {
            sPendingMount = true;
            new AlertDialog.Builder(act)
                    .setTitle("需要存储权限")
                    .setMessage("Ubuntu 桌面里的用户和本应用共用同一个系统身份。\n"
                            + "没有「所有文件访问」权限时，桌面里打不开平板上的文件，也看不到 SD 卡。\n\n"
                            + "点「去授权」打开系统设置，把「允许访问所有文件」打开，返回后会自动完成挂载。")
                    .setNegativeButton("取消", (d, w) -> sPendingMount = false)
                    .setPositiveButton("去授权", (d, w) -> LinuxLauncher.openStorageSettings(act))
                    .show();
            return;
        }
        doMount(act);
    }

    /**
     * 从系统授权页返回时调用：如果刚才正是因为缺权限跳过去的，
     * 现在有权限了就自动接着挂载，省掉「回来再点一次」。
     */
    public static void resumePendingMount(android.app.Activity act) {
        if (!sPendingMount) return;
        if (!LinuxLauncher.hasStorageAccess(act)) return;
        sPendingMount = false;
        doMount(act);
    }

    private static void doMount(final android.app.Activity act) {
        toast(act, "正在挂载存储…");
        new Thread(() -> {
            String r = LinuxLauncher.mountStorage(act);
            toast(act, r);
        }, "mount-sd").start();
    }

    /** 重新导入系统包：清空已解压的系统并从 /sdcard 重新解压 */
    public static void importRootfs(final Context ctx) {
        File ext = LinuxLauncher.findExternalRootfs();
        if (ext == null) {
            new AlertDialog.Builder(ctx)
                    .setTitle("未找到系统包")
                    .setMessage("请把系统包放到以下任一位置后重试：\n\n"
                            + "/sdcard/UbuntuXFCE/rootfs.tar.gz\n"
                            + "/sdcard/Download/rootfs.tar.gz\n\n"
                            + "系统包是一个 .tar.gz 文件，通常 1~2 GB。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(ctx)
                .setTitle("重新导入系统")
                .setMessage("将从以下文件重新导入：\n" + ext.getAbsolutePath()
                        + "\n\n会先关闭桌面，再清空当前已解压的系统"
                        + "（/data/data/com.termux/files/ubuntu），耗时约 5~15 分钟，"
                        + "期间请勿关闭应用、不要切走太多后台。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导入", (d, w) -> {
                    toast(ctx, "开始导入，请耐心等待…");
                    new Thread(() -> {
                        String r = LinuxLauncher.reimportRootfs(ctx);
                        toast(ctx, r.startsWith("OK:") ? "导入完成，可点「桌面」启动" : r);
                    }).start();
                })
                .show();
    }

    private static void runSu(final Context ctx, final String cmd, final String okMsg) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{LinuxLauncher.suBinary(), "-c", cmd});
                int code = p.waitFor();
                toast(ctx, code == 0 ? okMsg : "执行失败（exit " + code + "）");
            } catch (Exception e) {
                toast(ctx, "执行失败：" + e.getMessage());
            }
        }).start();
    }

    /** 非 root 版：以 App 自己的身份跑 sh，不用 su */
    private static void runSh(final Context ctx, final String cmd, final String okMsg) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                int code = p.waitFor();
                toast(ctx, code == 0 ? okMsg : "执行失败（exit " + code + "）");
            } catch (Exception e) {
                toast(ctx, "执行失败：" + e.getMessage());
            }
        }).start();
    }
}
