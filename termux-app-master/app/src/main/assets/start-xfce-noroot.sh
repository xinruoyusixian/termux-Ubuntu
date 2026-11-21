#!/system/bin/sh
# 非 root 一键启动 Ubuntu XFCE 桌面（proot 版）
# 与 root 版 start-xfce.sh 的区别：
#   - 不 nsenter、不 remount、不 chroot —— 全部以 App 自己的 uid(10183) 运行
#   - X server 直接 app_process 拉起（termux-x11 官方玩法，本来就不需要 root）
#   - 音频直接以 App uid 跑 pulseaudio
#   - 桌面在 proot 里以"伪装 root"跑，用 /home/alan 的现成配置
#   - 进程都是 App 的后代，App 退出系统随之结束（非 root 不提供常驻模式）
export PATH=/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin
FD=/data/data/com.termux/files
R=$FD/ubuntu
TXU=$FD/usr
TH=$FD/home

# APK 路径：**不能依赖 `pm path`**。
# App 自己拉起本脚本时处于 untrusted_app 域，`pm path com.termux` 会返回
#   cmd: Failure calling service package: Failed transaction (2147483646)
# 于是 APK 变量变成这串错误文本，app_process 起不来，X server 静默死亡
# （x11.log 0 字节）→ proot 里没有 X socket → 桌面黑屏。
# 所以按可靠性排序取：Java 写好的 .apk-path > Termux 注入的环境变量 > pm path。
APK=$(cat "$FD/.apk-path" 2>/dev/null)
[ -f "$APK" ] || APK="$TERMUX_APP__APK_PATH"
[ -f "$APK" ] || APK=$(pm path com.termux 2>/dev/null | grep -v split | head -1 | sed 's/package://')
echo "APK=$APK"

# 日志目录可能被 root 会话写成 root:root（重新解包/跑过 root 会话后）：
# 先尝试 chmod 放开；仍不可写就把日志切到 $HOME/log，保证桌面照常启动
chmod 777 "$FD/log" 2>/dev/null
if [ -w "$FD/log" ]; then
  FD_LOG="$FD/log"
else
  mkdir -p "$TH/log" 2>/dev/null
  FD_LOG="$TH/log"
fi

if [ -f "$FD/.session-closing" ]; then
  echo "session-closing flag present, abort"
  exit 0
fi

# 0.01) 清理 root(chroot) 模式残留的 fcitx5/chrome：
#       从 root 模式切换到 proot 模式时，旧 chroot 会话的 fcitx5/chrome 在
#       Android 全局命名空间仍可见，它们的 DISPLAY 与新 proot 会话冲突
#       → 输入法不生效 / Chrome profile 锁冲突。
#       必须在 proot 启动前清理（proot 内看不到 chroot 命名空间的进程）。
pkill -9 -x fcitx5 2>/dev/null
pkill -9 -x chrome 2>/dev/null
pkill -9 -f "google-chrome" 2>/dev/null
sleep 0.5

# 0) 首次运行把 pulseaudio/proot 等从随包分发的 tar 里补齐（幂等）
if { [ ! -x "$TXU/bin/pulseaudio" ] || [ ! -x "$TXU/bin/proot" ]; } && [ -f "$FD/termux-pkgs.tar.gz" ]; then
  TD=$FD/tmp-txroot
  mkdir -p "$FD/log" 2>/dev/null
  rm -rf "$TD"; mkdir -p "$TD"
  if tar -xzf "$FD/termux-pkgs.tar.gz" -C "$TD"; then
    mkdir -p "$TXU/bin" "$TXU/lib" "$TXU/libexec" "$TXU/etc" "$TXU/tmp" "$TH"
    if ! cp -a "$TD/data/data/com.termux/files/usr/." "$TXU/"; then
      echo "警告：组件复制失败，检查 $TD 结构："
      ls "$TD" 2>&1
      find "$TD" -maxdepth 3 | head -10
    fi
    # 兜底：确保二进制可执行（有些打包渠道会丢 exec 位）
    chmod 755 "$TXU/bin/"* "$TXU/libexec/proot/"* 2>/dev/null
    echo "Termux 组件已补齐（pulseaudio/proot）"
  else
    echo "错误：termux-pkgs.tar.gz 解压失败"
  fi
  rm -rf "$TD"
fi
[ -x "$TXU/bin/proot" ] || { echo "缺少 proot，无法在非 root 模式启动桌面"; exit 1; }

# Chrome 兼容 shim（将被 bind 覆盖到 /usr/bin/google-chrome-stable）
# copyAssetIfNeeded 已设执行位，这里兜底一次
chmod 755 "$FD/chrome-noroot.sh" 2>/dev/null

mkdir -p "$TXU/tmp/.X11-unix" "$R/tmp" 2>/dev/null
chmod 777 "$TXU/tmp/.X11-unix" 2>/dev/null

# 0.1) /dev/shm：Android 宿主没有 /dev/shm，proot -b /dev 挂进来的宿主 /dev 里也没有，
#      Chrome/fcitx 需要它做 POSIX 共享内存，缺失会直接 FATAL 退出（表现为点图标没反应）。
#      在 rootfs 内建一个 1777 目录，用 proot bind 覆盖 guest 的 /dev/shm。
mkdir -p "$R/.shm" 2>/dev/null
chmod 1777 "$R/.shm" 2>/dev/null

# 1) X server（App uid 直接跑，socket 放在 Termux usr/tmp，proot 会把它 bind 成 /tmp/.X11-unix）
#    僵尸 X（有进程无 socket）必须杀掉重来，否则下面直接跳过启动 → 永久黑屏
if pgrep -f "termux-x11" >/dev/null 2>&1 && [ ! -S "$TXU/tmp/.X11-unix/X0" ]; then
  echo "检测到僵尸 X server（有进程无 socket），清理重启"
  pkill -9 -f "termux-x11" 2>/dev/null
  pkill -9 -f "CmdEntryPoint" 2>/dev/null
  sleep 1
  rm -f "$TXU/tmp/.X0-lock" "$TXU/tmp/.X11-unix/X0" 2>/dev/null
fi
if ! pgrep -f "termux-x11" >/dev/null 2>&1; then
  rm -f "$TXU/tmp/.X0-lock" "$TXU/tmp/.X11-unix/X0" 2>/dev/null
  CLASSPATH="$APK" TMPDIR="$TXU/tmp" XKB_CONFIG_ROOT="$R/usr/share/X11/xkb" \
  nohup /system/bin/app_process64 / --nice-name=termux-x11 \
  com.termux.x11.CmdEntryPoint :0 >"$FD_LOG/x11.log" 2>&1 &
  i=0
  while [ ! -S "$TXU/tmp/.X11-unix/X0" ] && [ $i -lt 16 ]; do sleep 0.5; i=$((i+1)); done
fi

# 1.1) X server 没起来就别往下走（以前会继续拉 proot，最后表现为"黑屏"，
#      真正的错误被埋在一行 "X server 未启动" 里。现在把原因打清楚并退出）
if [ ! -S "$TXU/tmp/.X11-unix/X0" ]; then
  echo "错误：X server 未启动（socket 不存在）"
  echo "--- APK=$APK ---"
  echo "--- x11.log ---"
  tail -20 "$FD_LOG/x11.log" 2>/dev/null
  exit 1
fi

# 2) 显示界面
am start --user 0 -n com.termux/com.termux.x11.MainActivity >/dev/null 2>&1

# 3) 音频：非 root 下 App uid 就是 10183，直接跑，不用 su
if ! pgrep -x pulseaudio >/dev/null 2>&1; then
  sh "$TH/start-audio.sh" >"$FD_LOG/audio.log" 2>&1 &
fi

# 4) proot 拉起桌面：
#    -0                      伪装 root（rootfs 文件本来就归 10183，读写无碍）
#    /home/alan -> /root     复用打包时已配好的 XFCE/中文输入法配置
#    usr/tmp/.X11-unix -> /tmp/.X11-unix   让桌面连上 X server
#    /sdcard/Download        下载目录（proot 不产生真挂载，卸载/清数据无穿透风险）
if ! pgrep -f "[p]root.*ubuntu" >/dev/null 2>&1; then
  rm -f "$FD/.session-closing"
  # 清掉上一轮 root 会话留下的 root 属主日志/临时文件。
  # 关键：root 会话写出的 /tmp/xfce.log 是 root:root 644，App uid 既不能覆盖
  # 也不能删除（/tmp 被 chmod 1777 后带 sticky），而 shell 的 `> file` 重定向
  # 失败会**直接跳过整条命令**（踩坑铁律 5）→ 桌面四个组件一个都不启动 = 黑屏。
  # 现在这些日志已经改写到 $HOME/.runtime，这里仍顺手清一遍历史残留。
  rm -f "$R/tmp/xfce.log" "$R/tmp/dbus.info" "$R/tmp/.chrome-launch-lock" 2>/dev/null
  # XFCE 必须在 X11 Activity 前台（Surface 有效）时启动，否则所有窗口
  # 初始化成 10x10，切回前台也不重画 -> 桌面永远黑屏。
  # App 域启动自己包的 Activity 无需任何权限
  am start -n com.termux/com.termux.x11.MainActivity >/dev/null 2>&1
  sleep 2
  # Termux 会话默认带 LD_PRELOAD=libtermux-exec.so（shebang 处理），
  # 与 proot 的 ptrace/loader 冲突，会导致 proot 直接 fatal error
  unset LD_PRELOAD
  LD_LIBRARY_PATH=$TXU/lib nohup "$TXU/bin/proot" \
    -0 \
    -r "$R" \
    -b /dev -b /dev/pts -b /proc -b /sys \
    -b "$R/.shm":/dev/shm \
    -b /sdcard/Download:/mnt/download \
    -b "$TXU/tmp/.X11-unix":/tmp/.X11-unix \
    -b "$R/home/alan":/root \
    -b "$FD/start-desktop-noroot.sh":/root/start-desktop-noroot.sh \
    -b "$FD/install-desktop-icons.sh":/root/install-desktop-icons.sh \
    -b "$FD/install-im-toggle.sh":/root/install-im-toggle.sh \
    -b "$FD/chrome-noroot.sh":/usr/bin/google-chrome-stable \
    -w /root \
    /bin/bash -c "unset XAUTHORITY; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root; /bin/bash /root/start-desktop-noroot.sh" \
    >"$FD_LOG/proot.log" 2>&1 &
  sleep 12
fi
echo "桌面(proot) + X + 音频 + 下载目录 就绪"
