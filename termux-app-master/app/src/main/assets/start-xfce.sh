#!/system/bin/sh
# 一键启动 Ubuntu XFCE 桌面 + SSH + 音频 + SD 卡共享
# 用法: su -c /data/local/bin/start-xfce.sh
# 显式给 PATH：经 nsenter 进 init 命名空间后，继承来的 PATH 可能不含 /system/bin，
# 会导致 mount/tar 全部 "inaccessible or not found"
export PATH=/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin
R=/data/data/com.termux/files/ubuntu
P=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# X11 已并入 com.termux，APK 路径取 base.apk（过滤 split）
# 优先用 App 自己写下的 .apk-path：不依赖 pm 命令（App 域里 pm path 可能直接失败）
APK=$(cat /data/data/com.termux/files/.apk-path 2>/dev/null)
[ -f "$APK" ] || APK=$(pm path com.termux 2>/dev/null | grep -v split | head -1 | sed 's/package://')
TH=/data/data/com.termux/files/home
FD=/data/data/com.termux/files
# App uid 动态获取：每台设备/每次重装都可能不同，禁止写死（10183 只是历史值）。
# 解析顺序：files 目录属主 → 数据目录属主 → pm 输出的 uid: 字段
APP_UID=$(stat -c %u "$FD" 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(stat -c %u /data/data/com.termux 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(pm list packages -U com.termux 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1)
if [ -z "$APP_UID" ]; then
  echo "错误：无法确定 com.termux 的 uid，中止启动"
  exit 1
fi
mkdir -p "$FD/log" 2>/dev/null; chown "$APP_UID:$APP_UID" "$FD/log" 2>/dev/null

# -1) 关键：App 被拉起时处在它自己的 mount namespace 里，
#     那里 mount() 会失败（/dev、/proc 全挂不上，chroot 内 /dev/null 变成普通文件，
#     dbus/xfce 全报 Permission denied）。先切到 init(pid 1) 的全局 mount namespace。
if [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
  # toybox 的 nsenter 用 -F 表示 fork（不是 -f）
  exec /system/bin/nsenter -t 1 -m -F -- /system/bin/sh "$0" "$@"
fi

# 0) 去掉 /data 的 nosuid，否则 chroot 内 sudo/su/passwd 全部失效
mount -o remount,suid /data 2>/dev/null

# 0.2) 会话已被看门狗关闭（App 已退出）就别再启动桌面。
#      场景：App 被杀时本脚本还在启动过程中，它是独立进程（PPID=1），
#      会继续把 X server / XFCE 拉起来，导致看门狗刚清完又被起回来。
if [ -f "$FD/.session-closing" ]; then
  echo "session-closing flag present, abort"
  exit 0
fi

# 0.3) 清理 Android 原生命名空间残留的 fcitx5/chrome：
#      从 proot 模式切换到 root 模式时，旧 proot 会话的 fcitx5/chrome 可能在
#      Android 原生命名空间残留（root=/ 而非 chroot），它们的 DISPLAY 与
#      新 root 会话冲突 → 输入法不生效 / Chrome profile 锁冲突。
#      chroot 内的 start-desktop.sh 看不到这些进程，必须在 Android 侧清理。
pkill -9 -x fcitx5 2>/dev/null
pkill -9 -x chrome 2>/dev/null
pkill -9 -f "google-chrome" 2>/dev/null
sleep 0.5

# 0.4) uid 自适应（跨设备通用）：不同设备/格式化重装后 com.termux 的 uid 可能不同，
#      rootfs 包里的 passwd 属主是打包设备的旧 uid → chroot 里 su - alan 切到旧 uid，
#      与当前设备文件属主不符 → 无权访问 700 家目录和 /bin/bash → 黑屏只有光标。
#      一律以当前设备 com.termux 数据目录的真实属主为准，把 rootfs 关键 uid 全部对齐。
if [ -n "$APP_UID" ]; then
  sed -i "s/^alan:x:[0-9]*:[0-9]*:/alan:x:$APP_UID:$APP_UID:/" "$R/etc/passwd" 2>/dev/null
  sed -i "s/^alan:x:[0-9]*:/alan:x:$APP_UID:/" "$R/etc/group" 2>/dev/null
  chown -R "$APP_UID:$APP_UID" "$R/home/alan" 2>/dev/null
  chown "$APP_UID:$APP_UID" "$R/bin/bash" "$R/etc/passwd" "$R/etc/group" 2>/dev/null
  echo "uid 自适应：alan=$APP_UID"
fi

# 0.5) 保证运行时目录存在（rootfs 打包时不含 /tmp，缺了 X server 无法建 socket）
mkdir -p "$R/tmp" "$R/tmp/.X11-unix" "$R/run"
chmod 1777 "$R/tmp" 2>/dev/null
chmod 777 "$R/tmp/.X11-unix" 2>/dev/null
chmod 755 "$R/run" 2>/dev/null

# 1) 补齐 chroot 运行所需挂载 (幂等)
#    Android 的 toybox 没有 mountpoint，用 /proc/mounts 判断，否则会每次都重复 mount
ismount() { grep -qs " $1 " /proc/mounts; }
# 打包时排除了 /proc /sys /dev（虚拟文件系统），首次运行要自己建挂载点
mkdir -p "$R/proc" "$R/sys" "$R/dev"
ismount "$R/proc"    || mount -t proc proc "$R/proc"
ismount "$R/sys"     || mount -t sysfs sysfs "$R/sys"

# /dev 不再 bind 真实 /dev：改挂独立 tmpfs + mknod 基础节点。
# 教训：bind 穿透删除曾把真实 /dev 内容删掉（/dev/null 事故），而且
# App 被卸载/清数据时系统会 rm 整个数据目录 —— bind 的 /dev 会被穿透清空。
# 独立 tmpfs 实例被穿透删除时只影响这个空实例，真实 /dev 毫发无损。
if ! ismount "$R/dev"; then
  if mount -t tmpfs -o mode=755 tmpfs "$R/dev" 2>/dev/null; then
    mknod -m 666 "$R/dev/null"    c 1 3 2>/dev/null
    mknod -m 666 "$R/dev/zero"    c 1 5 2>/dev/null
    mknod -m 666 "$R/dev/full"    c 1 7 2>/dev/null
    mknod -m 666 "$R/dev/random"  c 1 8 2>/dev/null
    mknod -m 666 "$R/dev/urandom" c 1 9 2>/dev/null
    mknod -m 666 "$R/dev/tty"     c 5 0 2>/dev/null
    ln -sf /proc/self/fd   "$R/dev/fd"     2>/dev/null
    ln -sf /proc/self/fd/0 "$R/dev/stdin"  2>/dev/null
    ln -sf /proc/self/fd/1 "$R/dev/stdout" 2>/dev/null
    ln -sf /proc/self/fd/2 "$R/dev/stderr" 2>/dev/null
    ln -sf pts/ptmx        "$R/dev/ptmx"   2>/dev/null
    # mknod 失败（SELinux 限制）则退回 bind —— 至少能用，但记得这有穿透风险
    if [ ! -c "$R/dev/null" ]; then
      umount "$R/dev" 2>/dev/null
      mount -o bind /dev "$R/dev" 2>/dev/null
      echo "警告：mknod 失败，/dev 退回 bind 模式"
    fi
  else
    mount -o bind /dev "$R/dev" 2>/dev/null
  fi
fi
# devpts 独立挂载（虚拟 FS，穿透删除无副作用），不再 bind
# 注意顺序：dev/pts 必须在 /dev 的 tmpfs 挂好之后才 mkdir，
# 否则建在 tmpfs 底下被挂载覆盖 → mount 报 "No such file or directory"，
# chroot 里没有 /dev/pts，终端/ssh/会话都会异常。
[ -d "$R/dev/pts" ] || mkdir -p "$R/dev/pts"
ismount "$R/dev/pts" || mount -t devpts -o mode=600,ptmxmode=666 devpts "$R/dev/pts"
mkdir -p "$R/dev/shm"
ismount "$R/dev/shm" || mount -t tmpfs -o size=1g,mode=1777 tmpfs "$R/dev/shm"

# 1.1) 下载交换区（零穿透设计，可写）
# 真实 /sdcard 不再 bind 进 chroot：整卡 rw bind 曾在 App 卸载/清数据时被
# 系统 rm 穿透挂载点删空整张 SD 卡（真实事故）。现在 chroot 内挂的是 App
# 私有目录里的「交换暂存区」(files/download-xfer) —— 即使卸载/清数据穿透，
# 删的也只是暂存区（本来就该清的临时区），真实 SD 卡文件零风险。
# 暂存区与真实 /sdcard/Download 由 download-sync.sh 搬运器双向同步：
#   mnt/download/out -> Download（桌面导出，同名自动改名）
#   mnt/download/in  <- Download（桌面读取外部文件，5 秒刷新镜像）
XFER=$FD/download-xfer
mkdir -p "$XFER/out" "$XFER/in" "$R/mnt/download"
chmod -R 777 "$XFER" 2>/dev/null
chown -R "$APP_UID:$APP_UID" "$XFER" 2>/dev/null
ismount "$R/mnt/download" || mount -o bind "$XFER" "$R/mnt/download" 2>/dev/null
# 桌面里的入口（alan 家目录）
ln -sfn /mnt/download/out "$R/home/alan/下载-上传" 2>/dev/null
ln -sfn /mnt/download/in  "$R/home/alan/下载-镜像" 2>/dev/null
# 搬运器没在跑就拉起（App uid 身份 —— 写 /sdcard 需要 com.termux 的存储授权）
if ! pgrep -f '[d]ownload-sync.sh' >/dev/null 2>&1; then
  if [ -f "$FD/download-sync.sh" ]; then
    chmod 755 "$FD/download-sync.sh" 2>/dev/null
    nohup su "$APP_UID" -c "sh $FD/download-sync.sh \"$XFER\" /storage/emulated/0/Download 5" \
      >/dev/null 2>&1 &
  fi
fi

# 2) X server 未运行则启动
#    注意：进程存在但 socket 没建 = 卡死的僵尸 X（表现为一直黑屏）。
#    以前只判断 pgrep，僵尸进程会让这里直接跳过启动，桌面永远起不来。
if pgrep -f "termux-x11" >/dev/null 2>&1 && [ ! -S "$R/tmp/.X11-unix/X0" ]; then
  echo "检测到僵尸 X server（有进程无 socket），清理重启"
  pkill -9 -f "termux-x11" 2>/dev/null
  pkill -9 -f "CmdEntryPoint" 2>/dev/null
  sleep 1
  rm -f "$R/tmp/.X0-lock" "$R/tmp/.X11-unix/X0" 2>/dev/null
fi
if ! pgrep -f "termux-x11" >/dev/null 2>&1; then
  # 清掉上次残留的锁和 socket（否则 X 会因"Server is already active"直接退出）
  rm -f "$R/tmp/.X0-lock" "$R/tmp/.X11-unix/X0" 2>/dev/null
  mkdir -p "$R/tmp/.X11-unix"
  chmod 777 "$R/tmp" "$R/tmp/.X11-unix"
  CLASSPATH="$APK" TMPDIR="$R/tmp" \
  XKB_CONFIG_ROOT="$R/usr/share/X11/xkb" \
  nohup /system/bin/app_process64 / --nice-name=termux-x11 \
  com.termux.x11.CmdEntryPoint :0 >/data/data/com.termux/files/log/x11.log 2>&1 &
  # 等 socket 真正出现（最多 8 秒），不要盲等固定秒数
  i=0
  while [ ! -S "$R/tmp/.X11-unix/X0" ] && [ $i -lt 16 ]; do sleep 0.5; i=$((i+1)); done
fi

# 3) 启动显示 Activity
am start --user 0 -n com.termux/com.termux.x11.MainActivity >/dev/null 2>&1
sleep 1

# 3.6) Termux 侧依赖（pulseaudio / termux-api）随 APK 分发
#      国内网络下 Termux 官方源基本不可用（默认 DNS 8.8.8.8 被墙），
#      所以把 deb 预先解包成文件树打进 APK，首次启动直接补齐，开箱即有声。
TXU=/data/data/com.termux/files/usr
# 全新安装（没有 bootstrap）时 usr/ 整个目录都不存在，
# 不先建好，下面的 cp -an src/. dest/ 会直接失败
mkdir -p "$TXU/bin" "$TXU/lib" "$TXU/etc" "$TXU/tmp" "$TH" 2>/dev/null
if [ ! -x "$TXU/bin/pulseaudio" ] && [ -f "$FD/termux-pkgs.tar.gz" ]; then
  TD=/data/data/com.termux/files/tmp-txroot
  rm -rf "$TD"; mkdir -p "$TD"
  if tar -xzf "$FD/termux-pkgs.tar.gz" -C "$TD" 2>/dev/null; then
    if [ -d "$TD/data/data/com.termux/files/usr" ]; then
      # 只补缺失文件，绝不覆盖 bootstrap 自带的 bash/coreutils 等
      cp -an "$TD/data/data/com.termux/files/usr/." "$TXU/" 2>/dev/null
      chown -R "$APP_UID:$APP_UID" "$TXU/bin" "$TXU/lib" "$TXU/etc" "$TXU/libexec" 2>/dev/null
      echo "Termux 音频/API 组件已补齐"
    fi
    rm -rf "$TD"
  fi
fi

# 4) 音频：Termux 侧 PulseAudio（走 Android OpenSL ES；chroot 经 127.0.0.1:4713 接入）
#    必须用 Termux 的 uid 运行（root 跑会让 .pulse-rt 属主变 root，后续普通用户连不上）
AUD=$TH/start-audio.sh
if [ ! -f "$AUD" ]; then
  cat > "$AUD" <<'AUDEOF'
#!/system/bin/sh
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
export PATH=/system/bin:/system/xbin:$P/bin
export LD_LIBRARY_PATH=$P/lib
export HOME=$H
export TMPDIR=$P/tmp
export PULSE_RUNTIME_PATH=$H/.pulse-rt
LOG=$H/pa.log
mkdir -p "$PULSE_RUNTIME_PATH"
if ps -A 2>/dev/null | grep -q "[p]ulseaudio"; then exit 0; fi
rm -f "$PULSE_RUNTIME_PATH/pid"
setsid pulseaudio -n -F "$H/pa-user.pa" --log-target=file:"$LOG" \
  --exit-idle-time=-1 --disallow-exit < /dev/null > /dev/null 2>&1 &
sleep 3
AUDEOF
  chown "$APP_UID:$APP_UID" "$AUD" 2>/dev/null
  chmod 755 "$AUD"
fi
# 配置文件缺失时补一份（新装设备必备）
if [ ! -f "$TH/pa-user.pa" ]; then
  cat > "$TH/pa-user.pa" <<'PAEOF'
load-module module-native-protocol-unix
load-module module-native-protocol-tcp listen=127.0.0.1 port=4713 auth-anonymous=1
load-module module-aaudio-sink
load-module module-always-sink
PAEOF
  chown "$APP_UID:$APP_UID" "$TH/pa-user.pa" 2>/dev/null
fi
if ! pgrep -x pulseaudio >/dev/null 2>&1; then
  su "$APP_UID" -c "sh $AUD" >/data/data/com.termux/files/log/audio.log 2>&1
  sleep 1
  if ! pgrep -x pulseaudio >/dev/null 2>&1; then
    # 兜底：借 Termux 自己的 RunCommandService 拉起
    am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService \
      -a com.termux.RUN_COMMAND \
      --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/sh \
      --esa com.termux.RUN_COMMAND_ARGUMENTS "$AUD" \
      --es com.termux.RUN_COMMAND_WORKDIR "$TH" \
      --ez com.termux.RUN_COMMAND_BACKGROUND true >/dev/null 2>&1
  fi
fi

# 4.5) chroot 内的启动脚本随 APK 分发，启动时覆盖进 rootfs
#      （这样调整启动逻辑不必重打 1.4G 的 rootfs 镜像）
if [ -f "$FD/start-desktop.sh" ]; then
  cp -f "$FD/start-desktop.sh" "$R/root/start-desktop.sh"
  chmod 755 "$R/root/start-desktop.sh"
fi
# 桌面图标安装脚本也带进去：rootfs 重新导入/恢复备份后 ~/Desktop 会变空，
# 每次启动都由 start-desktop.sh 调它补齐（浏览器 / 中英切换 / 终端 / 文件管理）
if [ -f "$FD/install-desktop-icons.sh" ]; then
  cp -f "$FD/install-desktop-icons.sh" "$R/root/install-desktop-icons.sh"
  chmod 755 "$R/root/install-desktop-icons.sh"
fi
# 面板「中英切换」按钮安装脚本（fcitx5 托盘图标缺 StatusNotifier 宿主）
if [ -f "$FD/install-im-toggle.sh" ]; then
  cp -f "$FD/install-im-toggle.sh" "$R/root/install-im-toggle.sh"
  chmod 755 "$R/root/install-im-toggle.sh"
fi

# 5) chroot 里启动 sshd + XFCE（普通用户 alan）+ 输入法
#    再确认一次：走到这里可能已经过了十几秒，App 中途被杀也要停手
if [ -f "$FD/.session-closing" ]; then
  echo "session-closing flag present before desktop start, abort"
  exit 0
fi
chroot "$R" /bin/bash -c "export PATH=$P HOME=/root; unset XAUTHORITY; /bin/bash /root/start-desktop.sh"
echo "XFCE(alan) + SSH(2222) + 音频 + 下载交换区(可写/零穿透) 就绪"
