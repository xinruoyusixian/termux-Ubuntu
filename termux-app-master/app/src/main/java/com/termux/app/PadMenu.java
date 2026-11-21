package com.termux.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
    // root 版全量清理脚本：按命令行通杀所有模式的桌面进程并卸载 chroot 挂载。
    // 用于模式切换重启时彻底停掉旧桌面（含从 root 切走时 App 杀不掉的 root 进程）。
    private static final String CLEANUP_SCRIPT_ROOT = FILES_DIR + "/linux-cleanup.sh";
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
        // v39: 用标记（用户选择的模式）而非带缓存的 hasRoot，避免切换后拉起旧模式桌面
        if (!LinuxLauncher.isNonRootMode()) {
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
     * 关闭整套 Linux（X server + 桌面 + 音频 + 卸载挂载），然后彻底杀掉 App 回到安卓桌面。
     * 清理在后台线程；完成后停前台服务、finish 所有 Activity、最后 killProcess 确保进程退出
     * （只 finishAffinity 不够：TermuxService 前台服务会让进程残留）。
     * 两个入口（X11 悬浮面板、终端抽屉）都只调这一个方法。
     */
    public static void stopDesktop(final Context ctx) {
        toast(ctx, "正在关闭 Ubuntu…");
        new Thread(() -> {
            boolean ok = LinuxLauncher.killSession(ctx);
            toast(ctx, ok ? "Ubuntu 已关闭，正在退出…" : "关闭遇到问题，仍将退出");
            final android.app.Activity act = (ctx instanceof android.app.Activity)
                    ? (android.app.Activity) ctx : null;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // 1) 停掉 Termux 前台服务（否则进程不会真正退出）
                try {
                    Intent svc = new Intent(ctx, com.termux.app.TermuxService.class);
                    svc.setAction("com.termux.service_stop");
                    ctx.startService(svc);
                } catch (Exception ignored) {}
                try { ctx.stopService(new Intent(ctx, com.termux.app.TermuxService.class)); }
                catch (Exception ignored) {}
                // 2) 关掉所有 Activity
                if (act != null) {
                    try { act.finishAffinity(); } catch (Exception ignored) {}
                }
                // 3) 延迟杀进程，确保 finish/服务停止已执行
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 300);
            }, 400);
        }, "stop-desktop").start();
    }

    /**
     * 重启整个系统：先彻底关闭旧桌面（不管它运行在哪种模式），再按当前选择的模式拉起。
     *
     * 关键（v39 修复「切了模式重启不生效」）：
     * - 目标模式只认 .force-noroot 标记（isNonRootMode），这是菜单切换写入的真相源。
     *   绝不能用 hasRoot()：它带进程级 su 探测缓存，切换瞬间缓存可能与标记不一致，
     *   导致按旧模式重启 → 用户看到「切了 root 但重启还是 proot」，只有杀 App 清缓存才生效。
     * - 清理必须用「能杀掉旧模式进程」的权限：从 root 切到非 root 时，旧桌面是 root 进程，
     *   App 身份（runSh）杀不掉。所以只要设备有 su，就一律用 su 跑 root 版全量清理脚本
     *   （linux-cleanup.sh 按命令行通杀 X server/XFCE/proot，并卸载 chroot 挂载）。
     * - 启动阶段按目标模式选身份：非 root 必须 App 身份（脚本以 App uid 运行，用 su 跑会
     *   造成 root 属主污染）；root 用 su。清理与启动拼在同一条命令里串行执行，先清完再起。
     */
    public static void restartDesktop(Context ctx) {
        toast(ctx, "正在重启 Ubuntu 系统…");
        // v39: isSuAvailable() 会实测 su（授权弹窗时最多等 5s×多路径），
        // 不能在 UI 线程调用，否则有 ANR 风险。整段重启准备放到后台线程。
        new Thread(() -> doRestartDesktop(ctx), "restart-desktop").start();
    }

    private static void doRestartDesktop(Context ctx) {
        // 确保 root 版清理脚本存在（非 root 会话里可能没落地）
        LinuxLauncher.ensureRootCleanupScript(ctx);

        final boolean targetNonRoot = LinuxLauncher.isNonRootMode();
        final boolean hasSu = LinuxLauncher.isSuAvailable();

        // 清理：有 su 就用 root 版全量清理（通杀任何模式的旧桌面）；否则 App 版清理。
        // 两个清理脚本都自带 X server socket 删除，且按命令行匹配（规避 self-match 自杀）。
        String cleanup;
        if (hasSu) {
            cleanup = "sh " + CLEANUP_SCRIPT_ROOT;
        } else {
            cleanup = "sh " + LinuxLauncher.NOROOT_CLEANUP_SCRIPT;
        }

        String startCmd = targetNonRoot
                ? "sh " + LinuxLauncher.NOROOT_START_SCRIPT
                : "sh " + START_SCRIPT;
        String full = "rm -f " + FILES_DIR + "/.session-closing; mkdir -p " + FILES_DIR + "/log; "
                + cleanup + " >/dev/null 2>&1; sleep 1; "
                + startCmd + " >" + FILES_DIR + "/log/desktop-start.log 2>&1";

        // 清理阶段需要 root 权限（杀 root 旧桌面/卸载挂载），所以整条命令用 su 跑；
        // 但若目标是「非 root」，用 su 跑启动脚本会造成 root 属主污染 —— 因此拆成两段：
        // ① su 清理（等待完成） ② 按目标模式用正确身份启动。
        if (targetNonRoot) {
            // 非 root 目标：su 只负责清理，完成后用 App 身份启动 proot 桌面。
            if (hasSu) {
                final String rootCleanup = "rm -f " + FILES_DIR + "/.session-closing; mkdir -p "
                        + FILES_DIR + "/log; sh " + CLEANUP_SCRIPT_ROOT + " >/dev/null 2>&1";
                new Thread(() -> {
                    try {
                        Process p = Runtime.getRuntime().exec(
                                new String[]{LinuxLauncher.suBinary(), "-c", rootCleanup});
                        // 必须等清理真正结束再启动：否则旧的 root XFCE/X server 还占着 :0，
                        // 新的 proot 桌面起不来——正是「切换后重启不生效」的竞态。
                        boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                        if (!done) {
                            p.destroyForcibly();
                            toast(ctx, "清理超时，已取消启动，请重试");
                            return;
                        }
                        int code = p.exitValue();
                        if (code != 0) {
                            toast(ctx, "清理失败（exit " + code + "），已取消启动");
                            return;
                        }
                    } catch (Exception e) {
                        toast(ctx, "清理异常：" + e.getMessage());
                        return;
                    }
                    // 清理完成后，App 身份启动非 root 桌面
                    runSh(ctx, "rm -f " + FILES_DIR + "/.session-closing; mkdir -p " + FILES_DIR + "/log; "
                            + "sh " + LinuxLauncher.NOROOT_START_SCRIPT
                            + " >" + FILES_DIR + "/log/desktop-start.log 2>&1", "桌面已重启（非 root）");
                }, "restart-nonroot").start();
            } else {
                runSh(ctx, full, "桌面已重启（非 root）");
            }
        } else {
            // root 目标：清理 + 启动都用 su（root 桌面本来就是 su/chroot 起的）
            runSu(ctx, full, "系统已重启（root）");
        }
    }

    /** 只重启音频（Termux 侧 PulseAudio） */
    public static void restartAudio(Context ctx) {
        toast(ctx, "正在重启音频…");
        // 运行时目录必须归 App uid 所有：以前若被 root 起过一次，
        // .pulse-rt 会变成 root:root，之后普通身份启动会直接 EACCES 失败（表现为完全无声）。
        // 所以每次都删掉重建，别去猜里面残留了什么。
        // uid 动态取（格式化重装/不同设备会变，不能写死 10183）。
        int appUid = android.os.Process.myUid();
        String cmd = "pkill -x pulseaudio; sleep 1; "
                + "rm -rf " + FILES_DIR + "/home/.pulse-rt; "
                + "mkdir -p " + FILES_DIR + "/home/.pulse-rt; "
                + "chown " + appUid + ":" + appUid + " " + FILES_DIR + "/home/.pulse-rt; "
                + "su " + appUid + " -c \"sh " + AUDIO_SCRIPT + "\"";
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
     * 存储权限是 App 侧的：Linux 侧（root chroot 或 proot）与 com.termux 共用
     * 内核 uid，没这个权限桌面里就是一个打不开的空目录。
     * 所以：没权限先解释清楚并把人送到系统设置页；有权限就直接挂/校验。
     */
    public static void mountSd(final android.app.Activity act) {
        if (!LinuxLauncher.hasStorageAccess(act)) {
            sPendingMount = true;
            new AlertDialog.Builder(act)
                    .setTitle("需要存储权限")
                    .setMessage("Ubuntu 需要与平板交换 Download 里的文件。\n"
                            + "Linux 侧和本应用共用同一个系统身份，没有「所有文件访问」权限时，"
                            + "桌面里打不开下载目录。\n\n"
                            + "点「去授权」打开系统设置，把「允许访问所有文件」打开，返回后会自动完成挂载。"
                            + "root 和非 root 模式使用同一套授权。")
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

    /**
     * 备份当前系统：把整个 ubuntu 目录（含用户个人信息）打到 /sdcard。
     * 与发布包相反 —— 这是用户主动备份，个人数据原样保留。
     * 备份会先停掉桌面进程，需提醒用户；耗时较长，放子线程。
     */
    public static void backupSystem(final android.app.Activity act) {
        new AlertDialog.Builder(act)
                .setTitle("备份当前系统")
                .setMessage("会把当前整套 Ubuntu 系统打包保存到：\n\n"
                        + LinuxLauncher.BACKUP_DIR + "/\n\n"
                        + "包含你的全部个人数据（浏览器记录、登录、文档、输入法词库等），"
                        + "请妥善保管这个包。\n\n"
                        + "备份前会先关闭正在运行的桌面程序，备份可能需要几分钟。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始备份", (d, w) -> {
                    toast(act, "正在备份，请耐心等待…");
                    new Thread(() -> {
                        String r = LinuxLauncher.backupSystem(act);
                        toast(act, r);
                    }, "backup-system").start();
                })
                .show();
    }

    /** 从用户自己的备份包恢复系统（含其个人信息） */
    public static void restoreSystem(final android.app.Activity act) {
        final File[] pkgs = LinuxLauncher.findBackupPackages();
        if (pkgs == null || pkgs.length == 0) {
            new AlertDialog.Builder(act)
                    .setTitle("未找到备份包")
                    .setMessage("请把备份包（.tar.gz）放到以下目录后重试：\n\n"
                            + LinuxLauncher.BACKUP_DIR + "/\n"
                            + "/sdcard/Download/\n\n"
                            + "备份包文件名类似 ubuntu-backup-20261121-023000.tar.gz。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        // 不用 AlertDialog.setItems()：Termux 深色主题下该系统列表实测会被渲染成
        // 全空白（只剩标题/说明/「取消」，用户根本选不了包）。改成自建按钮列表，
        // 文字与背景色显式指定，任何主题下都可见、可点。
        final float dp = act.getResources().getDisplayMetrics().density;
        final int pad = (int) (16 * dp);
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        TextView tip = new TextView(act);
        tip.setText("恢复会先关闭桌面并清空当前系统，再用所选备份覆盖。\n"
                + "当前系统里未备份的改动会丢失。");
        tip.setTextColor(0xFFDDDDDD);
        tip.setTextSize(14f);
        tip.setPadding(0, 0, 0, pad);
        box.addView(tip);

        final ScrollView scroll = new ScrollView(act);
        scroll.addView(box);
        final AlertDialog chooser = new AlertDialog.Builder(act)
                .setTitle("选择要恢复的备份")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .create();

        final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        for (final File pkg : pkgs) {
            Button b = new Button(act);
            b.setAllCaps(false);
            b.setText(pkg.getName() + "\n"
                    + (pkg.length() / 1024 / 1024) + " MB · "
                    + fmt.format(new java.util.Date(pkg.lastModified())));
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundColor(0xFF37474F);
            b.setTextSize(14f);
            b.setPadding(pad, pad / 2, pad, pad / 2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            box.addView(b, lp);
            b.setOnClickListener(v -> {
                chooser.dismiss();
                new AlertDialog.Builder(act)
                        .setTitle("确认恢复")
                        .setMessage("确定从\n" + pkg.getName() + "\n恢复吗？\n\n"
                                + "这会清空当前系统并覆盖，耗时数分钟，期间请勿关闭应用。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确认恢复", (d2, w2) -> {
                            toast(act, "正在恢复，请耐心等待…");
                            new Thread(() -> {
                                String r = LinuxLauncher.restoreSystem(act, pkg);
                                toast(act, r.startsWith("OK:") ? r.substring(3) : r);
                            }, "restore-system").start();
                        })
                        .show();
            });
        }
        chooser.show();
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
