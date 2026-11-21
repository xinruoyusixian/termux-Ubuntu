#!/system/bin/sh
# 从用户自己的备份包恢复整个 Ubuntu 系统（含其个人信息）。
# 用法：sh restore-rootfs.sh <备份包绝对路径>
# 绝不自动碰 /sdcard 上的其它文件；只解压到 App 私有的 files/ubuntu。
SRC="$1"
FD=/data/data/com.termux/files
R=$FD/ubuntu
LOG=$FD/log/restore.log
mkdir -p "$FD/log" 2>>"$LOG"
: > "$LOG" || LOG=$FD/restore.log

say() { echo "$*" >> "$LOG"; echo "RESULT $*"; }

[ -n "$SRC" ] && [ -f "$SRC" ] || { say ERR "备份包不存在：$SRC"; exit 1; }

# 1) 先彻底停桌面并卸载运行时挂载，防止 rm -rf 穿透 bind mount 删掉宿主目录
pkill -x xfce4-session 2>>"$LOG"
pkill -x startxfce4 2>>"$LOG"
pkill -x xfwm4 2>>"$LOG"
pkill -x xfce4-panel 2>>"$LOG"
pkill -x xfdesktop 2>>"$LOG"
pkill -x fcitx5 2>>"$LOG"
pkill -x pulseaudio 2>>"$LOG"
pkill -f google-chrome 2>>"$LOG"
pkill -f chrome_crashpad 2>>"$LOG"
for i in 1 2 3; do
  p=$(pgrep -f '[t]ermux-x11' 2>>"$LOG")
  [ -z "$p" ] && break
  kill -9 $p 2>>"$LOG"
  sleep 1
done
sleep 1
# 必须用 -l (lazy)：从菜单点恢复时桌面还开着，dev tmpfs 处于 busy，
# 普通 umount 会失败，护栏就会把一次正常恢复误拦下来。多轮 + 每轮复查。
for pass in 1 2 3; do
  for m in dev/pts dev/shm dev mnt/download mnt/sdcard mnt/external_sd sys proc tmp run; do
    umount -l "$R/$m" 2>>"$LOG"
  done
  grep -qs '/files/ubuntu/' /proc/mounts || break
  sleep 1
done
# 护栏：只要还有挂载就绝不 rm -rf（曾因穿透删掉过 SD 卡文件）
if grep -qs '/files/ubuntu/' /proc/mounts; then
  say ERR "仍有挂载未卸载，为防穿透已取消恢复。详见 /proc/mounts"
  grep '/files/ubuntu/' /proc/mounts >> "$LOG" 2>>"$LOG"
  exit 9
fi

# 2) 清空旧系统再解压（保留当前 uid 自适应在 fix_perms 阶段处理）
rm -rf "$R" && mkdir -p "$R" || { say ERR "清理旧系统失败"; exit 1; }
/system/bin/tar xzpf "$SRC" -C "$R" 2>>"$LOG"
rc=$?
if [ "$rc" != 0 ]; then
  # 以 App 身份兜底（备份包属主通常是本 App，无需 root）
  /system/bin/tar xzf "$SRC" -C "$R" 2>>"$LOG"
  rc=$?
fi
if [ "$rc" != 0 ]; then
  say ERR "解压失败（tar exit $rc），详见 $LOG"
  exit 1
fi

# 3) 校正 uid / 权限 / setuid：不同设备 App uid 不同（不能写死 10183）
if [ -f "$FD/fix_perms.sh" ]; then
  sh "$FD/fix_perms.sh" >> "$LOG" 2>&1
fi
mkdir -p "$FD/log" 2>>"$LOG"
chmod 777 "$FD/log" 2>>"$LOG"

# 4) 重建就绪标记
mkdir -p "$R" 2>>"$LOG"
echo "restored from $SRC at $(date)" > "$R/.termux-linux-ready" 2>>"$LOG"

say OK "恢复完成：已从 $(basename "$SRC") 恢复系统，请重新进入桌面"
