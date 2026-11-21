#!/bin/bash
# proot 内启动 XFCE（非 root 版）
# 与 root 版 start-desktop.sh 的区别：没有真实 root，不能 su 切 alan，
# 直接以 proot 伪装的 root 跑整套桌面（配置复用 /home/alan，已 bind 到 /root）
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:0
unset XAUTHORITY
# Chrome/GTK 用 TMPDIR 做 POSIX 共享内存。Android 传进来的 TMPDIR 指向
# /data/user/0/com.termux/cache（权限 2771，proot 内进程写不进 → Trace/breakpoint trap）。
# 强制改指向 rootfs 内 1777 的 /tmp，避免共享内存崩溃且不依赖 cache 目录权限。
export TMPDIR=/tmp
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null

PULSE=tcp:127.0.0.1:4713
BUS=/tmp/xfce-bus
SENV=/root/.runtime/session.env
mkdir -p /root/.runtime && chmod 700 /root/.runtime

# 日志一律落在 $HOME/.runtime（该目录属主是 App uid）。
# 以前用 /tmp/xfce.log：root 模式的会话先写过这个文件后它变成 root:root 644，
# 而 /tmp 是 1777 带 sticky，普通用户既不能覆盖也不能删 →
# `nohup xfwm4 >/tmp/xfce.log` 的重定向直接失败，命令整条被跳过（铁律 5），
# 结果是 dbus/fcitx/panel 全都没起来，桌面黑屏还查不到原因。
LOG=/root/.runtime
for f in xfce.log dbus.log; do
  [ -w "$LOG/$f" ] || rm -f "$LOG/$f" 2>/dev/null
done

# 0) DNS 兜底：proot 下若 /etc/resolv.conf 不可写（root 属主），放一份到 /tmp 再 bind 太重，
#    直接试写，写不进去就用现有的
if [ ! -s /etc/resolv.conf ]; then
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /etc/resolv.conf 2>/dev/null
fi

# 1) X socket 检查
if [ ! -S /tmp/.X11-unix/X0 ]; then
  echo "X server 未启动"
  exit 1
fi

# 2) 固定地址的会话总线（Chrome/fcitx5 都靠它接上，与 root 版同思路）
if ! pgrep -f "dbus-daemon --session" >/dev/null 2>&1; then
  rm -f "$BUS" /tmp/dbus-* 2>/dev/null
  dbus-daemon --session --fork --address=unix:path=$BUS --print-address=0 >>"$LOG/dbus.log" 2>&1
  sleep 0.5
fi
printf 'export DBUS_SESSION_BUS_ADDRESS=unix:path=%s\nexport PULSE_SERVER=%s\n' "$BUS" "$PULSE" > "$SENV"
chmod 644 "$SENV" 2>/dev/null

# 2.1) 终端默认工作目录 = ~（用户家目录）。
#      proot 会话里 HOME=/root（bind 到 /home/alan），xfdesktop 的 CWD 是 Desktop，
#      图标启动的 xfce4-terminal 会继承它 → 终端开在桌面目录而非 ~。
#      写 terminalrc 的 DefaultWorkingDirectory，让所有终端窗口统一从 ~ 开始。
mkdir -p /root/.config/xfce4/terminal
TRC=/root/.config/xfce4/terminal/terminalrc
# xfce4-terminal 键名是 DefaultWorkingDir（配 use-default-working-dir=true）
if [ ! -f "$TRC" ]; then
  printf '[Configuration]\nuse-default-working-dir=true\nDefaultWorkingDir=%s\n' "/root" > "$TRC"
else
  grep -q '^DefaultWorkingDir=' "$TRC" || printf 'use-default-working-dir=true\nDefaultWorkingDir=%s\n' "/root" >> "$TRC"
fi
chmod 644 "$TRC" 2>/dev/null

# 3) XFCE（伪装 root 直接跑，不切用户）
# 注意：不用 startxfce4/xfce4-session 启动——proot 下它的会话链会导致
# panel/desktop 窗口一直 unmapped（黑屏）。实测手动直启组件必渲染成功。
# 组件已在跑（重启桌面场景）就跳过
if ! pgrep -x xfdesktop >/dev/null 2>&1; then
  export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS \
         GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx \
         SDL_IM_MODULE=fcitx SHELL=/bin/bash TERM=xterm-256color \
         PULSE_SERVER=$PULSE
  # 桌面图标 / 默认浏览器 / 输入法自启：每次启动幂等补齐，
  # 避免 rootfs 重新导入或恢复备份后 ~/Desktop 变空（表现为"没有浏览器"）
  [ -x /root/install-desktop-icons.sh ] && /root/install-desktop-icons.sh /root >>"$LOG/icons.log" 2>&1
  xfconf-query -c xfwm4 -p /general/use_compositing -n -t bool -s false 2>/dev/null
  xfconf-query -c xfce4-session -p /general/SaveOnExit -n -t bool -s false 2>/dev/null
  rm -rf /root/.cache/sessions/* 2>/dev/null
  nohup xfsettingsd >>"$LOG/xfce.log" 2>&1 &
  sleep 1
  nohup xfwm4 --replace >>"$LOG/xfce.log" 2>&1 &
  sleep 1
  nohup xfce4-panel >>"$LOG/xfce.log" 2>&1 &
  nohup xfdesktop >>"$LOG/xfce.log" 2>&1 &
  nohup thunar --daemon >>/dev/null 2>&1 &
  echo "桌面已启动（proot 伪装 root，配置取自 /home/alan）"
else
  echo "桌面已在运行"
  grep -q PULSE_SERVER "$SENV" 2>/dev/null || printf 'export PULSE_SERVER=%s\n' "$PULSE" >> "$SENV"
fi

# 4) 输入法兜底
(
  sleep 8
  if ! pgrep -x fcitx5 >/dev/null 2>&1; then
    export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR \
           GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx PULSE_SERVER=$PULSE
    fcitx5 -d >/dev/null 2>&1
  fi
) &
echo "XFCE(proot) + 音频 + /sdcard 就绪"
