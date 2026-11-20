#!/system/bin/sh
# 清理 rootfs 里的测试痕迹与缓存（保留功能，删除可再生成的缓存/测试数据）
R=/data/data/com.termux/files/ubuntu
[ -d "$R/home/alan" ] || { echo "rootfs missing"; exit 1; }

before=$(du -sk "$R/usr" "$R/opt" "$R/home" "$R/root" "$R/var" 2>/dev/null | awk '{s+=$1} END{print s}')

echo "== 1. 停掉用户态进程（保留 Android 侧 X server）=="
pkill -x xfce4-session 2>/dev/null
pkill -x startxfce4 2>/dev/null
pkill -x xfwm4 2>/dev/null
pkill -x xfce4-panel 2>/dev/null
pkill -x xfdesktop 2>/dev/null
pkill -x thunar 2>/dev/null
pkill -x fcitx5 2>/dev/null
pkill -f google-chrome 2>/dev/null
pkill -f chrome_crashpad 2>/dev/null
pkill -x dbus-daemon 2>/dev/null
sleep 2

echo "== 2. Chrome 用户数据（发布包绝不能带个人浏览记录/Cookie/密码/会话）=="
# 整个 profile 删除，不是只删 Cache —— Local State / Default/History / Cookies /
# Login Data / Preferences 里都有个人信息，Chrome 下次启动会自建干净 profile。
rm -rf "$R/root/.config/google-chrome" "$R/root/.cache/google-chrome"
rm -rf "$R/home/alan/.config/google-chrome" "$R/home/alan/.cache/google-chrome"
# 历次调试留下的临时 profile 目录，一并清掉
rm -rf "$R/root/.config"/chrome-clean "$R/root/.config"/chrome-fresh-* 2>/dev/null
rm -rf "$R/home/alan/.config"/chrome-clean "$R/home/alan/.config"/chrome-fresh-* 2>/dev/null
rm -rf "$R/root/.config/chromium" "$R/root/.cache/chromium"
rm -rf "$R/home/alan/.config/chromium" "$R/home/alan/.cache/chromium"
# 最近打开的文件列表也属于个人痕迹
rm -f "$R/home/alan/.local/share/recently-used.xbel" "$R/root/.local/share/recently-used.xbel" 2>/dev/null

echo "== 3. 已弃用浏览器的缓存 =="
rm -rf "$R/home/alan/.cache/netsurf" "$R/home/alan/.cache/epiphany"
rm -rf "$R/home/alan/.config/netsurf" "$R/home/alan/.config/epiphany" 2>/dev/null

echo "== 4. apt 缓存与索引 =="
rm -rf "$R/var/cache/apt/archives"/*.deb
rm -rf "$R/var/cache/apt/archives/partial"/*
rm -rf "$R/var/lib/apt/lists"/*
mkdir -p "$R/var/lib/apt/lists/partial" "$R/var/cache/apt/archives/partial"

echo "== 5. 通用缓存 =="
find "$R/home/alan/.cache" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; 2>/dev/null
find "$R/root/.cache" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; 2>/dev/null
rm -rf "$R/home/alan/.local/share/Trash"/* 2>/dev/null
rm -rf "$R/root/.local/share/Trash"/* 2>/dev/null
rm -rf "$R/home/alan/.thumbnails"/* "$R/root/.thumbnails"/* 2>/dev/null

echo "== 6. 文档与 man（不影响运行）=="
rm -rf "$R/usr/share/doc"/* 2>/dev/null
rm -rf "$R/usr/share/man"/* 2>/dev/null
rm -rf "$R/usr/share/info"/* 2>/dev/null

echo "== 7. 日志与临时 =="
rm -rf "$R/var/log"/*.log "$R/var/log/apt"/* "$R/var/log/journal"/* 2>/dev/null
rm -rf "$R/tmp"/* 2>/dev/null
rm -rf "$R/var/tmp"/* 2>/dev/null

echo "== 8. shell 历史与会话残留 =="
rm -f "$R/home/alan/.bash_history" "$R/root/.bash_history" 2>/dev/null
rm -f "$R/home/alan/.xsession-errors" "$R/root/.xsession-errors" 2>/dev/null
rm -rf "$R/home/alan/.cache" "$R/home/alan/.config/xfce4/session" 2>/dev/null
mkdir -p "$R/home/alan/.cache"

echo "== 9. python / pip 缓存 =="
rm -rf "$R/root/.cache"/* "$R/home/alan/.cache"/* 2>/dev/null
rm -rf "$R/root/.local/lib"/*/__pycache__ 2>/dev/null

echo "== 10. 校验关键文件仍在 =="
for f in /usr/bin/xfce4-session /usr/bin/fcitx5 /opt/google/chrome/chrome /usr/bin/python3 /home/alan/Desktop; do
  [ -e "$R$f" ] && echo "OK  $f" || echo "MISS $f"
done

after=$(du -sk "$R/usr" "$R/opt" "$R/home" "$R/root" "$R/var" 2>/dev/null | awk '{s+=$1} END{print s}')
echo "== 清理前 $(($before/1024))M -> 清理后 $(($after/1024))M，回收 $((($before-$after)/1024))M =="
