#!/system/bin/sh
# 修复 rootfs 属主与权限
# 场景：解压时以非 root + umask 077 会导致全盘 10183:10183 600/700，
#       此时 sudo/passwd/dbus 全部失效，桌面起不来。
# 注意：toybox 的 find 不支持 "-exec ... {} +"，必须用 -print0 | xargs -0
R=/data/data/com.termux/files/ubuntu
FD=/data/data/com.termux/files
LOG=$FD/log/fix_perms.log
mkdir -p "$FD/log"
: > "$LOG"
[ -d "$R/etc" ] || { echo "no rootfs"; exit 1; }
# 当前设备 App uid（跨设备/重装会变，一律动态取，禁止写死 10183）
# 解析顺序：files 目录属主 → 数据目录属主 → pm 输出的 uid: 字段
# v42: 优先读 App 落盘的 .app-uid（Process.myUid()）。App 自己最清楚自己的 uid，
# 比外面 stat/pm 反推可靠，也不受「卸载重装换 uid」影响。
APP_UID=$(tr -dc '0-9' < "/data/data/com.termux/files/.app-uid" 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(stat -c %u /data/data/com.termux/files 2>>"$LOG")
[ -n "$APP_UID" ] || APP_UID=$(stat -c %u /data/data/com.termux 2>>"$LOG")
[ -n "$APP_UID" ] || APP_UID=$(pm list packages -U com.termux 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1)
if [ -z "$APP_UID" ]; then
  echo "错误：无法确定 com.termux 的 uid，修复中止" | tee -a "$LOG" >&2
  exit 1
fi
# 同步 rootfs 内 alan 账号 uid 与当前设备一致（否则 su - alan 失败 → 黑屏）
sed -i "s/^alan:x:[0-9]*:[0-9]*:/alan:x:$APP_UID:$APP_UID:/" "$R/etc/passwd" 2>>"$LOG"
sed -i "s/^alan:x:[0-9]*:/alan:x:$APP_UID:/" "$R/etc/group" 2>>"$LOG"

echo "== 0. 必备运行时目录 =="
# 顶层目录必须归 app 用户：tar -p 以 root 解压会把 "." 还原成 root:root，
# 导致 App 无法写就绪标记/部署脚本（EACCES）
chown "$APP_UID:$APP_UID" "$R" 2>>"$LOG"
chmod 755 "$R" 2>>"$LOG"
mkdir -p "$R/tmp" "$R/tmp/.X11-unix" "$R/run" "$R/run/dbus" "$R/run/sshd" "$R/dev" "$R/dev/pts" "$R/dev/shm" "$R/proc" "$R/sys" 2>>"$LOG"
chmod 1777 "$R/tmp" 2>>"$LOG"
chmod 777 "$R/tmp/.X11-unix" 2>>"$LOG"
chmod 755 "$R/run" "$R/run/dbus" "$R/run/sshd" 2>>"$LOG"
chmod 755 "$R/dev" 2>>"$LOG"
chmod 1777 "$R/dev/shm" 2>>"$LOG"

echo "== 1. 卸载 bind mount（防止 chown 穿透到 Android /dev 与 Termux usr）=="
# 同一挂载会以 /data/data、/data/user/0、/data_mirror 三种别名出现在
# /proc/mounts 里，只有其中一个路径能 umount 成功，其余返回 Invalid argument，
# 因此反复遍历直到计数归零。
for i in 1 2 3 4 5; do
  n=$(grep -c "/files/ubuntu/" /proc/mounts 2>>"$LOG")
  [ "$n" = "0" ] && break
  grep "/files/ubuntu/" /proc/mounts | awk '{print $2}' | while read m; do
    umount -f "$m" 2>>"$LOG" || umount -l "$m" 2>>"$LOG"
  done
  sleep 1
done
still=$(grep -c "/files/ubuntu/" /proc/mounts 2>>"$LOG")
if [ "$still" != "0" ]; then
  echo "!! 仍有 $still 个挂载未卸载，中止（避免改坏 Android /dev）"
  grep "/files/ubuntu/" /proc/mounts
  exit 1
fi

echo "== 2. 系统目录归 root:root =="
chown -R 0:0 "$R/usr" "$R/etc" "$R/opt" "$R/var" "$R/boot" "$R/srv" "$R/media" "$R/root" "$R/run" "$R/data" 2>>"$LOG"
for d in bin sbin lib lib64 lib32 libx32 bin.usr-is-merged sbin.usr-is-merged lib.usr-is-merged; do
  [ -e "$R/$d" ] && chown -hR 0:0 "$R/$d" 2>>"$LOG"
done
chown -R 0:0 "$R/home" 2>>"$LOG"

echo "== 3. 用户主目录归 alan($APP_UID) =="
chown -R "$APP_UID:$APP_UID" "$R/home/alan" 2>>"$LOG"

echo "== 4. 恢复权限位（目录 755 / 无执行位文件 644 / 可执行 755）=="
for d in usr etc opt var boot srv media home root run data; do
  [ -d "$R/$d" ] || continue
  find "$R/$d" -type d -print0 2>>"$LOG" | xargs -0 -r chmod 755 2>>"$LOG"
  find "$R/$d" -type f ! -perm -u+x -print0 2>>"$LOG" | xargs -0 -r chmod 644 2>>"$LOG"
  find "$R/$d" -type f -perm -u+x -print0 2>>"$LOG" | xargs -0 -r chmod 755 2>>"$LOG"
done

echo "== 5. 敏感文件 =="
chown 0:42 "$R/etc/shadow" "$R/etc/gshadow" 2>>"$LOG"
chmod 640 "$R/etc/shadow" "$R/etc/gshadow" 2>>"$LOG"
chmod 600 "$R/etc/shadow-" "$R/etc/gshadow-" 2>>"$LOG"
chmod 440 "$R/etc/sudoers" 2>>"$LOG"
chown 0:0 "$R/etc/sudoers" 2>>"$LOG"
chmod 755 "$R/etc/sudoers.d" 2>>"$LOG"
chmod 440 "$R"/etc/sudoers.d/* 2>>"$LOG"
chown 0:0 "$R"/etc/sudoers.d/* 2>>"$LOG"
chmod 600 "$R"/etc/ssh/ssh_host_*_key 2>>"$LOG"
chmod 644 "$R"/etc/ssh/ssh_host_*_key.pub 2>>"$LOG"
chown 0:0 "$R/etc/sudo.conf" 2>>"$LOG"

echo "== 5.1 alan 免密 sudo（桌面上的 root 终端/清理工具不再卡密码）=="
mkdir -p "$R/etc/sudoers.d" 2>>"$LOG"
printf 'alan ALL=(ALL:ALL) NOPASSWD:ALL\n' > "$R/etc/sudoers.d/alan" 2>>"$LOG"
chown 0:0 "$R/etc/sudoers.d/alan" 2>>"$LOG"
chmod 440 "$R/etc/sudoers.d/alan" 2>>"$LOG"

echo "== 6. 恢复 setuid / setgid =="
for f in usr/bin/sudo usr/bin/su usr/bin/passwd usr/bin/chsh usr/bin/chfn usr/bin/newgrp \
         usr/bin/gpasswd usr/bin/mount usr/bin/umount usr/bin/ping usr/bin/pkexec \
         usr/bin/chage usr/bin/expiry usr/lib/openssh/ssh-keysign; do
  [ -e "$R/$f" ] && { chown 0:0 "$R/$f"; chmod 4755 "$R/$f"; }
done
[ -e "$R/usr/lib/dbus-1.0/dbus-daemon-launch-helper" ] && chmod 4754 "$R/usr/lib/dbus-1.0/dbus-daemon-launch-helper"
[ -e "$R/usr/lib/policykit-1/polkit-agent-helper-1" ] && chmod 4754 "$R/usr/lib/policykit-1/polkit-agent-helper-1"
for f in usr/bin/sg usr/bin/wall usr/bin/ssh-agent; do
  [ -e "$R/$f" ] && chmod 2755 "$R/$f"
done

echo "== 7. 特殊目录 =="
chown 0:0 "$R/root" 2>>"$LOG"; chmod 700 "$R/root" 2>>"$LOG"
chmod 1777 "$R/tmp" "$R/var/tmp" 2>>"$LOG"
[ -d "$R/var/mail" ] && { chown 0:0 "$R/var/mail"; chmod 2775 "$R/var/mail"; }
[ -d "$R/var/spool/cron/crontabs" ] && { chown 0:0 "$R/var/spool/cron/crontabs"; chmod 1730 "$R/var/spool/cron/crontabs"; }
[ -d "$R/var/log" ] && chown 0:0 "$R/var/log"

echo "== 8. 修复工具与桌面图标 =="
chmod 755 "$R"/usr/local/bin/ub-* 2>>"$LOG"
chown 0:0 "$R"/usr/local/bin/ub-* 2>>"$LOG"
chmod 755 "$R"/home/alan/Desktop/*.desktop 2>>"$LOG"
chown "$APP_UID:$APP_UID" "$R"/home/alan/Desktop/*.desktop 2>>"$LOG"
mkdir -p "$R/home/alan/.runtime" 2>>"$LOG"
chown -R "$APP_UID:$APP_UID" "$R/home/alan/.runtime" 2>>"$LOG"
chmod 700 "$R/home/alan/.runtime" 2>>"$LOG"

echo "== 9. SELinux 标签对齐（否则非 root 模式读不到 rootfs） =="
# 以 root 解压出来的文件标签只有 app_data_file:s0，没有 App 的 MLS 类别，
# App 进程（untrusted_app:s0:c…）能 stat 但 open 被拒 → proot 报 '/bin/bash' not found。
# fix_selinux.sh 内部自带「仍有挂载则中止」护栏，标签已一致会立刻退出。
if [ -f "$FD/fix_selinux.sh" ]; then
  sh "$FD/fix_selinux.sh" >>"$LOG" 2>&1 && echo "标签已对齐" || echo "标签修复失败，见日志"
fi

echo "== 结果 =="
for f in usr/bin/sudo usr/bin/su usr/bin/passwd etc/shadow etc/sudoers etc/sudoers.d/alan root home/alan usr/bin tmp; do
  printf "%-22s " "$f"; stat -c "%u:%g %a" "$R/$f" 2>>"$LOG" || echo missing
done
echo "== fix_perms done =="
