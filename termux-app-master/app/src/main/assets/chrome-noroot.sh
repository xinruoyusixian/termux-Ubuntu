#!/bin/bash
# Chrome 兼容 shim（仅非 root 会话使用）
#
# 问题：proot -0 会把进程伪装成 uid 0，Chrome 检测到后直接拒绝启动：
#   "Running as root without --no-sandbox is not supported. See https://crbug.com/638180."
#   表现为点桌面菜单里的浏览器图标毫无反应，看起来像"浏览器没了"。
#
# 方案：非 root 启动器（start-xfce-noroot.sh）用 proot bind 把本文件覆盖到
#   /usr/bin/google-chrome-stable。
# 注意：不能转发到 /usr/local/bin/chrome——它内部 exec 的正是
#   /usr/bin/google-chrome-stable（已被本 shim 覆盖），会无限递归。
# 所以这里自包含地复刻其环境后直接 exec 真实二进制 /opt/google/chrome/chrome。
#   root(chroot) 会话不挂这个 bind，/usr/bin/google-chrome-stable 保持原样。
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export SHELL=/bin/bash
export HOME=${HOME:-/root}
[ -z "$DISPLAY" ] && export DISPLAY=:0
export PULSE_SERVER=${PULSE_SERVER:-tcp:127.0.0.1:4713}
export GTK_IM_MODULE=fcitx
export QT_IM_MODULE=fcitx
export XMODIFIERS=@im=fcitx
# fcitx5 的 GTK 前端通过会话总线找输入法；从菜单启动可能没继承该变量
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ] && [ -r "$HOME/.runtime/session.env" ]; then
  . "$HOME/.runtime/session.env"
fi

# 诊断日志：从桌面图标启动时 stdout/stderr 会被丢弃，出问题完全无从查起。
# 这里把整个启动过程（含 Chrome 自身报错）追加到 ~/.runtime/chrome.log。
_LOG="${HOME}/.runtime/chrome.log"
mkdir -p "${HOME}/.runtime" 2>/dev/null
{
  echo "=== $(date '+%F %T') chrome launch ==="
  echo "HOME=$HOME DISPLAY=$DISPLAY"
  echo "DBUS=${DBUS_SESSION_BUS_ADDRESS:-<empty>}"
  echo "binary=/opt/google/chrome/chrome exists=$([ -x /opt/google/chrome/chrome ] && echo yes || echo NO)"
} >> "$_LOG" 2>/dev/null
exec >>"$_LOG" 2>&1

# 关键自愈：强制杀进程/断电/清数据后，Chrome 会残留 SingletonLock 符号链接。
# 该链接指向 "localhost-<pid>"，若对应进程已不存在，新启动的 Chrome 会误以为
# 已有实例在跑，把 URL 转给那个死进程后自己静默退出 —— 表现就是"点图标没反应"。
# 这里检测锁指向的 pid 是否还活着，是死锁就清掉三件套再启动。
PROFILE="${HOME}/.config/google-chrome"
if [ -L "$PROFILE/SingletonLock" ]; then
  _t=$(readlink "$PROFILE/SingletonLock" 2>/dev/null)
  _pid=${_t##*-}
  case "$_pid" in
    ''|*[!0-9]*) _alive=0 ;;
    *) [ -d "/proc/$_pid" ] && grep -qi chrome "/proc/$_pid/comm" 2>/dev/null && _alive=1 || _alive=0 ;;
  esac
  if [ "$_alive" = 0 ]; then
    rm -f "$PROFILE/SingletonLock" "$PROFILE/SingletonCookie" "$PROFILE/SingletonSocket" 2>/dev/null
  fi
fi

# 数据库锁文件自愈：Chrome 被强杀后 SQLite 留下 -journal/-wal/-shm 锁文件，
# 下次启动报 "database is locked" → "打开个人资料出了点问题可能无法使用"。
# 启动前清理这些锁文件（Chrome 会自动重建）。
if [ -d "$PROFILE/Default" ]; then
  find "$PROFILE/Default/" -name "*-journal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-wal" -delete 2>/dev/null
  find "$PROFILE/Default/" -name "*-shm" -delete 2>/dev/null
fi

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
