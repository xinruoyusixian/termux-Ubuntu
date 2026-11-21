#!/system/bin/sh
# 由 App 菜单「挂载下载」调用：确保 chroot 内 /mnt/download 交换区就位（可写）。
#
# 零穿透架构（2026-11-20 修订）：
#   曾把整张 /sdcard 以 rw bind 进 chroot —— App 卸载/清数据时，系统 rm 整个
#   App 数据目录会穿透挂载点，把真实 SD 卡删空（已发生一次事故）。
#   现在挂载点是 App 私有目录内的「交换暂存区」(files/download-xfer)，
#   由 download-sync.sh 搬运器与真实 /sdcard/Download 双向同步：
#   穿透的最坏后果 = 暂存区被清（本来就该清的临时交换区），真实文件零风险。
#
# 约定：诊断输出走 stderr（App 将其重定向到 LOG），最终结果只写一行
#       "RESULT OK|NOPERM|ERR ..." 到 stdout，供 Java 解析。
export PATH=/system/bin:/system/xbin
R=/data/data/com.termux/files/ubuntu
FD=/data/data/com.termux/files
XFER=$FD/download-xfer
# App uid 动态解析：不同设备/重装会变，禁止写死 10183
APP_UID=$(stat -c %u "$FD" 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(stat -c %u /data/data/com.termux 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(pm list packages -U com.termux 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1)
mkdir -p "$FD/log"; chown "$APP_UID:$APP_UID" "$FD/log" 2>/dev/null
LOG=$FD/log/mount-sd.log

exec 2>>"$LOG"
echo "--- $(date) mount-download" >&2

# 必须切到 init(pid 1) 的 mount namespace：
# App 进程有自己的 namespace，在那里 mount 出来的东西 chroot 侧看不到。
if [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
  exec /system/bin/nsenter -t 1 -m -F -- /system/bin/sh "$0" "$@"
fi

if [ ! -d "$R/usr" ]; then
  echo "RESULT ERR no-rootfs"
  exit 2
fi

ismount() { grep -qs " $1 " /proc/mounts; }

# --- 旧的全卡挂载残留（老版本装过）全部卸掉 ---
for old in "$R/mnt/sdcard" "$R/mnt/external_sd"; do
  if ismount "$old"; then
    umount -l "$old" 2>/dev/null
    sleep 0.3
  fi
done

# --- 暂存区结构（App uid 可写；root 跑时修属主）---
mkdir -p "$XFER/out" "$XFER/in" "$R/mnt/download"
chmod -R 777 "$XFER" 2>/dev/null
chown -R "$APP_UID:$APP_UID" "$XFER" 2>/dev/null

if ! ismount "$R/mnt/download"; then
  if ! mount -o bind "$XFER" "$R/mnt/download" 2>/dev/null; then
    echo "RESULT ERR bind-failed"
    exit 4
  fi
fi

# 桌面里的入口（alan 家目录）
UH=$R/home/alan
if [ -d "$UH" ]; then
  ln -sfn /mnt/download/out "$UH/下载-上传" 2>/dev/null
  ln -sfn /mnt/download/in  "$UH/下载-镜像" 2>/dev/null
  rm -f "$UH/SD卡" "$UH/外置SD卡" 2>/dev/null
fi

# 搬运器没在跑就拉起（以 App uid 跑 —— 写 /sdcard/Download 需要
# com.termux 的存储权限，FUSE 按 uid 授权；root 跑反而会写坏属主）
if ! pgrep -f '[d]ownload-sync.sh' >/dev/null 2>&1; then
  if [ -f "$FD/download-sync.sh" ]; then
    chmod 755 "$FD/download-sync.sh" 2>/dev/null
    nohup su "$APP_UID" -c "sh $FD/download-sync.sh \"$XFER\" /storage/emulated/0/Download 5" \
      >/dev/null 2>&1 &
    sleep 0.5
  fi
fi

echo "RESULT OK xfer=$XFER"
exit 0
