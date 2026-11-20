#!/system/bin/sh
# 非 root 清理脚本：杀掉整套 proot 桌面栈
# 所有进程都是 App 自己的 uid(10183)，普通 kill 就够，不需要 root。
# 注意别用 pkill -f 加含 'termux' 的宽泛模式 —— 会把自己/App 内其它进程误杀。
export PATH=/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin

for round in 1 2 3; do
  pkill -9 -f '[s]tart-xfce-noroot.sh' 2>/dev/null
  pkill -9 -f '[s]tart-desktop-noroot.sh' 2>/dev/null
  pkill -9 -f '[p]root.*files/ubuntu' 2>/dev/null
  pkill -9 -x xfce4-session 2>/dev/null
  pkill -9 -f '[x]fce4' 2>/dev/null
  pkill -9 -f '[x]fdesktop' 2>/dev/null
  pkill -9 -x xfwm4 2>/dev/null
  pkill -9 -x xfconfd 2>/dev/null
  pkill -9 -f '[d]bus-daemon --session' 2>/dev/null
  pkill -9 -x fcitx5 2>/dev/null
  pkill -9 -x pulseaudio 2>/dev/null
  sleep 1
done

# X server（comm 是 main，只能按 cmdline 匹配）
for i in 1 2 3 4 5; do
  p=$(pgrep -f '[t]ermux-x11' 2>/dev/null)
  [ -z "$p" ] && break
  kill -9 $p 2>/dev/null
  sleep 1
done

rm -f /data/data/com.termux/files/usr/tmp/.X0-lock \
      /data/data/com.termux/files/usr/tmp/.X11-unix/X0 \
      /data/data/com.termux/files/.session-closing 2>/dev/null
echo "cleanup done (noroot)"
