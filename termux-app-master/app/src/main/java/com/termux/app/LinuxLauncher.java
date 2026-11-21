package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Custom launcher for the "Termux Linux" build.
 *
 * Responsibilities:
 *   1. Extract the bundled Ubuntu rootfs (app/src/main/assets/ubuntu-rootfs.tar.gz)
 *      into $FILES_DIR/ubuntu on first launch. Only done once (marker file).
 *   2. Detect root (su availability).
 *   3. Build the exec argv that drops the terminal session straight into Linux:
 *        - rooted:   su -c "chroot <rootfs> /bin/bash -l"
 *        - non-root: proot -r <rootfs> -0 -b /dev -b /proc -b /sys ... /bin/bash -l
 *
 * The X11 / desktop stack is intentionally NOT bundled here; the user installs it
 * inside the distro when needed.
 */
public final class LinuxLauncher {

    private static final String TAG = "LinuxLauncher";

    /** Rootfs is extracted under Termux $FILES_DIR (e.g. /data/data/com.termux/files/ubuntu). */
    public static final String ROOTFS_DIR_PATH =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/ubuntu";
    public static final String ROOTFS_MARKER = ROOTFS_DIR_PATH + "/.termux-linux-ready";
    // Named .bin (not .tar.gz) on purpose: AGP auto-decompresses *.gz assets into
    // plain tar, inflating the APK to ~890MB. A neutral extension keeps it gzipped.
    private static final String ASSET_ROOTFS = "ubuntu-rootfs.bin";
    private static final String COPIED_TAR = "ubuntu-rootfs.tar.gz";

    /**
     * 外置系统包搜索路径（按优先级）。
     * 系统包与 APK 分离后，改系统只需替换这个 tar.gz，不必重打 APK。
     */
    private static final String[] EXTERNAL_ROOTS = {
            "/sdcard/UbuntuXFCE/rootfs.tar.gz",
            "/sdcard/UbuntuXFCE/ubuntu-rootfs.tar.gz",
            "/sdcard/Download/rootfs.tar.gz",
            "/sdcard/Download/ubuntu-rootfs.tar.gz",
            "/sdcard/ubuntu-rootfs.tar.gz",
    };

    /** 系统包最小合理体积（200MB），避免误把半截文件当系统包 */
    private static final long MIN_ROOTFS_BYTES = 200L * 1024 * 1024;

    /**
     * 会话归属文件：记录「哪个 App 进程启动了当前这套 Linux 桌面」。
     *
     * 为什么需要它：X server / pulseaudio / xfce4-session 都是通过 su 拉起的，
     * 内核会把它们 reparent 到 init（PPID=1），从此与 App 进程彻底脱钩，
     * 杀掉 App 它们照跑。Android 没有可靠的“子进程随父进程死”机制可用，
     * 所以改为在下一次 App 进程启动时，把上一次遗留的会话整个清掉
     * —— 对用户来说等价于「杀掉 App，系统也就停了」。
     */
    public static final String SESSION_FILE =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/.linux-session";

    /** 看门狗 pid 文件：用于在不误杀自身的前提下精确停止看门狗 */
    public static final String WATCHDOG_PID_FILE =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/.linux-watchdog.pid";
    public static final String WATCHDOG_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/linux-watchdog.sh";

    private static final String PREF_FILE = "linux_launcher_prefs";
    private static final String PREF_CLOSE_WITH_APP = "close_with_app";

    /** 清理脚本：独立进程执行，避免 pkill -f 把 su -c 的 shell 自己匹配上 */
    private static final String CLEANUP_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/linux-cleanup.sh";

    /** 把 Android 存储挂进 chroot 的脚本（菜单「挂载 SD」用） */
    public static final String MOUNT_SD_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/mount-sd.sh";

    /** 非 root（proot）方案的一组脚本 */
    public static final String NOROOT_START_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/start-xfce-noroot.sh";
    public static final String NOROOT_DESKTOP_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/start-desktop-noroot.sh";
    public static final String NOROOT_CLEANUP_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/linux-cleanup-noroot.sh";
    /** 标记文件：存在时强制走非 root（proot）路径。默认模式，可由菜单切换 */
    public static final String FORCE_NOROOT_FLAG =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/.force-noroot";
    /** 模式已初始化标记：确保「默认非 root」只在首次安装设置一次，不覆盖用户后续切换 */
    private static final String MODE_INITIALIZED_FLAG =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/.mode-initialized";

    private LinuxLauncher() {}

    /** 首次启动默认非 root：建 .force-noroot 标记（只做一次，尊重用户后续切换） */
    public static void ensureDefaultMode() {
        try {
            File init = new File(MODE_INITIALIZED_FLAG);
            if (init.exists()) return;
            File flag = new File(FORCE_NOROOT_FLAG);
            // 必须在标记确实存在后才写「已初始化」，否则一旦创建失败
            // 就再也不会重试，卡在「已初始化但非默认模式」的不一致状态。
            boolean ok = flag.exists() || flag.createNewFile();
            if (!ok && !flag.exists()) {
                Log.w(TAG, "ensureDefaultMode: cannot create force-noroot flag");
                return;
            }
            init.createNewFile();
            Log.i(TAG, "default mode = non-root (proot)");
        } catch (Exception e) {
            Log.w(TAG, "ensureDefaultMode failed: " + e.getMessage());
        }
    }

    /**
     * 设备是否真的有可用 root（不看 .force-noroot 标记，直接实测 su）。
     * 供菜单切换模式时判断：没 root 就提示无法切到 root 模式。
     */
    /** 实测确认可用的 su 路径（避免检测用一个、执行用另一个） */
    private static volatile String sWorkingSuPath;

    /** su 候选路径：KernelSU / Magisk / 系统合并位置都覆盖 */
    private static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/adb/ksu/bin/su", "/data/magisk/su", "/data/adb/magisk/su"};

    /**
     * 设备是否真的有可用 root（不看 .force-noroot 标记，直接实测）。
     * 遍历所有 su 候选路径，任一实测 `su -c id` 返回 uid=0 即算可用；
     * 只有管理器 APK 或残留 su 文件都不算（装了 KernelSU≠su 可用）。
     * 供菜单切换模式、备份/恢复选执行身份时判断。
     */
    public static boolean isSuAvailable() {
        // 先试已确认可用的路径，避免每次重新探测全部路径
        String cached = sWorkingSuPath;
        if (cached != null && verifySuWorks(cached)) return true;
        for (String p : SU_PATHS) {
            if (!new File(p).exists()) continue;
            if (verifySuWorks(p)) {
                sWorkingSuPath = p;
                return true;
            }
        }
        // PATH 里也可能有 su（Magisk 合并进 /system/bin）
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "command -v su"});
            String out = readAll(p.getInputStream());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            String fromPath = out.trim().split("\n")[0].trim();
            if (!fromPath.isEmpty() && verifySuWorks(fromPath)) {
                sWorkingSuPath = fromPath;
                return true;
            }
        } catch (Exception ignored) {}
        sWorkingSuPath = null;
        return false;
    }

    /** 当前是否为非 root 模式（读标记） */
    public static boolean isNonRootMode() {
        return new File(FORCE_NOROOT_FLAG).exists();
    }

    /** 切换运行模式：返回切换后的模式描述（"非 root（proot）" / "root（chroot）"） */
    public static String toggleMode() {
        File flag = new File(FORCE_NOROOT_FLAG);
        if (flag.exists()) {
            if (!deleteQuiet(flag)) return "切换失败：无法删除标记";
            // 不删 MODE_INITIALIZED_FLAG：用户切换后重启要保持不变
            return "root（chroot）";
        } else {
            try { flag.createNewFile(); } catch (Exception e) { return "切换失败：" + e.getMessage(); }
            return "非 root（proot）";
        }
    }

    private static boolean deleteQuiet(File f) {
        try { return f.delete(); } catch (Exception e) { return false; }
    }

    /**
     * 「系统跟随 App 关闭」开关。
     *
     * 开（默认）：App 进程一消失，看门狗就关掉 X server / 桌面 / 音频。
     * 关：Linux 完全常驻后台，杀 App 不影响它，下次打开 App 直接接管。
     */
    public static boolean isCloseWithApp(Context ctx) {
        return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREF_CLOSE_WITH_APP, true);
    }

    public static void setCloseWithApp(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_CLOSE_WITH_APP, on).apply();
    }

    // ---------------------------------------------------------------------
    // 桌面显示缩放
    //
    // 板子物理分辨率 2880x1800（density 340），但 X server 默认按 96dpi、
    // 2880x1800 的逻辑尺寸渲染，等于所有界面元素只有 Android 原生的一半大，
    // 字小得看不清。termux-x11 自带 "scaled" 模式：它把 X 逻辑分辨率设为
    // 物理尺寸 / scale，客户端按小分辨率渲染，再放大铺满屏幕。
    // 200% 时逻辑分辨率正好减半，观感与 Chrome 等 Android 应用接近。
    //
    // displayResolutionMode / displayScale 都是 termux-x11 自己读的键，
    // 改完 LorieApp 的监听会立刻触发重算，不需要重启桌面。
    // ---------------------------------------------------------------------

    private static final String PREF_DISPLAY_SCALE_APPLIED = "display_scale_applied_v1";
    private static final String X11_KEY_SCALE = "displayScale";
    private static final String X11_KEY_MODE = "displayResolutionMode";

    /** 可选的缩放档位（百分比） */
    public static final int[] DISPLAY_SCALES = {100, 125, 150, 175, 200};

    public static int getDisplayScale(Context ctx) {
        int s = PreferenceManager.getDefaultSharedPreferences(ctx).getInt(X11_KEY_SCALE, 100);
        if (s < 100) s = 100;
        if (s > 200) s = 200;
        return s;
    }

    public static void setDisplayScale(Context ctx, int scale) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(X11_KEY_MODE, "scaled")
                .putInt(X11_KEY_SCALE, scale)
                .putBoolean(PREF_DISPLAY_SCALE_APPLIED, true)
                .apply();
    }

    /** 按档位表取下一個缩放值（到顶后回到 100） */
    public static int nextDisplayScale(int current) {
        for (int i = 0; i < DISPLAY_SCALES.length; i++) {
            if (DISPLAY_SCALES[i] == current)
                return DISPLAY_SCALES[(i + 1) % DISPLAY_SCALES.length];
        }
        return 200;
    }

    /**
     * 首次运行把桌面放大到 200%。
     *
     * 只在用户没动过 termux-x11 的分辨率设置（仍是 native）时应用一次，
     * 之后用户手动调过的值不会被覆盖。
     */
    public static void ensureDisplayScale(Context ctx) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        if (p.getBoolean(PREF_DISPLAY_SCALE_APPLIED, false)) return;
        String mode = p.getString(X11_KEY_MODE, "native");
        if (!"native".equals(mode)) {
            // 已经是 scaled / exact / custom，尊重现有选择，只做标记
            p.edit().putBoolean(PREF_DISPLAY_SCALE_APPLIED, true).apply();
            return;
        }
        setDisplayScale(ctx, 200);
    }

    /**
     * 按当前开关状态增删看门狗。切换开关后调用即可立即生效，
     * 不需要重启桌面。
     */
    public static void syncWatchdog(final Context ctx) {
        final boolean follow = isCloseWithApp(ctx);
        final int appPid = android.os.Process.myPid();
        new Thread(() -> {
            // 先停旧的（按 pid 文件精确停，避免 pkill -f 匹配到自己）
            String stop = "[ -f " + WATCHDOG_PID_FILE + " ] && kill -9 $(cat " + WATCHDOG_PID_FILE
                    + ") 2>/dev/null; rm -f " + WATCHDOG_PID_FILE + "; ";
            String start = follow
                    ? "if pgrep -f '[t]ermux-x11' >/dev/null 2>&1; then "
                        + "setsid sh " + WATCHDOG_SCRIPT + " " + appPid + " >/dev/null 2>&1 & "
                        + "fi"
                    : "echo disabled";
            try {
                Process p = Runtime.getRuntime().exec(new String[]{suBinary(), "-c", stop + start});
                p.waitFor(20, TimeUnit.SECONDS);
                Log.i(TAG, "syncWatchdog follow=" + follow);
            } catch (Exception e) {
                Log.w(TAG, "syncWatchdog failed: " + e.getMessage());
            }
        }, "sync-watchdog").start();
    }

    /**
     * App 进程启动时调用一次（Application.onCreate）。
     *
     * 如果上一次的 App 进程已经不在了、但它拉起的桌面进程还活着，
     * 就在后台把它们全部终止，避免「杀 App 后 Linux 一直在后台跑」。
     */
    public static void onAppProcessStart(Context ctx) {
        final int myPid = android.os.Process.myPid();
        int oldPid = -1;
        try {
            File f = new File(SESSION_FILE);
            if (f.exists()) {
                String s = readFileQuietly(f).trim();
                if (s.contains("\n")) s = s.substring(0, s.indexOf('\n'));
                oldPid = Integer.parseInt(s.trim());
            }
        } catch (Exception ignored) {
        }

        // 立刻把归属改成当前进程，避免下次判断出错
        try {
            writeFileQuietly(new File(SESSION_FILE), String.valueOf(myPid));
        } catch (Exception e) {
            Log.w(TAG, "write session file failed: " + e.getMessage());
        }

        final int stale = oldPid;
        if (stale > 0 && stale != myPid && new File("/proc/" + stale).exists()) {
            if (!isCloseWithApp(ctx)) {
                // 开关关闭：Linux 常驻，不清理上一次的会话，直接接管
                Log.i(TAG, "stale session owned by pid " + stale + " kept alive (follow=false)");
            } else {
                Log.i(TAG, "stale linux session owned by pid " + stale + ", cleaning up");
                new Thread(() -> {
                    boolean ok = killSession(ctx);
                    Log.i(TAG, "stale session cleanup " + (ok ? "ok" : "failed"));
                }, "linux-session-cleanup").start();
            }
        }
    }

    /**
     * 终止整套 Linux 会话：X server → 桌面 → 音频 → 卸载 chroot 挂载。
     * @return 命令是否成功返回（不代表每个进程都一定杀掉了）
     */
    public static boolean killSession(Context ctx) {
        // 非 root：所有进程都是 App 自己的 uid，直接跑非 root 清理脚本
        boolean rooted = hasRoot(ctx);
        try {
            if (rooted) {
                copyAssetIfNeeded(ctx, "linux-cleanup.sh", CLEANUP_SCRIPT);
            } else {
                copyAssetIfNeeded(ctx, "linux-cleanup-noroot.sh", NOROOT_CLEANUP_SCRIPT);
            }
        } catch (Exception e) {
            Log.w(TAG, "copy cleanup script failed: " + e.getMessage());
        }
        String cmd;
        if (rooted) {
            cmd =
                    // 用户主动关闭，清掉看门狗的关闭标记，否则下次 start-xfce.sh 会拒绝启动
                    "rm -f " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/.session-closing; " +
                    // 先停看门狗，否则它会在桌面退出后继续空转
                    "[ -f " + WATCHDOG_PID_FILE + " ] && kill -9 $(cat " + WATCHDOG_PID_FILE
                            + ") 2>/dev/null; rm -f " + WATCHDOG_PID_FILE + "; " +
                    "sh " + CLEANUP_SCRIPT;
        } else {
            cmd =
                    "rm -f " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/.session-closing; " +
                    "sh " + NOROOT_CLEANUP_SCRIPT;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    rooted ? suBinary() : "/system/bin/sh",
                    rooted ? "-c" : "-c", cmd});
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            Log.i(TAG, "killSession finished=" + done + " exit=" + p.exitValue());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "killSession failed", e);
            return false;
        }
    }

    /**
     * 是否已拿到「访问全部文件」的权限。
     *
     * Linux 侧（root 的 chroot 或 proot）与 com.termux 共用同一个内核 uid，
     * Android 存储按 uid 判权限，所以没有它，桌面里就打不开挂载的下载目录 ——
     * 目录在、点进去是空的或者 Permission denied。
     */
    public static boolean hasStorageAccess(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                return android.os.Environment.isExternalStorageManager();
            } catch (Exception e) {
                return false;
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 跳到系统的「所有文件访问」授权页；个别 ROM 没有该页时退回应用详情页 */
    public static void openStorageSettings(Context ctx) {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk >= 30) {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {
                // 部分定制 ROM 裁掉了应用级入口，落到下面的全局入口
            }
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    sdk >= 30 ? android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                              : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openStorageSettings failed: " + e.getMessage());
        }
    }

    /**
     * 把 Android 存储挂进 chroot（必须在子线程调用）。
     * @return 可直接展示给用户的中文结果
     */
    public static String mountStorage(Context ctx) {
        // 非 root（proot）：不能 mount --bind，但 App 拿到 Android「所有文件访问」权限后，
        // proot 启动脚本 -b /sdcard/Download:/mnt/download 直接映射真实下载目录。
        // 这里做一次真实可读写校验，避免「已授权但目录还是空/只读」的假成功。
        if (!hasRoot(ctx)) {
            if (!hasStorageAccess(ctx)) {
                return "系统仍拒绝访问存储，请确认「所有文件访问权限」已打开";
            }
            File dl = new File("/sdcard/Download");
            boolean ok = dl.isDirectory() && dl.canRead() && dl.canWrite();
            writeStatus("non-root download access=" + ok);
            return ok
                    ? "已授权：下载目录可读写，桌面里位于 /mnt/download"
                    : "已授权，但 /sdcard/Download 暂不可写，请确认系统授权已生效";
        }
        try {
            copyAssetIfNeeded(ctx, "mount-sd.sh", MOUNT_SD_SCRIPT);
        } catch (Exception e) {
            return "挂载脚本写入失败：" + e.getMessage();
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{suBinary(), "-c", "sh " + MOUNT_SD_SCRIPT});
            String out = readAll(p.getInputStream());
            boolean done = p.waitFor(90, TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return "挂载超时，请稍后再试";
            }
            Log.i(TAG, "mountStorage out=" + out);
            writeStatus("mountStorage " + out.trim());
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RESULT OK")) {
                    String ext = fieldOf(line, "ext");
                    return (ext != null && !"none".equals(ext))
                            ? "已挂载：/mnt/sdcard（含外置卡 /mnt/external_sd）"
                            : "已挂载：/mnt/sdcard";
                }
                if (line.startsWith("RESULT NOPERM")) {
                    return "系统仍拒绝访问存储，请确认「所有文件访问权限」已打开";
                }
                if (line.startsWith("RESULT ERR")) {
                    String why = line.substring("RESULT ERR".length()).trim();
                    if ("no-rootfs".equals(why)) return "系统还没导入，请先进入一次桌面";
                    return "挂载失败：" + why;
                }
            }
            return "挂载失败（无返回结果，详见 files/log/mount-sd.log）";
        } catch (Exception e) {
            Log.e(TAG, "mountStorage failed", e);
            return "挂载失败：" + e.getMessage();
        }
    }

    /** 从 "key=value" 形式的一行里取出 value */
    private static String fieldOf(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return null;
        int start = i + key.length() + 1;
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static String readFileQuietly(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeFileQuietly(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    /** 找一个可用的外置系统包；没有则返回 null */
    public static File findExternalRootfs() {
        for (String p : EXTERNAL_ROOTS) {
            File f = new File(p);
            try {
                if (f.isFile() && f.length() > MIN_ROOTFS_BYTES) return f;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static final String BACKUP_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/backup-rootfs.sh";
    public static final String RESTORE_SCRIPT =
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/restore-rootfs.sh";
    public static final String BACKUP_DIR = "/sdcard/UbuntuXFCE/backups";

    /**
     * 备份当前系统（含用户个人信息）到 /sdcard/UbuntuXFCE/backups。
     * 必须在子线程调用；root/非 root 都以能读写 rootfs 的身份执行。
     * 结果以 RESULT OK/ERR 开头返回，供菜单 toast 展示。
     */
    public static String backupSystem(Context ctx) {
        if (!isRootfsReady()) return "系统未安装，无需备份";
        try {
            copyAssetIfNeeded(ctx, "backup-rootfs.sh", BACKUP_SCRIPT);
        } catch (Exception e) {
            return "备份脚本写入失败：" + e.getMessage();
        }
        // 执行身份看「设备能否真正拿到 root」，不看当前模式标记：
        // 用户从 root 切到非 root 模式后，rootfs 里 /usr /root 等仍是 root:root 700，
        // 以 App uid 备份会大量 EACCES 跳过文件，生成残缺包却误报成功。
        // 只要设备有可用 su，就一律用 root 打包，保证完整。
        boolean rooted = isSuAvailable();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    rooted ? suBinary() : "/system/bin/sh", "-c", "sh " + BACKUP_SCRIPT});
            String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
            boolean done = p.waitFor(900, TimeUnit.SECONDS);
            if (!done) { p.destroy(); return "备份超时（超过 15 分钟），请查看 files/log/backup.log"; }
            writeStatus("backup " + out.trim());
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RESULT OK")) return line.substring("RESULT OK".length()).trim();
                if (line.startsWith("RESULT ERR")) return "备份失败：" + line.substring("RESULT ERR".length()).trim();
            }
            return "备份失败（无返回结果，详见 files/log/backup.log）";
        } catch (Exception e) {
            Log.e(TAG, "backup failed", e);
            return "备份失败：" + e.getMessage();
        }
    }

    /** 列出可恢复的备份包：备份目录优先，其次 Download 里的 ubuntu-backup-*.tar.gz */
    public static File[] findBackupPackages() {
        java.util.List<File> list = new java.util.ArrayList<>();
        String[] dirs = {BACKUP_DIR, "/sdcard/Download"};
        for (String d : dirs) {
            File dir = new File(d);
            File[] fs = dir.listFiles((dir1, name) ->
                    (name.startsWith("ubuntu-backup-") || name.startsWith("rootfs-"))
                            && name.endsWith(".tar.gz"));
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                java.util.Collections.addAll(list, fs);
            }
        }
        return list.toArray(new File[0]);
    }

    /** 从指定备份包恢复系统（含用户个人信息）。必须在子线程调用。 */
    /** shell 单引号转义：把任意路径安全地拼进 sh -c 命令，防空格/单引号/注入 */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String restoreSystem(Context ctx, File pkg) {
        if (pkg == null || !pkg.isFile()) return "备份包不存在";
        try {
            copyAssetIfNeeded(ctx, "restore-rootfs.sh", RESTORE_SCRIPT);
            copyAssetIfNeeded(ctx, "fix_perms.sh",
                    TermuxConstants.TERMUX_FILES_DIR_PATH + "/fix_perms.sh");
        } catch (Exception e) {
            return "恢复脚本写入失败：" + e.getMessage();
        }
        // 用设备真实 root 能力恢复，rootfs 的属主/setuid 需要 root 才能还原。
        // 注意：不能用 `su -c '... "$1"' sh <路径>` —— Android 的 su 不会把后续
        // 位置参数传给 -c 的命令，脚本会拿到空路径直接报「备份包不存在」。
        // 改成把路径做单引号转义后拼进命令字符串：既能正确传参，也防文件名
        // 含空格/单引号时被拆开或注入。
        boolean rooted = isSuAvailable();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    rooted ? suBinary() : "/system/bin/sh",
                    "-c", "sh " + RESTORE_SCRIPT + " " + shellQuote(pkg.getAbsolutePath()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
            boolean done = p.waitFor(900, TimeUnit.SECONDS);
            if (!done) { p.destroy(); return "恢复超时（超过 15 分钟），请查看 files/log/restore.log"; }
            writeStatus("restore " + out.trim());
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RESULT OK")) return "OK:" + line.substring("RESULT OK".length()).trim();
                if (line.startsWith("RESULT ERR")) return "恢复失败：" + line.substring("RESULT ERR".length()).trim();
            }
            return "恢复失败（无返回结果，详见 files/log/restore.log）";
        } catch (Exception e) {
            Log.e(TAG, "restore failed", e);
            return "恢复失败：" + e.getMessage();
        }
    }

    /** 系统是否已就绪：探测 rootfs 里必然存在的文件，而不是依赖自己写的标记 */
    public static boolean isRootfsReady() {
        return new File(ROOTFS_DIR_PATH, "usr/bin/bash").exists()
                || new File(ROOTFS_DIR_PATH, "etc/os-release").exists()
                || new File(ROOTFS_MARKER).exists();
    }

    /**
     * 清空已解压的系统（重新导入前调用）。
     *
     * 安全要点：rootfs 里可能正 bind 着 /dev、/proc、/sys、/mnt/sdcard。
     * 若直接 rm -rf，删除操作会穿透 bind mount 一路删进 Android 的 /dev，
     * 造成系统级灾难。所以必须先杀桌面进程、卸载全部挂载，确认没残留才删。
     */
    public static boolean resetRootfs(Context ctx) {
        final String r = ROOTFS_DIR_PATH;
        boolean rooted = hasRoot(ctx);
        try {
            if (rooted) {
                copyAssetIfNeeded(ctx, "linux-cleanup.sh", CLEANUP_SCRIPT);
            } else {
                copyAssetIfNeeded(ctx, "linux-cleanup-noroot.sh", NOROOT_CLEANUP_SCRIPT);
            }
        } catch (Exception ignored) {
        }
        String cleanupPart = rooted
                ? "sh " + CLEANUP_SCRIPT + "; "
                : "sh " + NOROOT_CLEANUP_SCRIPT + "; ";
        String cmd =
                "rm -f " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/.session-closing; " +
                cleanupPart +
                // 护栏：只要还有挂载就绝不 rm -rf
                // （rm 会穿透 bind mount 删掉 Android 的 /dev 和存储卡，已经造成过一次事故）
                "if grep -qs '/files/ubuntu/' /proc/mounts; then "
                        + "  echo ABORT_MOUNTS; grep '/files/ubuntu/' /proc/mounts; exit 9; "
                        + "fi; "
                + "rm -rf " + r + " && mkdir -p " + r + " && echo OK";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    rooted ? suBinary() : "/system/bin/sh", "-c", cmd});
            String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
            boolean finished = p.waitFor(600, TimeUnit.SECONDS);
            Log.i(TAG, "resetRootfs out=" + out);
            writeStatus("resetRootfs " + out.trim());
            return finished && p.exitValue() == 0 && out.contains("OK");
        } catch (Exception e) {
            Log.e(TAG, "resetRootfs failed", e);
            return false;
        }
    }

    /**
     * 重新导入系统包：停桌面 -> 卸载挂载 -> 清空 -> 解压 -> 修权限 -> 写就绪标记。
     * 耗时数分钟，必须在子线程调用。
     *
     * @return 结果描述（成功时以 "OK:" 开头）
     */
    public static String reimportRootfs(Context ctx) {
        File ext = findExternalRootfs();
        if (ext == null) return "未找到系统包";
        writeStatus("reimport from " + ext.getAbsolutePath());
        if (!resetRootfs(ctx)) return "清理旧系统失败（可能有挂载未卸载，详见状态日志）";
        try {
            extractRootfs(ctx, true);
            new File(ROOTFS_DIR_PATH).mkdirs();
            try (FileOutputStream fos = new FileOutputStream(ROOTFS_MARKER)) {
                fos.write("ok".getBytes());
            }
            writeStatus("reimport done");
            return "OK:已从 " + ext.getName() + " 导入完成";
        } catch (Exception e) {
            Log.e(TAG, "reimport failed", e);
            writeStatus("reimport failed: " + e.getMessage());
            return "导入失败：" + e.getMessage();
        }
    }

    /** Result of preparing + deciding how to launch Linux. */
    public static final class LaunchPlan {
        public final boolean rooted;
        public final String executable;
        public final String[] arguments;
        public final String message;
        LaunchPlan(boolean rooted, String executable, String[] arguments, String message) {
            this.rooted = rooted;
            this.executable = executable;
            this.arguments = arguments;
            this.message = message;
        }
    }

    /**
     * Detect root. IMPORTANT: we must NOT run `su -c id` here — on KernelSU/Magisk that
     * triggers an authorization popup which is denied/times out during app startup, so a
     * rooted device would be misdetected as non-rooted. Instead, presence of a su binary
     * (or KSU/Magisk install artifacts) is enough; the actual su call happens when the
     * chroot session starts, where the user can grant once.
     *
     * 2026-11-20 修订：管理器 APK 已安装 ≠ su 可用（内核不支持 / 未授权 / su 被隐藏
     * 都会让 Termux 的 su wrapper 报 "No su program found" 直接退出，会话 code 1）。
     * 所以「只命中管理器」时必须动态实测 su 才能判 root；静态路径命中（su 真实可见）
     * 仍然直接判 root，授权弹窗留给会话启动时。
     */
    public static boolean hasRoot(Context ctx) {
        // 调试/逃生口：标记文件存在时强制走非 root（proot）路径，
        // 方便在有 root 的机器上验证非 root 模式。
        try {
            if (new File(FORCE_NOROOT_FLAG).exists()) {
                Log.i(TAG, "force-noroot flag present, treating as non-root");
                return false;
            }
        } catch (Exception ignored) {}
        // 1) su binary visible to the app process —— 必须实测能用才算 root。
        //    以前只看文件存在，KernelSU 装了但 su 被删/没授权时仍判有 root，
        //    随后 chroot 命令报 "No su program found on this device..."。
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/data/adb/ksu/bin/su", "/data/magisk/su"};
        for (String p : suPaths) {
            try {
                if (new File(p).exists()) {
                    if (sSuManagerVerified == null) {
                        synchronized (SU_VERIFY_LOCK) {
                            if (sSuManagerVerified == null) {
                                boolean works = verifySuWorks(p);
                                sSuManagerVerified = works;
                                Log.i(TAG, "su binary " + p + " live check -> " + works);
                                writeStatus("su binary " + p + " works=" + works);
                            }
                        }
                    }
                    return sSuManagerVerified;
                }
            } catch (Exception ignored) {}
        }
        // 2) 只有管理器 APK 命中时不能直接判 root —— 必须实测 su 是否真的可用
        //    （实测会缓存到本进程生命周期，避免反复起进程）
        if (ctx != null) {
            String[] managers = {"me.weishu.kernelsu", "com.topjohnwu.magisk",
                    "io.github.vvb2060.magisk", "com.noshufou.android.su"};
            boolean managerSeen = false;
            for (String pkg : managers) {
                try {
                    ctx.getPackageManager().getPackageInfo(pkg, 0);
                    managerSeen = true;
                    break;
                } catch (Exception ignored) {}
            }
            if (managerSeen) {
                if (sSuManagerVerified != null) {
                    Log.i(TAG, "Root manager present, cached su-works=" + sSuManagerVerified);
                    return sSuManagerVerified;
                }
                synchronized (SU_VERIFY_LOCK) {
                    if (sSuManagerVerified == null) {
                        boolean works = verifySuWorks();
                        sSuManagerVerified = works;
                        writeStatus("manager installed, su -c id works=" + works);
                        Log.i(TAG, "Root manager present, live su check -> " + works);
                    }
                    return sSuManagerVerified;
                }
            }
        }
        return false;
    }

    /** 管理器已装但 su 可见性未知时的动态验证（带超时；进程生命周期内缓存）。 */
    private static final Object SU_VERIFY_LOCK = new Object();
    private static volatile Boolean sSuManagerVerified;

    private static boolean verifySuWorks() {
        return verifySuWorks("su");
    }

    /**
     * 用指定路径实测 su。
     * 关键顺序：先 waitFor(超时) 再读流。若 su 弹出授权框挂起等待用户操作，
     * 子进程存活、stdout 不关闭，先 readLine() 会永久阻塞（曾经卡死 UI 的 bug），
     * 导致 5 秒超时形同虚设。这里先等进程结束，超时就强杀，之后再读残留输出。
     */
    private static boolean verifySuWorks(String suBin) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{suBin, "-c", "id"});
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                Log.w(TAG, "su -c id timed out (授权弹窗未处理?), treating as non-root: " + suBin);
                return false;
            }
            String out = readAll(p.getInputStream());
            boolean ok = p.exitValue() == 0 && out.contains("uid=0");
            Log.i(TAG, "su -c id exit=" + p.exitValue() + " path=" + suBin + " out=" + out.trim());
            return ok;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            Log.i(TAG, "su check failed -> non-root: " + e.getMessage());
            return false;
        }
    }

    /** 找一个真实存在的 su 二进制；KernelSU/Magisk 的路径各不相同，不要硬编码。 */
    public static String suBinary() {
        // 优先用实测确认可用的路径，避免「检测用一个、执行用另一个」
        String working = sWorkingSuPath;
        if (working != null) return working;
        for (String p : SU_PATHS) {
            try {
                if (new File(p).exists()) return p;
            } catch (Exception ignored) {}
        }
        return "su";
    }

    /**
     * Prepare the rootfs and build the launch plan.
     * Must be called OFF the main thread (does file IO + tar extract).
     */
    public static LaunchPlan prepare(Context ctx) {
        boolean rooted = hasRoot(ctx);
        writeStatus("prepare called, rooted=" + rooted);
        File rootfs = new File(ROOTFS_DIR_PATH);

        // --- Step 1: extract rootfs if not done yet ---
        // 就绪判断不能依赖“自己写的标记文件”：tar -p 以 root 解压会把顶层目录
        // 改成 root:root，App 进程没权限往里写标记（EACCES），一旦标记写失败
        // 整个 prepare 就中断，后面的脚本复制全部被跳过。
        // 所以改成检查 rootfs 里必有的文件，标记文件只作 best-effort。
        if (!isRootfsReady()) {
            try {
                extractRootfs(ctx, rooted);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract rootfs", e);
                return new LaunchPlan(rooted, null, null,
                        "rootfs 解压失败: " + e.getMessage());
            }
            try (FileOutputStream fos = new FileOutputStream(ROOTFS_MARKER)) {
                fos.write("ok".getBytes());
            } catch (Exception ignored) {
                Log.w(TAG, "marker write skipped (permission), readiness uses file probe");
            }
        }

        // --- Step 2: decide executable + args ---
        if (rooted) {
            // 复制桌面启动脚本（Android 侧：起 X server / 显示 Activity / 音频 / chroot 起 XFCE）
            String deskScript = TermuxConstants.TERMUX_FILES_DIR_PATH + "/start-xfce.sh";
            try { copyAssetIfNeeded(ctx, "start-xfce.sh", deskScript); } catch (Exception e) {
                Log.e(TAG, "copy start-xfce.sh failed", e);
            }
            // chroot 内的桌面脚本也随 APK 走：这样改脚本不用重打 1.4G 的 rootfs
            String innerScript = TermuxConstants.TERMUX_FILES_DIR_PATH + "/start-desktop.sh";
            try { copyAssetIfNeeded(ctx, "start-desktop.sh", innerScript); } catch (Exception e) {
                Log.e(TAG, "copy start-desktop.sh failed", e);
            }
            // Termux 侧依赖（pulseaudio / termux-api）：国内源不可用，随包分发
            // 注意 asset 名必须用 .bin：aapt 会把 .tar.gz 自动解压并改名，导致 open() 失败
            String pkgsTar = TermuxConstants.TERMUX_FILES_DIR_PATH + "/termux-pkgs.tar.gz";
            try { copyAssetIfNeeded(ctx, "termux-pkgs.bin", pkgsTar); } catch (Exception e) {
                Log.e(TAG, "copy termux-pkgs failed", e);
            }
            // 音频启动脚本 + PulseAudio 配置必须随 APK 走：卸载重装会清空 /data/data/com.termux，
            // 以前这两个文件是手工推进去的，重装后就没了 -> 无声
            String audioScript = TermuxConstants.TERMUX_HOME_DIR_PATH + "/start-audio.sh";
            try { copyAssetIfNeeded(ctx, "start-audio.sh", audioScript); } catch (Exception e) {
                Log.e(TAG, "copy start-audio.sh failed", e);
            }
            String paConf = TermuxConstants.TERMUX_HOME_DIR_PATH + "/pa-user.pa";
            try { copyAssetIfNeeded(ctx, "pa-user.pa", paConf); } catch (Exception e) {
                Log.e(TAG, "copy pa-user.pa failed", e);
            }
            // 生命周期看门狗
            try { copyAssetIfNeeded(ctx, "linux-watchdog.sh", WATCHDOG_SCRIPT); } catch (Exception e) {
                Log.e(TAG, "copy linux-watchdog.sh failed", e);
            }
            // 杀进程 + 卸载挂载的清理脚本（killSession / 看门狗 / 重新导入共用）
            try { copyAssetIfNeeded(ctx, "linux-cleanup.sh", CLEANUP_SCRIPT); } catch (Exception e) {
                Log.e(TAG, "copy linux-cleanup.sh failed", e);
            }
            // 菜单里的「挂载 SD」
            try { copyAssetIfNeeded(ctx, "mount-sd.sh", MOUNT_SD_SCRIPT); } catch (Exception e) {
                Log.e(TAG, "copy mount-sd.sh failed", e);
            }
            // 下载交换区搬运器（暂存区 <-> 真实 Download 双向同步，零穿透）
            try { copyAssetIfNeeded(ctx, "download-sync.sh",
                    TermuxConstants.TERMUX_FILES_DIR_PATH + "/download-sync.sh"); } catch (Exception e) {
                Log.e(TAG, "copy download-sync.sh failed", e);
            }
            // chroot needs /dev /proc /sys mounted inside rootfs.
            String mountCmd =
                    // 清掉上次看门狗留下的「会话已关闭」标记，否则 start-xfce.sh 会直接退出
                    "rm -f " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/.session-closing; " +
                    // 先清掉上一会话残留的看门狗：它盯着已死的旧 App pid，
                    // 新桌面一起来就会被它误杀。
                    // 注意不能用 pkill -f '[l]inux-watchdog'：本命令行全文里含
                    // "linux-watchdog.sh"（下面 setsid 段），pkill 会匹配到父 shell
                    // 把自己 SIGTERM，整个会话直接 [Process completed (signal 15)]。
                    // 按看门狗自己写的 pid 文件精确杀（与 killSession 同款）
                    "[ -f " + WATCHDOG_PID_FILE + " ] && kill -9 $(cat " + WATCHDOG_PID_FILE
                            + ") 2>/dev/null; rm -f " + WATCHDOG_PID_FILE + "; " +
                    // 后台拉起图形桌面（脚本自身幂等：已在运行会直接跳过）
                    // log 目录可能被 root 会话写成 root 属主，切到非 root 后
                    // App uid 写不了日志会直接 Permission denied，这里放开
                    "mkdir -p " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/log; " +
                    "chmod 777 " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/log; " +
                    "sh " + deskScript + " >" + TermuxConstants.TERMUX_FILES_DIR_PATH
                            + "/log/desktop-start.log 2>&1 & " +
                    // 「系统跟随 App」看门狗：App 进程一消失就关掉整套桌面栈。
                    // 必须由 root 独立进程跑：su 拉起的进程 PPID 会变成 1，
                    // App 进程内没有任何回调能感知自己被杀。
                    (isCloseWithApp(ctx)
                            ? "setsid sh " + WATCHDOG_SCRIPT + " " + android.os.Process.myPid()
                              + " >/dev/null 2>&1 & "
                            : "") +
                    // chroot 会话挂载（与 start-xfce.sh 同一套零穿透方案）：
                    // /dev 用独立 tmpfs + mknod（bind 真实 /dev 曾在卸载/清数据时被穿透清空）；
                    // /mnt/download 挂交换暂存区（由 download-sync.sh 与真实 Download 同步），
                    // 真实 /sdcard 不再被 bind，卸载/清数据穿透也删不到用户数据。
                    "mount -t proc proc " + ROOTFS_DIR_PATH + "/proc 2>/dev/null; " +
                    "mount -t sysfs sysfs " + ROOTFS_DIR_PATH + "/sys 2>/dev/null; " +
                    "if ! grep -qs ' " + ROOTFS_DIR_PATH + "/dev ' /proc/mounts; then " +
                    "if mount -t tmpfs -o mode=755 tmpfs " + ROOTFS_DIR_PATH + "/dev 2>/dev/null; then " +
                    "mknod -m 666 " + ROOTFS_DIR_PATH + "/dev/null c 1 3 2>/dev/null; " +
                    "mknod -m 666 " + ROOTFS_DIR_PATH + "/dev/zero c 1 5 2>/dev/null; " +
                    "mknod -m 666 " + ROOTFS_DIR_PATH + "/dev/random c 1 8 2>/dev/null; " +
                    "mknod -m 666 " + ROOTFS_DIR_PATH + "/dev/urandom c 1 9 2>/dev/null; " +
                    "ln -sf /proc/self/fd " + ROOTFS_DIR_PATH + "/dev/fd 2>/dev/null; " +
                    "ln -sf pts/ptmx " + ROOTFS_DIR_PATH + "/dev/ptmx 2>/dev/null; fi; fi; " +
                    "[ -c " + ROOTFS_DIR_PATH + "/dev/null ] || mount -o bind /dev "
                            + ROOTFS_DIR_PATH + "/dev 2>/dev/null; " +
                    "grep -qs ' " + ROOTFS_DIR_PATH + "/dev/pts ' /proc/mounts || "
                            + "mount -t devpts -o mode=600,ptmxmode=666 devpts "
                            + ROOTFS_DIR_PATH + "/dev/pts 2>/dev/null; " +
                    "mkdir -p " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/download-xfer/out "
                            + TermuxConstants.TERMUX_FILES_DIR_PATH + "/download-xfer/in "
                            + ROOTFS_DIR_PATH + "/mnt/download; " +
                    "chmod -R 777 " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/download-xfer 2>/dev/null; " +
                    "APP_UID=$(stat -c %u " + TermuxConstants.TERMUX_FILES_DIR_PATH + " 2>/dev/null); "
                            + "[ -n \"$APP_UID\" ] || APP_UID=10183; "
                            + "chown -R \"$APP_UID:$APP_UID\" " + TermuxConstants.TERMUX_FILES_DIR_PATH
                            + "/download-xfer 2>/dev/null; " +
                    "grep -qs ' " + ROOTFS_DIR_PATH + "/mnt/download ' /proc/mounts || "
                            + "mount -o bind " + TermuxConstants.TERMUX_FILES_DIR_PATH
                            + "/download-xfer " + ROOTFS_DIR_PATH + "/mnt/download 2>/dev/null; " +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "export HOME=/root; export TERM=xterm-256color; " +
                    "chroot " + ROOTFS_DIR_PATH + " /bin/bash -l";
            writeStatus("plan=chroot (rooted) + desktop autostart");
            return new LaunchPlan(true, suBinary(),
                    new String[]{"-c", mountCmd}, "root (chroot)");
        } else {
            // Non-root: proot 方案。X server / 音频 / 桌面全部以 App 自己的 uid 跑，
            // 桌面在 proot 内以"伪装 root"运行。所有依赖随 APK 分发（proot/pulseaudio
            // 在 termux-pkgs.tar.gz 里，start-xfce-noroot.sh 首次运行时自动补齐）。
            String deskScript = NOROOT_START_SCRIPT;
            try { copyAssetIfNeeded(ctx, "start-xfce-noroot.sh", deskScript); } catch (Exception e) {
                Log.e(TAG, "copy start-xfce-noroot.sh failed", e);
            }
            // proot 内的桌面脚本：放 files 目录，由外层脚本 bind 进 proot 的 /root
            try { copyAssetIfNeeded(ctx, "start-desktop-noroot.sh", NOROOT_DESKTOP_SCRIPT); } catch (Exception e) {
                Log.e(TAG, "copy start-desktop-noroot.sh failed", e);
            }
            // Chrome 兼容 shim：非 root 下 proot -0 伪装 uid=0，Chrome 拒绝启动沙箱
            // （"Running as root without --no-sandbox is not supported"），
            // 由 start-xfce-noroot.sh 把它 bind 覆盖到 /usr/bin/google-chrome-stable
            try { copyAssetIfNeeded(ctx, "chrome-noroot.sh",
                    TermuxConstants.TERMUX_FILES_DIR_PATH + "/chrome-noroot.sh"); } catch (Exception e) {
                Log.e(TAG, "copy chrome-noroot.sh failed", e);
            }
            // Termux 侧依赖（pulseaudio / proot / libtalloc）：随包分发
            String pkgsTar = TermuxConstants.TERMUX_FILES_DIR_PATH + "/termux-pkgs.tar.gz";
            try { copyAssetIfNeeded(ctx, "termux-pkgs.bin", pkgsTar); } catch (Exception e) {
                Log.e(TAG, "copy termux-pkgs failed", e);
            }
            // 音频（start-audio.sh 不依赖 root，与 root 版共用）
            String audioScript = TermuxConstants.TERMUX_HOME_DIR_PATH + "/start-audio.sh";
            try { copyAssetIfNeeded(ctx, "start-audio.sh", audioScript); } catch (Exception e) {
                Log.e(TAG, "copy start-audio.sh failed", e);
            }
            try { copyAssetIfNeeded(ctx, "pa-user.pa",
                    TermuxConstants.TERMUX_HOME_DIR_PATH + "/pa-user.pa"); } catch (Exception e) {
                Log.e(TAG, "copy pa-user.pa failed", e);
            }
            // 非 root 清理脚本（关闭系统 / 重启桌面 / 重新导入共用）
            try { copyAssetIfNeeded(ctx, "linux-cleanup-noroot.sh", NOROOT_CLEANUP_SCRIPT); } catch (Exception e) {
                Log.e(TAG, "copy linux-cleanup-noroot.sh failed", e);
            }
            String cmd =
                    "rm -f " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/.session-closing; " +
                    // 清掉上一会话（root 模式）残留的看门狗，防止它误杀本会话
                    // （按 pid 文件精确杀；见 chroot 分支注释，不能用 pkill -f）
                    "[ -f " + WATCHDOG_PID_FILE + " ] && kill -9 $(cat " + WATCHDOG_PID_FILE
                            + ") 2>/dev/null; rm -f " + WATCHDOG_PID_FILE + "; " +
                    // log 目录可能被 root 会话重建为 root:root，先尝试放开；
                    // 若仍不可写（chmod 失败）则回退到 $HOME 写日志，保证桌面照常拉起
                    "mkdir -p " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/log; " +
                    "chmod 777 " + TermuxConstants.TERMUX_FILES_DIR_PATH + "/log 2>/dev/null; " +
                    "LOGDIR=" + TermuxConstants.TERMUX_FILES_DIR_PATH + "/log; " +
                    "[ -w \"$LOGDIR\" ] || LOGDIR=$HOME; " +
                    // 后台拉起整套桌面（脚本自身幂等），前台给一个 Termux shell
                    "sh " + deskScript + " >\"$LOGDIR/desktop-start.log\" 2>&1 & " +
                    "echo \"Ubuntu(proot) 桌面正在后台启动，日志: $LOGDIR/desktop-start.log\"; " +
                    "exec sh";
            writeStatus("plan=proot (non-root) + desktop autostart");
            return new LaunchPlan(false, "/system/bin/sh",
                    new String[]{"-c", cmd}, "proot（非 root）");
        }
    }

    /**
     * 导入系统包：优先用外置 tar.gz（/sdcard/UbuntuXFCE/rootfs.tar.gz 等），
     * 没有外置包时才回退到 APK 内置 asset。
     *
     * 关键：必须以 root 解压。以 app 用户 + umask 077 解压会把整盘文件变成
     * 10183:10183 且权限被压成 600/700，导致 sudo/passwd/dbus 全部失效。
     * 解压后再跑一次 fix_perms.sh 兜底（toybox tar 对 setuid 恢复不完整）。
     */
    private static void extractRootfs(Context ctx, boolean rooted) throws Exception {
        File src = findExternalRootfs();
        boolean fromAsset = false;

        if (src == null) {
            File tarFile = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, COPIED_TAR);
            try (InputStream in = ctx.getAssets().open(ASSET_ROOTFS)) {
                Log.i(TAG, "External rootfs not found, copying bundled asset");
                long assetSize = in.available();
                if (!tarFile.exists() || tarFile.length() != assetSize) {
                    try (OutputStream out = new FileOutputStream(tarFile)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                src = tarFile;
                fromAsset = true;
            } catch (IOException noAsset) {
                throw new IOException("未找到系统包。请把 rootfs.tar.gz 放到 /sdcard/UbuntuXFCE/ 目录后重新打开应用。");
            }
        }

        File dest = new File(ROOTFS_DIR_PATH);
        dest.mkdirs();
        final String srcPath = src.getAbsolutePath();
        writeStatus("rootfs source=" + srcPath + " asset=" + fromAsset);

        int code = -1;
        String err = "";
        if (rooted) {
            // -p 保留权限位；以 root 运行才能还原 uid/gid
            String cmd = "/system/bin/tar xzpf '" + srcPath + "' -C '" + ROOTFS_DIR_PATH + "'";
            Process p = Runtime.getRuntime().exec(new String[]{suBinary(), "-c", cmd});
            err = readAll(p.getErrorStream());
            code = p.waitFor();
            Log.i(TAG, "tar(root) exit=" + code + " err=" + err);
            writeStatus("tar(root) exit=" + code + " " + err.substring(0, Math.min(160, err.length())));
        }
        if (code != 0) {
            // 兜底：以 app 用户解压，随后 fix_perms 校正属主与权限
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/tar", "xzf", srcPath, "-C", ROOTFS_DIR_PATH});
            err = readAll(p.getErrorStream());
            code = p.waitFor();
            Log.i(TAG, "tar(app) exit=" + code + " err=" + err);
            writeStatus("tar(app) exit=" + code + " " + err.substring(0, Math.min(160, err.length())));
        }
        if (code != 0) throw new IOException("系统包解压失败（tar exit " + code + "）：" + err);

        runFixPerms(ctx, rooted);

        if (fromAsset) src.delete();
    }

    /** 解压后统一校正属主 / 权限 / setuid（以 root 执行 fix_perms.sh） */
    private static void runFixPerms(Context ctx, boolean rooted) {
        if (!rooted) return;
        try {
            String dst = TermuxConstants.TERMUX_FILES_DIR_PATH + "/fix_perms.sh";
            copyAssetIfNeeded(ctx, "fix_perms.sh", dst);
            Process p = Runtime.getRuntime().exec(new String[]{suBinary(), "-c", "sh " + dst});
            String out = readAll(p.getInputStream());
            p.waitFor(600, TimeUnit.SECONDS);
            Log.i(TAG, "fix_perms output: " + out);
            writeStatus("fix_perms done");
        } catch (Exception e) {
            Log.e(TAG, "fix_perms failed", e);
            writeStatus("fix_perms failed: " + e.getMessage());
        }
    }

    /** Copy a small asset to an absolute path (0755) if it is not there yet or differs in size. */
    private static void copyAssetIfNeeded(Context ctx, String assetName, String destPath) throws Exception {
        File dest = new File(destPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = ctx.getAssets().open(assetName)) {
            int size = in.available();
            // 脚本类体积小但改动频繁，必须总是用 APK 内置版本覆盖：
            // 只比文件大小的话，若改完大小恰好没变，老用户升级后仍跑旧脚本。
            // 大资源包（.bin）保留大小比较，避免每次导入都重复复制十几 MB。
            boolean force = assetName.endsWith(".sh") || assetName.endsWith(".pa");
            if (!force && dest.exists() && dest.length() == size) {
                dest.setExecutable(true, false);
                return;
            }
            File tmp = new File(destPath + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
            dest.setExecutable(true, false);
        }
    }

    /** Diagnostics: public entry so Activity can trace launch flow. */
    public static void trace(String msg) { writeStatus("[Activity] " + msg); }

    /** Diagnostics: write status to a file readable via adb shell su. */
    private static void writeStatus(String msg) {
        try {
            File f = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, "linux-launcher-status.txt");
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes());
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(InputStream in) {
        StringBuilder sb = new StringBuilder();
        if (in == null) return "";
        try (InputStream stream = in) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) sb.append(new String(buf, 0, n));
        } catch (IOException ignored) {}
        return sb.toString();
    }
}
