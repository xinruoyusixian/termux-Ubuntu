#!/system/bin/sh
# 用户主动备份：把当前整个 Ubuntu 系统打包到 /sdcard/UbuntuXFCE/backups/
# 与发布包不同，这里是用户自己的系统，包含所有个人信息（浏览器 profile、登录、
# 文档、输入法词库等），原样保留，不做任何清理。
# 用 /system/bin/tar（toybox）：rootfs 内的 GNU tar 动态链接解释器在宿主视角不存在。
FD=/data/data/com.termux/files
R=$FD/ubuntu
OUT=/sdcard/UbuntuXFCE/backups
LOG=$FD/log/backup.log
mkdir -p "$FD/log" "$OUT" 2>/dev/null
: > "$LOG" 2>/dev/null || LOG=$FD/backup.log

say() { echo "$*" >> "$LOG"; echo "RESULT $*"; }

[ -d "$R" ] || { say ERR "系统未安装"; exit 1; }

# 1) 关掉正在运行的桌面/浏览器，避免打包时文件在变（tar 读到半截文件）
#    只杀 guest 用户态进程，Android 侧 X server 保留无妨。
pkill -x xfce4-session 2>/dev/null
pkill -x startxfce4 2>/dev/null
pkill -x xfwm4 2>/dev/null
pkill -x xfce4-panel 2>/dev/null
pkill -x xfdesktop 2>/dev/null
pkill -x thunar 2>/dev/null
pkill -x fcitx5 2>/dev/null
pkill -x pulseaudio 2>/dev/null
pkill -f google-chrome 2>/dev/null
pkill -f chrome_crashpad 2>/dev/null
sleep 2

# 2) 排除运行时挂载：/proc /sys /dev /tmp /mnt/download 都是宿主目录 bind 进来的，
#    打进去没有意义且可能把 Android 存储塞进包里（也会造成恢复时的穿透风险）。
#    --exclude 用相对 cd 后的路径（./proc 等）。
STAMP=$(date +%Y%m%d-%H%M%S)
F="$OUT/ubuntu-backup-$STAMP.tar.gz"
TMP="$FD/.ubuntu-backup.tmp.tar"
TMPGZ="$FD/.ubuntu-backup.tmp.gz"
rm -f "$TMP" "$TMPGZ"

cd "$R" || { say ERR "无法进入 $R"; exit 1; }
# 先落未压缩 tar，单独检查 tar 自己的退出码：
# 之前用 tar | gzip 管道，$? 取的是 gzip 的码，tar 因权限失败仍会误报成功，
# 生成「看着正常、实则残缺」的备份包。拆分后 tar 失败能被真正捕获。
/system/bin/tar -cf "$TMP" \
  --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp \
  --exclude=./mnt/download --exclude=./mnt/sdcard --exclude=./mnt/external_sd \
  --exclude=./.shm --exclude=./run \
  . 2>>"$LOG"
rc=$?
if [ "$rc" != 0 ] || [ ! -s "$TMP" ]; then
  rm -f "$TMP" "$TMPGZ"
  say ERR "打包失败（tar exit $rc），详见 $LOG"
  exit 1
fi
# 辅助判定：tar 若把权限被拒当致命错误会写 "tar: had errors"，这里也拦一下
if grep -q 'Permission denied' "$LOG" 2>/dev/null; then
  say WARN "打包过程中有个别文件无法读取（详见 $LOG），主体已打包"
fi

/system/bin/gzip -1 -c "$TMP" > "$TMPGZ" 2>>"$LOG"
rc=$?
rm -f "$TMP"
if [ "$rc" != 0 ] || [ ! -s "$TMPGZ" ]; then
  rm -f "$TMPGZ"
  say ERR "压缩失败（gzip exit $rc），详见 $LOG"
  exit 1
fi

mv -f "$TMPGZ" "$F" 2>/dev/null || { cp "$TMPGZ" "$F" && rm -f "$TMPGZ"; }
SZ=$(du -h "$F" 2>/dev/null | awk '{print $1}')
say OK "备份完成：$F（$SZ）"
