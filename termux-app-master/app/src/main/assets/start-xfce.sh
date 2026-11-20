#!/system/bin/sh
# 一键启动 Ubuntu XFCE 桌面 + SSH + 音频 + SD 卡共享
# 用法: su -c /data/local/bin/start-xfce.sh
# 显式给 PATH：经 nsenter 进 init 命名空间后，继承来的 PATH 可能不含 /system/bin，
# 会导致 mount/tar 全部 "inaccessible or not found"
export PATH=/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin
R=/data/data/com.termux/files/ubuntu
P=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# X11 已并入 com.termux，APK 路径取 base.apk（过滤 split）
APK=$(pm path com.termux | grep -v split | head -1 | sed 's/package://')
TH=/data/data/com.termux/files/home
FD=/data/data/com.termux/files
mkdir -p "$FD/log" 2>/dev/null; chown 10183:10183 "$FD/log" 2>/dev/null

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

# 0.5) 保证运行时目录存在（rootfs 打包时不含 /tmp，缺了 X server 无法建 socket）
mkdir -p "$R/tmp" "$R/tmp/.X11-unix" "$R/run"
chmod 1777 "$R/tmp" 2>/dev/null
chmod 777 "$R/tmp/.X11-unix" 2>/dev/null
chmod 755 "$R/run" 2>/dev/null

# 1) 补齐 chroot 运行所需挂载 (幂等)
#    Android 的 toybox 没有 mountpoint，用 /proc/mounts 判断，否则会每次都重复 mount
ismount() { grep -qs " $1 " /proc/mounts; }
# 打包时排除了 /proc /sys /dev（虚拟文件系统），首次运行要自己建挂载点
mkdir -p "$R/proc" "$R/sys" "$R/dev" "$R/dev/pts"
ismount "$R/proc"    || mount -t proc proc "$R/proc"
ismount "$R/sys"     || mount -t sysfs sysfs "$R/sys"
ismount "$R/dev"     || mount -o bind /dev "$R/dev"
ismount "$R/dev/pts" || mount -o bind /dev/pts "$R/dev/pts"
mkdir -p "$R/dev/shm"
ismount "$R/dev/shm" || mount -t tmpfs -o size=1g,mode=1777 tmpfs "$R/dev/shm"

# 1.0) 兜底：若 /dev 没挂上，/dev/null 会是打包时留下的普通文件，必须清掉
if [ ! -c "$R/dev/null" ]; then
  bind_ok=$(mount -o bind /dev "$R/dev" 2>&1 && echo y)
  [ "$bind_ok" = y ] || echo "警告：/dev bind 失败：$bind_ok"
  rm -f "$R/dev/null" "$R/dev/zero" "$R/dev/random" "$R/dev/urandom" 2>/dev/null
fi

# 1.1) SD 卡共享（与 Android 同一份文件，不是复制）
mkdir -p "$R/mnt/sdcard"
ismount "$R/mnt/sdcard" || mount --bind /storage/emulated/0 "$R/mnt/sdcard" 2>/dev/null

# 2) X server 未运行则启动
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
      chown -R 10183:10183 "$TXU/bin" "$TXU/lib" "$TXU/etc" "$TXU/libexec" 2>/dev/null
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
  chown 10183:10183 "$AUD" 2>/dev/null
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
  chown 10183:10183 "$TH/pa-user.pa" 2>/dev/null
fi
if ! pgrep -x pulseaudio >/dev/null 2>&1; then
  su 10183 -c "sh $AUD" >/data/data/com.termux/files/log/audio.log 2>&1
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

# 5) chroot 里启动 sshd + XFCE（普通用户 alan）+ 输入法
#    再确认一次：走到这里可能已经过了十几秒，App 中途被杀也要停手
if [ -f "$FD/.session-closing" ]; then
  echo "session-closing flag present before desktop start, abort"
  exit 0
fi
chroot "$R" /bin/bash -c "export PATH=$P HOME=/root; unset XAUTHORITY; /bin/bash /root/start-desktop.sh"
echo "XFCE(alan) + SSH(2222) + 音频 + SD卡共享 就绪"
