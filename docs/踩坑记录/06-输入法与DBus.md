# 06 - 输入法与 DBus（"只打英文"真凶）

## 坑 1（最重要）：Chrome 进程环境没有 DBUS_SESSION_BUS_ADDRESS
- **现象**：fcitx5 进程在、词典正常、但 Chrome 里打字只出英文字母（候选条不出现），xwininfo 里 Fcitx5 Input Window 1x1 未映射，`fcitx5-remote` 返回 0。
- **真因**：fcitx5 的 GTK 前端靠**会话总线**找输入法；Chrome 从"没继承总线变量的路径"启动（桌面图标以外、或旧 dbus-run-session 的随机地址 /tmp/dbus-XXXX 外部程序无从得知）→ 连不上总线 → 静默退化成纯英文。
- **修法（已固化）**：
  1. 会话总线**固定地址**：`dbus-daemon --session --fork --address=unix:path=/tmp/xfce-bus`
  2. 地址写进 `/home/alan/.runtime/session.env`（含 PULSE_SERVER），任何程序可 source
  3. `/usr/local/bin/chrome` 启动时 DBUS 为空自动 source session.env；~/.profile 同样 source
  4. 新增 `/usr/local/bin/im`（im / im cn / im en / im st）+ 桌面「中英切换」图标

## 坑 2：dbus-daemon 必须由桌面用户启动
- root 启动的 socket 属主 root，普通用户连不上 → fcitx5 直接退出。root 版用 `su - alan -c "dbus-daemon ..."`。

## 坑 3：XDG_RUNTIME_DIR 权限
- 必须**归该用户且 700**，否则 fcitx5/dbus 报权限错（`install -d -m 700 -o alan -g alan`）。proot 版用 /tmp/runtime-root。

## 坑 4：fcitx5 输入法兜底启动
- XFCE autostart 不可靠时，启动脚本 sleep 8 后 `pgrep -x fcitx5 || fcitx5 -d`（必须带会话总线，否则进程在但打不出中文）。
- `fcitx5-remote` 也必须在有 DBUS_SESSION_BUS_ADDRESS 的环境里执行，否则 "Failed to create dbus connection"。

## 坑 5：WowType（fcitx5 中文插件）
- 安装：fcitx5 5.1.7 + wowtype deb（GitHub 直连慢用 ghfast.top 镜像）；`wowtype-config --setup --yes` 自动写 profile。
- 候选条是它自己画的，fcitx5 classicui 改 Font 无效（恒定 34px 高），改字号要用 WowType 图形设置。

## 非 root（proot）下的补充
- fcitx5 在 proot 里能正常跑（无 root 检查）。之前"输入法没了"是 Chrome 打不开的连带观感（见 03 文件）。
- proot 内 HOME 必须显式 `/root`（bind 的 /home/alan），否则兜底脚本读不到 session.env。

## 坑 6（v42）：切模式后输入法不生效 / 面板托盘空白

**现象 A：进程在、也能连总线，但打不了字**
- 真因一：**另一模式的 fcitx5 残留**。切模式后旧的 fcitx5 可能还在 Android 原生命名空间（`/proc/<pid>/root` = `/`，不是 chroot），它和新实例抢 XIM 所有权，客户端可能连到哑实例。
- 真因二：**会话总线 socket 僵死**。dbus-daemon 被杀/重启后 socket 文件残留，fcitx5 连上却不回包 → `fcitx5-remote` 报 `Failed to get reply`。
- 排查命令：`readlink /proc/<fcitx-pid>/root` 看它在哪个命名空间；`fcitx5-remote` 返回 0/1/2 才算通。
- **铁律：验证必须用 `su - alan -c`**。用 root 身份跑 `fcitx5-remote` / `dbus-send` 一定失败（会话总线拒绝 root），别被这个假象带偏。
- 修法：两个 `start-xfce*.sh` 启动前 `pkill -9 -x fcitx5` + `pkill -9 -x chrome`；`start-desktop*.sh` 删 `$BUS` 后重建总线。

**现象 B：面板托盘区永远空白（以为没输入法）**
- 真因：fcitx5 托盘图标走 **StatusNotifierItem**，需要面板有 StatusNotifier 宿主。本 rootfs 的 `xfce4-panel 4.18.4` 只带 `libsystray.so`（XEmbed），**没有 `libstatusnotifier.so`**；Ubuntu noble 已移除 `xfce4-statusnotifier-plugin`，`budgie-sntray-plugin` 只提供 Budgie 版插件（`/usr/lib/budgie-desktop/plugins/...`），xfce4-panel 用不了。
- 结论：不要在这上面继续折腾装包（源是通的，清华镜像 7.5MB/s，是**包不存在**）。
- 修法（已固化）：`install-im-toggle.sh` 在面板上加一个「中英切换」启动器（`Exec=/usr/local/bin/im`），点一下切中/英。幂等：按 `plugin-ids` 逐个查 `items` 是否已有 `im-toggle.desktop`。
- 注意两点：① 必须**桌面启动之后**再调（xfconf-query 依赖 xfconfd）；② root 模式下 `/root` 是 700，alan 执行不了，要先 `cp` 到 `/usr/local/bin/`。
