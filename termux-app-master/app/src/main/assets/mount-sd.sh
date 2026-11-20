#!/system/bin/sh
# 由 App 菜单「挂载 SD」调用：把 Android 共享存储 / 外置 SD 卡 bind 进 Ubuntu chroot。
#
# 为什么必须由 App 侧调用而不是在桌面里点：
#   chroot 内的 alan 与 com.termux 是同一个内核 uid(10183)，
#   Android 的存储访问控制按 uid 判权限，所以「Termux 有没有存储权限」
#   直接决定了桌面里能不能打开这些目录。App 侧才拿得到这个权限状态。
#
# 约定：诊断输出走 stderr（App 将其重定向到 LOG），最终结果只写一行
#       "RESULT OK|NOPERM|ERR ..." 到 stdout，供 Java 解析。
export PATH=/system/bin:/system/xbin
R=/data/data/com.termux/files/ubuntu
mkdir -p /data/data/com.termux/files/log; chown 10183:10183 /data/data/com.termux/files/log 2>/dev/null
LOG=/data/data/com.termux/files/log/mount-sd.log

exec 2>>"$LOG"
echo "--- $(date) mount-sd" >&2

# 必须切到 init(pid 1) 的 mount namespace：
# App 进程有自己的 namespace，在那里 mount 出来的东西 chroot 侧看不到；
# 而且 /mnt/pass_through 这类系统挂载点只在全局 namespace 里可见。
if [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
  exec /system/bin/nsenter -t 1 -m -F -- /system/bin/sh "$0" "$@"
fi

if [ ! -d "$R/usr" ]; then
  echo "RESULT ERR no-rootfs"
  exit 2
fi

ismount() { grep -qs " $1 " /proc/mounts; }

# --- 让 chroot 内的桌面用户能真正读写挂进来的目录 ---
# 存储目录属组是 media_rw(1023)、权限 0770；alan 若不在该组，
# 桌面里能看见 /mnt/sdcard 却打不开（Permission denied）。
#
# 注意：必须把 alan 写进 media_rw 这一组的「成员」字段，
# 不能只写 alan 自己那行的第四列 —— glibc 的 initgroups(3) 是遍历
# 所有组、看每组的成员列表里有没有这个用户，用户自己那行写什么它不看。
GRP=$R/etc/group
if [ -f "$GRP" ]; then
  grep -q '^media_rw:' "$GRP" || echo 'media_rw:x:1023:' >> "$GRP"
  if ! grep -q '^media_rw:[^:]*:[0-9]*:[^:]*alan' "$GRP"; then
    awk -F: 'BEGIN{OFS=":"} $1=="media_rw"{ $4=($4==""?"alan":$4",alan") } {print}' \
      "$GRP" > "$GRP.new" && mv "$GRP.new" "$GRP"
  fi
fi

# --- bind 到目标点（幂等：源相同直接返回，源变了先卸再挂）---
bind_to() {
  _src=$1
  _dst=$2
  mkdir -p "$_dst" 2>/dev/null
  if ismount "$_dst"; then
    _cur=$(grep " $_dst " /proc/mounts | head -1 | awk '{print $1}')
    [ "$_cur" = "$_src" ] && return 0
    umount -l "$_dst" 2>/dev/null
    sleep 0.3
  fi
  mount -o bind "$_src" "$_dst" 2>/dev/null || return 1
  return 0
}

# --- 主存储：优先走 FUSE(/storage/emulated/0)，FUSE 异常时退回 pass_through ---
# pass_through 是真实文件系统直通，绕开 FUSE；root 可读，
# 但属组是 media_rw，所以上面那段加组是必须的。
pick_main() {
  for c in /storage/emulated/0 /mnt/runtime/default/emulated/0 \
           /mnt/pass_through/0/emulated/0 /mnt/user/0/emulated/0 /sdcard; do
    [ -d "$c" ] || continue
    if ls "$c" >/dev/null 2>&1; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

# --- 外置 SD 卡 / U 盘：/storage 下形如 XXXX-XXXX 的卷 ---
pick_ext() {
  for c in /storage/* /mnt/media_rw/*; do
    [ -d "$c" ] || continue
    b=${c##*/}
    case "$b" in
      emulated|self|enc_emulated|installer|androidwritable|pass_through|runtime|user|media_rw) continue ;;
    esac
    if ls "$c" >/dev/null 2>&1; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

MAIN=$(pick_main)
if [ -z "$MAIN" ]; then
  echo "RESULT NOPERM"
  exit 3
fi
echo "main source: $MAIN" >&2

if ! bind_to "$MAIN" "$R/mnt/sdcard"; then
  echo "RESULT ERR bind-main-failed"
  exit 4
fi

EXT=$(pick_ext)
if [ -n "$EXT" ]; then
  echo "ext source: $EXT" >&2
  bind_to "$EXT" "$R/mnt/external_sd" || { echo "warn: bind ext failed" >&2; EXT=; }
else
  # 没插卡时把旧挂载卸掉，避免桌面里留一个空目录让人误以为有卡
  ismount "$R/mnt/external_sd" && umount -l "$R/mnt/external_sd" 2>/dev/null
fi

# 桌面里放个入口，省得用户记路径
UH=$R/home/alan
if [ -d "$UH" ]; then
  ln -sfn /mnt/sdcard "$UH/SD卡" 2>/dev/null
  if [ -n "$EXT" ]; then
    ln -sfn /mnt/external_sd "$UH/外置SD卡" 2>/dev/null
  else
    rm -f "$UH/外置SD卡" 2>/dev/null
  fi
fi

echo "RESULT OK main=$MAIN ext=${EXT:-none}"
exit 0
