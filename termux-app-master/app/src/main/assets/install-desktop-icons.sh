#!/bin/bash
# 幂等重建桌面必备入口（v38 新增）
#
# 为什么需要它：root 模式的桌面图标（浏览器 / 中英切换）是早前会话手工放进去的，
# 一旦 rootfs 被"导入系统包"覆盖、或用户恢复备份，~/Desktop 就空了 →
# 用户看到"系统里没有浏览器、也没有输入法"。本脚本每次启动桌面都补齐，
# 从此不会再丢；已存在且内容一致就不改动（尊重用户自己的调整）。
#
# 用法：install-desktop-icons.sh <家目录>      root 模式传 /home/alan，
#                                              proot 模式传 /root（bind 到 /home/alan）
H="${1:-$HOME}"
if [ ! -d "$H" ]; then
  echo "install-desktop-icons: 家目录不存在 $H"
  exit 1
fi
UID_OWN=$(stat -c %u "$H" 2>/dev/null)
[ -n "$UID_OWN" ] || UID_OWN=$(id -u)

D="$H/Desktop"
mkdir -p "$D" 2>/dev/null

# 写一个 .desktop：内容相同则跳过（保留用户手改），否则重写
_put() {
  _f="$1"; shift
  _tmp=$(mktemp) || return 0
  cat > "$_tmp"
  if [ -f "$_f" ] && cmp -s "$_tmp" "$_f"; then
    rm -f "$_tmp"
  else
    cat "$_tmp" > "$_f"
    rm -f "$_tmp"
    echo "重建：$_f"
  fi
  chmod 755 "$_f" 2>/dev/null
  chown "$UID_OWN:$UID_OWN" "$_f" 2>/dev/null
}

# 1) 桌面图标
_put "$D/浏览器.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=浏览器
Comment=Google Chrome 网页浏览器
Exec=/usr/local/bin/chrome
Icon=google-chrome
Terminal=false
StartupNotify=true
Categories=Network;WebBrowser;
EOF

_put "$D/中英切换.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=中英切换
Comment=一键切换中文/英文输入（fcitx5）
Exec=/usr/local/bin/im
Icon=input-keyboard
Terminal=false
StartupNotify=false
Categories=Utility;
EOF

_put "$D/终端.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=终端
Comment=命令行终端
Exec=xfce4-terminal
Icon=utilities-terminal
Terminal=false
StartupNotify=true
Categories=System;TerminalEmulator;
EOF

_put "$D/文件管理.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=文件管理
Comment=浏览文件（含 下载-上传 / 下载-镜像）
Exec=thunar
Icon=system-file-manager
Terminal=false
StartupNotify=true
Categories=System;FileManager;
EOF

# 图标需要可信任标记，xfdesktop 才会画（旧版 XFCE 用 metadata，新版本靠可执行位）
chmod 755 "$D"/*.desktop 2>/dev/null
chown "$UID_OWN:$UID_OWN" "$D"/*.desktop 2>/dev/null

# 2) 默认浏览器（缺了会导致点链接弹"打不开浏览器"的模态框抢焦点）
#    helper 优先放到用户自己的 ~/.local/share/xfce4/helpers —— proot 模式下 /usr/share
#    归 root:root 不可写，放这里两种模式都能生效。
HELP="$H/.local/share/xfce4/helpers"
mkdir -p "$HELP" 2>/dev/null
if [ ! -f "$HELP/chrome-browser.desktop" ]; then
  cat > "$HELP/chrome-browser.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=X-XFCE-Helper
X-XFCE-Binaries=chrome;google-chrome;google-chrome-stable;
X-XFCE-Category=WebBrowser
X-XFCE-Commands=/usr/local/bin/chrome;%B
X-XFCE-CommandsWithParameter=/usr/local/bin/chrome "%s";
Icon=google-chrome
Name=Chrome
Name[zh_CN]=谷歌浏览器
EOF
  chown "$UID_OWN:$UID_OWN" "$HELP/chrome-browser.desktop" 2>/dev/null
  chmod 644 "$HELP/chrome-browser.desktop" 2>/dev/null
fi
mkdir -p "$H/.config/xfce4" 2>/dev/null
HRC="$H/.config/xfce4/helpers.rc"
if ! grep -qs '^WebBrowser=' "$HRC" 2>/dev/null; then
  printf 'WebBrowser=chrome-browser\n' >> "$HRC"
  chown "$UID_OWN:$UID_OWN" "$HRC" 2>/dev/null
  chmod 644 "$HRC" 2>/dev/null
fi

# 3) 输入法自启（rootfs 里 /etc/xdg/autostart 没有 fcitx5.desktop，
#    只能靠启动脚本 sleep 8 秒兜底；补一份让它在会话里正常自启）
AD="$H/.config/autostart"
mkdir -p "$AD" 2>/dev/null
if [ ! -f "$AD/fcitx5.desktop" ]; then
  cat > "$AD/fcitx5.desktop" <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=Fcitx 5
Comment=启动输入法
Exec=fcitx5 -d
Icon=fcitx
Terminal=false
NoDisplay=true
X-GNOME-Autostart-enabled=true
EOF
  chown "$UID_OWN:$UID_OWN" "$AD/fcitx5.desktop" 2>/dev/null
  chmod 644 "$AD/fcitx5.desktop" 2>/dev/null
fi

echo "桌面入口已就绪（$H）"
