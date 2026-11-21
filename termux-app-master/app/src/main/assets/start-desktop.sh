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

# 0.5) 系统总线：Chrome 启动时会连 /run/dbus/system_bus_socket，
#      chroot 内没有系统 dbus-daemon → Chrome 日志刷 "Failed to connect to socket"
#      大量错误虽不致命，但会拖慢启动并干扰 profile 初始化。
#      这里启动一个系统总线（幂等），socket 权限 666 让 alan 也能连。
mkdir -p /run/dbus 2>/dev/null
chown root:root /run/dbus 2>/dev/null
chmod 755 /run/dbus 2>/dev/null
if ! pgrep -x dbus-daemon >/dev/null 2>&1 || [ ! -S /run/dbus/system_bus_socket ]; then
  dbus-daemon --system --fork 2>/dev/null
  sleep 0.3
fi
chmod 666 /run/dbus/system_bus_socket 2>/dev/null

# 0.6) 清理 Android 命名空间残留的 fcitx5：
#      切换模式（root↔proot）后，旧模式的 fcitx5 可能仍留在 Android 原生命名空间
#      （root=/ 而非 chroot），它的 DISPLAY 与当前会话冲突，导致输入法不生效。
#      chroot 内的 start-desktop.sh 看不到外部进程，用 pkill -f 清理。
#      注意：这只在 root(chroot) 模式下执行，proot 版不需要（进程同命名空间）。
pkill -9 -x fcitx5 2>/dev/null
sleep 0.5

# 0.7) Chrome profile 自愈（数据库锁文件清理）：
#      Chrome 被强杀（kill -9 / 断电 / 模式切换）后，SQLite 数据库留下
#      -journal / -wal / -shm 锁文件，下次启动报 "database is locked"
#      → "打开个人资料出了点问题"。启动前清理这些锁文件。
PROFILE="$UH/.config/google-chrome"
if [ -d "$PROFILE/Default" ]; then
  find "$PROFILE/Default/" -name "*-journal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-wal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-shm" -delete 2>/dev/null
  # SingletonLock 指向死进程也清理
  if [ -L "$PROFILE/SingletonLock" ]; then
    _t=$(readlink "$PROFILE/SingletonLock" 2>/dev/null)
    _pid=${_t##*-}
    case "$_pid" in
      ''|*[!0-9]*) ;;
      *) [ -d "/proc/$_pid" ] || rm -f "$PROFILE/SingletonLock" "$PROFILE/SingletonCookie" "$PROFILE/SingletonSocket" 2>/dev/null ;;
    esac
  fi
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

# 1.5) Chrome 启动 wrapper 自愈（root 模式）：
#      rootfs 预装的 /usr/local/bin/chrome 缺少数据库锁文件清理逻辑，
#      Chrome 被强杀后 SQLite -journal/-wal/-shm 残留 → "打开个人资料出了点问题"。
#      这里每次启动覆盖一份带自愈的 wrapper（幂等），与 proot 版 chrome-noroot.sh 对齐。
if [ -x /opt/google/chrome/chrome ]; then
  cat > /usr/local/bin/chrome <<'CHROME_WRAPPER'
#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export SHELL=/bin/bash
export HOME=${HOME:-/home/alan}
[ -z "$DISPLAY" ] && export DISPLAY=:0
export PULSE_SERVER=${PULSE_SERVER:-tcp:127.0.0.1:4713}
export GTK_IM_MODULE=fcitx
export QT_IM_MODULE=fcitx
export XMODIFIERS=@im=fcitx
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ] && [ -r "$HOME/.runtime/session.env" ]; then
  . "$HOME/.runtime/session.env"
fi
# 诊断日志：桌面图标启动时 stdout/stderr 会被丢弃，出问题无从查起。
# 超过 1MB 自动截断，避免无限增长。
_LOG="${HOME}/.runtime/chrome.log"
mkdir -p "${HOME}/.runtime" 2>/dev/null
[ -f "$_LOG" ] && [ "$(stat -c %s "$_LOG" 2>/dev/null || echo 0)" -gt 1048576 ] && : > "$_LOG"
{
  echo "=== $(date '+%F %T') chrome launch ==="
  echo "HOME=$HOME DISPLAY=$DISPLAY"
  echo "DBUS=${DBUS_SESSION_BUS_ADDRESS:-<empty>}"
  echo "X11=$(ls /tmp/.X11-unix/ 2>/dev/null | tr '\n' ' ')"
  echo "binary=$([ -x /opt/google/chrome/chrome ] && echo yes || echo NO)"
} >> "$_LOG" 2>/dev/null
exec >>"$_LOG" 2>&1
PROFILE="${HOME}/.config/google-chrome"
# SingletonLock 自愈：锁指向死进程就清理
if [ -L "$PROFILE/SingletonLock" ]; then
  _t=$(readlink "$PROFILE/SingletonLock" 2>/dev/null)
  _pid=${_t##*-}
  case "$_pid" in
    ''|*[!0-9]*) _alive=0 ;;
    *) [ -d "/proc/$_pid" ] && grep -qi chrome "/proc/$_pid/comm" 2>/dev/null && _alive=1 || _alive=0 ;;
  esac
  if [ "$_alive" = 0 ]; then
    rm -f "$PROFILE/SingletonLock" "$PROFILE/SingletonCookie" "$PROFILE/SingletonSocket" 2>/dev/null
    echo "cleaned dead SingletonLock ($_pid)"
  fi
fi
# 数据库锁文件自愈：强杀后 -journal/-wal/-shm 残留 → "database is locked"
if [ -d "$PROFILE/Default" ]; then
  find "$PROFILE/Default/" -name "*-journal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-wal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-shm" -delete 2>/dev/null
fi
echo "exec chrome now"
exec /opt/google/chrome/chrome \
  --no-sandbox \
  --disable-gpu-sandbox \
  --test-type \
  --hide-crash-restore-bubble \
  --user-data-dir="${HOME}/.config/google-chrome" \
  --force-device-scale-factor=2 \
  --start-maximized \
  --no-first-run \
  --no-default-browser-check \
  "$@"
CHROME_WRAPPER
  chmod 755 /usr/local/bin/chrome 2>/dev/null
fi

# 2) 会话运行时目录（必须归该用户且 700，否则 fcitx5/dbus 会报权限错）
install -d -m 700 -o "$U" -g "$U" "$UH/.runtime"

# 2.01) 日志文件先建成 alan 属主：root 直接重定向新建出来的是 root:root，
#        切到非 root（proot）模式后 alan 既盖不了也删不掉，重定向失败 → 命令被跳过。
for lf in xfce.log dbus.log icons.log; do
  [ -f "$UH/.runtime/$lf" ] || : > "$UH/.runtime/$lf"
  chown "$U:$U" "$UH/.runtime/$lf" 2>/dev/null
  chmod 644 "$UH/.runtime/$lf" 2>/dev/null
done

# 2.0) 桌面图标 / 默认浏览器 / 输入法自启（幂等）。
#      rootfs 被重新导入或从备份恢复后 ~/Desktop 会变空，用户就"找不到浏览器和输入法"，
#      每次启动补一份，从此不会再丢。
#      日志写进 $UH/.runtime —— 写 /tmp 会在切换非 root 模式后变成 root 属主文件，
#      挡住 proot 会话的重定向（导致桌面组件一个都不启动）。
if [ -x /root/install-desktop-icons.sh ]; then
  /root/install-desktop-icons.sh "$UH" >> "$UH/.runtime/icons.log" 2>&1
fi

# 2.02) fcitx5 默认激活（幂等）：ActiveByDefault=False 时 fcitx5 启动即为未激活态，
#       用户第一次打字只出英文字母，必须手动点一次才切中文。强制改成 True。
#       必须在桌面/fcitx5 启动之前改，否则 fcitx5 退出时会用内存里的值覆盖回去。
FC="$UH/.config/fcitx5/config"
if [ -f "$FC" ]; then
  if grep -q '^ActiveByDefault=' "$FC"; then
    sed -i 's/^ActiveByDefault=.*/ActiveByDefault=True/' "$FC" 2>/dev/null
  else
    printf 'ActiveByDefault=True\n' >> "$FC" 2>/dev/null
  fi
  chown "$U:$U" "$FC" 2>/dev/null
fi

# 2.1) 终端默认工作目录 = ~（图标启动的终端会继承 xfdesktop 的 CWD，这里强制回 ~）
install -d -m 700 -o "$U" -g "$U" "$UH/.config/xfce4/terminal"
TRC="$UH/.config/xfce4/terminal/terminalrc"
if [ ! -f "$TRC" ]; then
  printf '[Configuration]\nuse-default-working-dir=true\nDefaultWorkingDir=%s\n' "$UH" > "$TRC"
else
  grep -q '^DefaultWorkingDir=' "$TRC" || printf 'use-default-working-dir=true\nDefaultWorkingDir=%s\n' "$UH" >> "$TRC"
fi
chown "$U:$U" "$TRC" 2>/dev/null
chmod 644 "$TRC" 2>/dev/null

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
  su - "$U" -c "dbus-daemon --session --fork --address=unix:path=$BUS --print-address=0" >"$UH/.runtime/dbus.log" 2>&1
  sleep 0.5
  printf 'export DBUS_SESSION_BUS_ADDRESS=unix:path=%s\nexport PULSE_SERVER=%s\n' "$BUS" "$PULSE" > "$SENV"
  chown "$U:$U" "$SENV" 2>/dev/null
  chmod 644 "$SENV"

  su - "$U" -c "export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$UH/.runtime PULSE_SERVER=$PULSE SHELL=/bin/bash TERM=xterm-256color GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx SDL_IM_MODULE=fcitx; unset XAUTHORITY; cd $UH; xfconf-query -c xfce4-session -p /general/SaveOnExit -n -t bool -s false 2>/dev/null; rm -rf $UH/.cache/sessions/* 2>/dev/null; exec startxfce4" >"$UH/.runtime/xfce.log" 2>&1 &
  echo "桌面已启动（用户 $U）"
  sleep 3

  # 3.2 兜底：输入法没自启就手动拉起（必须带会话总线，否则会出现"进程在但打不出中文"）
  #     拉起后强制设为中文（wowtype）并激活：fcitx5 默认是英文/关闭态，
  #     不改的话用户第一次打字只能出英文，得手动点一下才切中文。
  (
    sleep 8
    if ! pgrep -u "$U" -x fcitx5 >/dev/null 2>&1; then
      su - "$U" -c "export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$UH/.runtime PULSE_SERVER=$PULSE GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx; fcitx5 -d" >/dev/null 2>&1
      sleep 2
    fi
    su - "$U" -c "export DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS DISPLAY=:0 XDG_RUNTIME_DIR=$UH/.runtime; fcitx5-remote -s wowtype >/dev/null 2>&1; fcitx5-remote -o >/dev/null 2>&1" >/dev/null 2>&1
  ) &
else
  echo "桌面已在运行（用户 $U）"
  # 会话已在跑时，保证 session.env 存在（老会话可能没有）
  [ -f "$SENV" ] || { printf 'export DBUS_SESSION_BUS_ADDRESS=unix:path=%s\nexport PULSE_SERVER=%s\n' "$BUS" "$PULSE" > "$SENV"; chown "$U:$U" "$SENV"; chmod 644 "$SENV"; }
  # 老会话的 session.env 可能缺 PULSE_SERVER，补上（幂等）
  grep -q PULSE_SERVER "$SENV" 2>/dev/null || printf 'export PULSE_SERVER=%s\n' "$PULSE" >> "$SENV"
fi

# 2.02) 面板「中英切换」按钮（幂等）。fcitx5 托盘图标走 StatusNotifierItem，
#       本 rootfs 的 xfce4-panel 4.18.4 未带该宿主插件（noble 已移除
#       xfce4-statusnotifier-plugin，budgie-sntray-plugin 只给 Budgie），
#       托盘区永远空白 → 用面板启动器给可见入口。
#       必须放在桌面启动之后：xfconf-query 依赖 xfconfd，且必须由桌面用户执行。
#       /root 是 700，alan 无法执行，先复制到 /usr/local/bin。
if [ -x /root/install-im-toggle.sh ]; then
  cp -f /root/install-im-toggle.sh /usr/local/bin/install-im-toggle.sh 2>/dev/null
  chmod 755 /usr/local/bin/install-im-toggle.sh 2>/dev/null
  su - "$U" -c "export DISPLAY=:0 DBUS_SESSION_BUS_ADDRESS=unix:path=$BUS XDG_RUNTIME_DIR=$UH/.runtime; /usr/local/bin/install-im-toggle.sh $UH" >> "$UH/.runtime/icons.log" 2>&1
fi

echo "SSH 端口 2222；桌面用户 $U（需要 root 时 sudo）"
