#!/system/bin/sh
# 启动 Termux 侧 PulseAudio（AAudio 输出），供 chroot 通过 TCP 4713 使用
# PATH 必须含 /system/bin：全新安装的 Termux 还没有 bootstrap，
# usr/bin 里没有 setsid/ps/mkdir/grep，只有 Android 自带的 toybox 才有。
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
export PATH=/system/bin:/system/xbin:$P/bin
export LD_LIBRARY_PATH=$P/lib
export HOME=$H
export TMPDIR=$P/tmp
export PULSE_RUNTIME_PATH=$H/.pulse-rt
LOG=$H/pa.log
mkdir -p "$PULSE_RUNTIME_PATH" "$P/tmp" 2>/dev/null

if ps -A 2>/dev/null | grep -q "[p]ulseaudio"; then
  echo "pulseaudio 已在运行"
else
  rm -f "$PULSE_RUNTIME_PATH/pid"
  # setsid: 脱离当前会话，避免 su/adb 会话退出时被一起带走
  setsid pulseaudio -n -F "$H/pa-user.pa" \
    --log-target=file:"$LOG" --exit-idle-time=-1 --disallow-exit \
    < /dev/null > /dev/null 2>&1 &
  sleep 4
fi

echo "=== ps ==="
ps -A 2>/dev/null | grep "[p]ulseaudio"
echo "=== info ==="
pactl info 2>&1 | grep -E 'Server Name|Default Sample'
echo "=== sinks ==="
pactl list short sinks 2>&1
echo "=== tcp 4713 ==="
awk 'NR>1 {print $2}' /proc/net/tcp 2>/dev/null | grep -ci ':1269' | xargs echo "listen_count="
echo "=== log tail ==="
tail -6 "$LOG" 2>/dev/null
