#!/bin/bash
# 拉起 Ubuntu XFCE 桌面（显示端由 Android 侧 Termux:X11 提供）
# 默认以普通用户 alan 运行，需要 root 时用 sudo（密码 alan）
# 已在 Android 侧 start-xfce.sh 中调用；也可在 Ubuntu 内手动执行
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:0
unset XAUTHORITY

U=alan
UH=/home/alan
PULSE=tcp:127.0.0.1:4713
# 固定会话总线地址：这样任何进程（含从外部拉起的 Chrome）都能读 session.env 接上
BUS=/tmp/xfce-bus
SENV=$UH/.runtime/session.env

# 0) 确保普通用户能读 DNS
#    /etc/resolv.conf -> /run/systemd/resolve/stub-resolv.conf 权限是 600(root)，
#    普通用户读不到会导致 Chrome 报 DNS_PROBE_FINISHED_BAD_CONFIG
if [ -L /etc/resolv.conf ] || [ ! -r /etc/resolv.conf ]; then
  TMP=$(mktemp)
  cat /etc/resolv.conf > "$TMP" 2>/dev/null
  grep -q nameserver "$TMP" 2>/dev/null || printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$TMP"
  rm -f /etc/resolv.conf
  cp "$TMP" /etc/resolv.conf
  chmod 644 /etc/resolv.conf
  rm -f "$TMP"
fi

# X11 socket 检查
if [ ! -S /tmp/.X11-unix/X0 ]; then
  echo "X server 未启动，请先在 Android 端执行：su -c /data/local/bin/start-xfce.sh"
  exit 1
fi

# 1) SSH（端口 2222）
#    /run 在打包镜像里被误存成 alan:alan 700，sshd 写 pid 会失败，这里纠正
chown root:root /run 2>/dev/null
chmod 755 /run 2>/dev/null
mkdir -p /run/sshd
chown root:root /run/sshd 2>/dev/null
chmod 755 /run/sshd 2>/dev/null
# btmp 属主错误会让 sshd 每次登录都刷 "Excess permission" 警告
touch /var/log/btmp 2>/dev/null
chown root:root /var/log/btmp 2>/dev/null
chmod 600 /var/log/btmp 2>/dev/null
# 端口已在监听就不再拉第二个实例（重复实例会互相抢端口，表现为连接被 reset）
if ! ss -ltn 2>/dev/null | grep -q ':2222 '; then
  /usr/sbin/sshd 2>/dev/null
fi

# 2) 会话运行时目录（必须归该用户且 700，否则 fcitx5/dbus 会报权限错）
install -d -m 700 -o "$U" -g "$U" "$UH/.runtime"

# 3) 以普通用户启动 XFCE
if ! pgrep -u "$U" -x xfce4-session >/dev/null 2>&1; then
  # 3.1 起一个【地址固定】的会话总线。
  #     之前用 dbus-run-session，地址是随机的 /tmp/dbus-XXXX：
  #     从桌面图标/面板以外的路径拉起的程序（尤其 Chrome）拿不到该变量，
  #     fcitx5 的 GTK 前端连不上总线 → 表现为"输入法不出现、只能打英文字母"。
  #     现在总线固定为 unix:path=/tmp/xfce-bus，并写入 session.env 供任何程序 source。
  pkill -u "$U" -f "dbus-daemon --session" >/dev/null 2>&1
  pkill -f "dbus-daemon --session --fork --address=unix:path=$BUS" >/dev/null 2>&1
  rm -f "$BUS" /tmp/dbus-*
  sleep 0.3
  # 必须由该用户启动：socket 属主若是 root，普通用户连不上，fcitx5 会直接退出
  su - "$U" -c "dbus-daemon --session --fork --address=unix:path=$BUS --print-address=0" >/tmp/dbus.info 2>&1
  sleep 0.5
  printf 'export DBUS_SESSION_BUS_ADDRESS=unix:path=%s\nexport PULSE_SERVER=%s\n' "$BUS" "$PULSE" > "$SENV"
  chown "$U:$U" "$SENV" 2>/dev/null
  chmod 644 "$SENV"

  su - "$U" -c "export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$UH/.runtime PULSE_SERVER=$PULSE SHELL=/bin/bash TERM=xterm-256color GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx SDL_IM_MODULE=fcitx; unset XAUTHORITY; cd $UH; xfconf-query -c xfce4-session -p /general/SaveOnExit -n -t bool -s false 2>/dev/null; rm -rf $UH/.cache/sessions/* 2>/dev/null; exec startxfce4" >/tmp/xfce.log 2>&1 &
  echo "桌面已启动（用户 $U）"
  sleep 3

  # 3.2 兜底：输入法没自启就手动拉起（必须带会话总线，否则会出现"进程在但打不出中文"）
  (
    sleep 8
    if ! pgrep -u "$U" -x fcitx5 >/dev/null 2>&1; then
      su - "$U" -c "export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$UH/.runtime PULSE_SERVER=$PULSE GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx; fcitx5 -d" >/dev/null 2>&1
    fi
  ) &
else
  echo "桌面已在运行（用户 $U）"
  # 会话已在跑时，保证 session.env 存在（老会话可能没有）
  [ -f "$SENV" ] || { printf 'export DBUS_SESSION_BUS_ADDRESS=unix:path=%s\nexport PULSE_SERVER=%s\n' "$BUS" "$PULSE" > "$SENV"; chown "$U:$U" "$SENV"; chmod 644 "$SENV"; }
  # 老会话的 session.env 可能缺 PULSE_SERVER，补上（幂等）
  grep -q PULSE_SERVER "$SENV" 2>/dev/null || printf 'export PULSE_SERVER=%s\n' "$PULSE" >> "$SENV"
fi

echo "SSH 端口 2222；桌面用户 $U（需要 root 时 sudo）"
