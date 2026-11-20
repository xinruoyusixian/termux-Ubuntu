# 03 - 非 root（proot）专项

## 坑 1：proot 直接 fatal（$PATH=(null)）
- **真因**：Termux 会话默认 `LD_PRELOAD=libtermux-exec.so`（shebang 处理），与 proot 的 ptrace/loader 冲突。
- **解法**：proot 调用前 `unset LD_PRELOAD`。

## 坑 2：proot 伪装 uid=0 → Chrome 拒绝启动（v27 主修复）
- **现象**：非 root 桌面里点浏览器图标毫无反应，"软件像没了"（rootfs 是共用的，软件其实都在）。
- **真因**：`proot -0` 让进程 getuid()=0，Chrome 检测后直接退出：
  `Running as root without --no-sandbox is not supported. See https://crbug.com/638180.`
- **解法**：start-xfce-noroot.sh 用 proot bind 把 `chrome-noroot.sh`（shim，`--no-sandbox --disable-gpu-sandbox --test-type` + 输入法/音频环境）覆盖到 `/usr/bin/google-chrome-stable`。root(chroot) 会话不挂这个 bind，完全不受影响。
- **次生坑**：shim 不能转发到 rootfs 里已有的 `/usr/local/bin/chrome`——它内部 exec 的正是被覆盖的 `/usr/bin/google-chrome-stable`，无限递归（实测 SIGTERM）。**shim 必须自包含，直接 exec 真实二进制 `/opt/google/chrome/chrome`**。

## 坑 3：proot 下 startxfce4 会话链永远黑屏
- **现象**：桌面起来但全黑，xwininfo 里 panel/desktop 全是 10x10 unmapped；`xfwm4: Unsupported GL renderer (llvmpipe)` 卡住会话链。
- **解法**：不用 startxfce4/xfce4-session，手动直启：
  ```sh
  xfconf-query -c xfwm4 -p /general/use_compositing -n -t bool -s false   # 关合成器
  nohup xfsettingsd & sleep 1
  nohup xfwm4 --replace & sleep 1
  nohup xfce4-panel & nohup xfdesktop & nohup thunar --daemon &
  ```
  实测手动直启必渲染成功。

## 坑 4：proot 内 HOME 混乱
- App shell 的 HOME 是 `/data/data/com.termux/files/home`，在 proot 视图里不存在 → 程序找不到配置。
- **解法**：proot 命令里显式 `export HOME=/root`（/root 已 bind 到 rootfs 的 /home/alan，两模式共用同一份配置）。

## 坑 5：termux-pkgs.tar.gz 丢执行位 → "缺少 proot"
- **真因**：Python tarfile 打包不保留 exec 位。
- **解法**：打包脚本对 `usr/bin`、`usr/libexec` 强制 mode 755；启动脚本再 `chmod 755` 兜底。

## 坑 6：非 root 写不了 /data/local/tmp → 日志重定向失败
- **现象**：脚本"看似没跑"，无任何日志（v25 首次黑屏根因）。
- **解法**：所有日志/临时目录收进 App 自己目录（files/log、files/tmp-txroot）；Java 端 `mkdir -p files/log` 必须放在重定向之前。

## 坑 7：非 root 天然"随 App 关闭"
- 非 root 桌面栈全部是 App 的后代进程，force-stop App = 全停，无常驻模式。测试时注意 force-stop 会连带杀桌面。

## 坑 8：proot 手动验证方法
- 先最小命令（proot -r xxx /bin/bash）→ 逐组加 bind（/dev /proc /sys → /sdcard → X socket → home）→ 最后加文件 bind。哪一步挂了一眼定位。
