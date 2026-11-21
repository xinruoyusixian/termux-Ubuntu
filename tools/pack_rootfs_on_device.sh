#!/system/bin/sh
# 在安卓设备上打包发布用 rootfs（约 2GB）。用 /system/bin/tar（toybox），
# 因为 rootfs 内 GNU tar 的动态链接解释器在宿主视角不存在。
R=/data/data/com.termux/files/ubuntu
OUT=/data/local/tmp/rootfs-release.tar.gz
LOG=/data/local/tmp/pack-rootfs.log
: > "$LOG"

echo "== 1) 确保桌面进程已停 ==" >> "$LOG"
for p in xfce4-session startxfce4 xfwm4 xfce4-panel xfdesktop thunar fcitx5 pulseaudio; do
  pkill -x "$p" 2>>"$LOG"
done
pkill -f google-chrome 2>>"$LOG"
pkill -f chrome_crashpad 2>>"$LOG"
pgrep -f '[t]ermux-x11' | xargs -r kill -9 2>>"$LOG"
sleep 2

echo "== 2) 卸载运行时挂载 ==" >> "$LOG"
for pass in 1 2 3; do
  for m in dev/pts dev/shm dev mnt/download mnt/sdcard mnt/external_sd sys proc tmp run; do
    umount -l "$R/$m" 2>>"$LOG"
  done
  grep -qs '/files/ubuntu/' /proc/mounts || break
  sleep 1
done
sleep 1
# 护栏：还有挂载就不打包，避免把宿主目录打进去
if grep -qs '/files/ubuntu/' /proc/mounts; then
  echo "ABORT: 仍有挂载未卸载" >> "$LOG"
  grep '/files/ubuntu/' /proc/mounts >> "$LOG"
  echo "RESULT ABORT_MOUNTS"
  exit 9
fi

echo "== 3) 打包（排除运行时目录）==" >> "$LOG"
cd "$R" || exit 1
rm -f "$OUT"
/system/bin/tar -cf - \
  --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp \
  --exclude=./mnt/download --exclude=./mnt/sdcard --exclude=./mnt/external_sd \
  --exclude=./.shm --exclude=./run \
  . 2>>"$LOG" | /system/bin/gzip -1 > "$OUT"
rc=$?
echo "tar/gzip exit=$rc" >> "$LOG"
ls -la "$OUT" >> "$LOG"
gzip -t "$OUT" 2>>"$LOG"
echo "gzip_t_rc=$?" >> "$LOG"
echo "RESULT DONE rc=$rc size=$(stat -c %s "$OUT" 2>>"$LOG")"
