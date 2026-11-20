#!/system/bin/sh
# Ubuntu 24.04 chroot 进入脚本 — 小米 Pad 6 Pro / LineageOS
# 用法：在具备 root 的 shell 中执行： sh /data/linux/enter-ubuntu.sh

ROOT=/data/linux/ubuntu

# 必须在 root 下运行
if [ "$(id -u)" != "0" ]; then
  echo "请用 root 执行： su -c 'sh /data/linux/enter-ubuntu.sh'"
  exit 1
fi

# 若已挂载则跳过，避免重复挂载报错
mountpoint -q "$ROOT/dev"     || mount -t devtmpfs  devtmpfs  "$ROOT/dev"     2>/dev/null || mount --bind /dev  "$ROOT/dev"
mountpoint -q "$ROOT/dev/pts" || mount -t devpts    devpts    "$ROOT/dev/pts" 2>/dev/null || mount --bind /dev/pts "$ROOT/dev/pts"
mountpoint -q "$ROOT/proc"    || mount -t proc      proc      "$ROOT/proc"
mountpoint -q "$ROOT/sys"     || mount --bind /sys  "$ROOT/sys"
mountpoint -q "$ROOT/tmp"     || mount -t tmpfs     tmpfs     "$ROOT/tmp"

# 让 chroot 内能解析 DNS（部分 ROM 需要在挂载 proc 后再写一次）
cp /etc/resolv.conf "$ROOT/etc/resolv.conf.host" 2>/dev/null

echo "正在进入 Ubuntu 24.04 (arm64) chroot ..."
echo "退出请输入： exit"
echo "-------------------------------------------"

chroot "$ROOT" /bin/bash --login
