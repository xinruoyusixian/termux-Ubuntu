#!/system/bin/sh
# 修正 rootfs 的 SELinux MLS 类别（v38 新增，proot 黑屏根因之一）
#
# 现象：非 root（proot）模式启动桌面，proot 直接报
#         proot error: '/bin/bash' not found (root = .../ubuntu, cwd = /root, $PATH=(null))
#       而 /bin/bash 明明存在。
#
# 真因：系统包是由 root（kernel/ksu 域，**没有 MLS 类别**）解压或恢复的，
#       整盘文件标签是 u:object_r:app_data_file:s0；
#       而 App 进程跑在 untrusted_app:s0:c167,c256,c512,c768。
#       读权限按类别约束 → App 能 stat 但 open/readlink 全部 EACCES，
#       proot 连 /bin 这个符号链接都读不了，于是"文件不存在"。
#       （root 模式不受影响：chroot 用的是 root 域，可以读 s0 文件。）
#
# 解法：把 rootfs 全部标成 $FILES_DIR 的标签（即 App 自己的带类别标签）。
#       幂等：标签已一致就直接退出，几乎 0 开销。
#
# 用法：sh fix_selinux.sh [-f]   -f 强制重标
# 必须 root 执行（chcon 需要 MAC 管理权限）。
#
# 铁律 1 护栏：仍有挂载时 chcon -R 会穿透到真实 /dev、/proc → 直接中止。
FD=/data/data/com.termux/files
R=$FD/ubuntu
LOG=$FD/log/selinux.log
mkdir -p "$FD/log" 2>/dev/null
chmod 777 "$FD/log" 2>/dev/null
echo "=== $(date) fix_selinux start ===" >> "$LOG"

if [ ! -d "$R" ]; then
  echo "no rootfs at $R" >> "$LOG"
  exit 0
fi

CTX=$(stat -c %C "$FD" 2>/dev/null)
CUR=$(stat -c %C "$R" 2>/dev/null)
echo "app ctx = [$CTX]" >> "$LOG"
echo "rootfs  = [$CUR]" >> "$LOG"

if [ -z "$CTX" ]; then
  echo "无法获取 App 目录标签，放弃（非 Android/SELinux 环境？）" >> "$LOG"
  exit 1
fi

if [ "$CTX" = "$CUR" ] && [ "$1" != "-f" ]; then
  echo "标签一致，无需处理" >> "$LOG"
  exit 0
fi

# 护栏：rootfs 下不能有任何挂载（bind/tmpfs/proc…），否则 -R 会穿透
if grep -qs " $R/" /proc/mounts; then
  echo "ABORT：rootfs 仍有挂载，先卸载再 chcon" >> "$LOG"
  grep " $R/" /proc/mounts >> "$LOG"
  exit 2
fi

echo "开始 chcon -R（文件多，约需 1~2 分钟）" >> "$LOG"
chcon -R -h "$CTX" "$R" >> "$LOG" 2>&1
RC=$?
echo "chcon rc=$RC  new=[$(stat -c %C "$R" 2>/dev/null)]" >> "$LOG"
echo "=== $(date) fix_selinux end ===" >> "$LOG"
exit $RC
