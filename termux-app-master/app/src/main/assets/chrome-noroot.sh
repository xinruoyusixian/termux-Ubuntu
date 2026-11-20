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
