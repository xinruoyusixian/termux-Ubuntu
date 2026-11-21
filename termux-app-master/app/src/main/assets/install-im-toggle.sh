#!/bin/bash
# 在 XFCE 面板上加「中英切换」启动器（幂等）
#
# 为什么需要：fcitx5 的托盘图标走 StatusNotifierItem，需要面板有
# StatusNotifier 宿主插件。本 rootfs 的 xfce4-panel 4.18.4 未带
# libstatusnotifier.so（Ubuntu noble 已移除 xfce4-statusnotifier-plugin，
# budgie-sntray-plugin 只提供 Budgie 版），所以托盘区永远是空的。
# 这里改用面板「启动器」给一个可见入口，点一下即切换中/英。
#
# 用法：install-im-toggle.sh <家目录>   （root 模式 /home/alan，proot 模式 /root）

H="${1:-$HOME}"
APP="$H/.local/share/applications"
export DISPLAY="${DISPLAY:-:0}"
[ -r "$H/.runtime/session.env" ] && . "$H/.runtime/session.env"

# 1) 图标：主题里没有 input-keyboard（hicolor 精简），直接用自绘 SVG 绝对路径，
#    否则面板按钮会显示成空白方块，用户以为"没有输入法"。
ICON="$H/.local/share/icons/im-toggle.svg"
mkdir -p "$(dirname "$ICON")" 2>/dev/null
cat > "$ICON" <<'EOF'
<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48">
  <rect x="2" y="2" width="44" height="44" rx="9" fill="#2d7ff9"/>
  <text x="24" y="34" font-family="Noto Sans CJK SC, WenQuanYi Zen Hei, sans-serif"
        font-size="26" font-weight="bold" fill="#ffffff" text-anchor="middle">中</text>
</svg>
EOF
chmod 644 "$ICON" 2>/dev/null

# 2) 桌面文件（幂等覆盖，内容固定）
mkdir -p "$APP" 2>/dev/null
cat > "$APP/im-toggle.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=中英切换
Comment=切换 fcitx5 中文/英文输入
Exec=/usr/local/bin/im
Icon=$ICON
Terminal=false
Categories=Utility;
EOF
chmod 644 "$APP/im-toggle.desktop" 2>/dev/null

# 2) 面板里已经加过就不再重复（避免每次启动叠加一堆按钮）
#    注意：xfconf-query -p /plugins 取不到子项，必须按 plugin-ids 逐个查
for id in $(xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids 2>/dev/null | grep -E '^[0-9]+$'); do
  if xfconf-query -c xfce4-panel -p "/plugins/plugin-$id/items" 2>/dev/null | grep -qx "im-toggle.desktop"; then
    echo "中英切换按钮已存在（plugin-$id）"
    exit 0
  fi
done

IDS=$(xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids 2>/dev/null | grep -E '^[0-9]+$')
[ -z "$IDS" ] && { echo "面板配置读取失败，跳过"; exit 0; }
MAX=$(echo "$IDS" | sort -n | tail -1)
NEW=$((MAX + 1))

xfconf-query -c xfce4-panel -p "/plugins/plugin-$NEW" -t string -s launcher --create 2>/dev/null
xfconf-query -c xfce4-panel -p "/plugins/plugin-$NEW/items" -t string -s im-toggle.desktop --create 2>/dev/null
ARGS=""
for i in $IDS $NEW; do ARGS="$ARGS -t int -s $i"; done
xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids $ARGS 2>/dev/null
echo "已添加中英切换按钮（plugin-$NEW）"
