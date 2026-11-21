# 07 - Chrome 浏览器（在此类设备上跑浏览器的完整结论）

## 浏览器选型结论（重要）
| 浏览器 | 结论 |
|---|---|
| apt 的 firefox / chromium-browser | Ubuntu 24.04 里都是 **snap 占位**，chroot/proot 用不了 |
| epiphany-browser (WebKitGTK) | 装得上但 WebKit 渲染进程 SIGSEGV/SIGTRAP（EXIT=132）；关 bwrap 沙箱 / 禁 JIT / 挂 /dev/shm 全试过无效 → **此设备跑不了 WebKit 系** |
| netsurf-gtk | 唯一能跑的传统浏览器，但无 JS、现代 HTTPS 站点白屏，仅应急 |
| **Google Chrome arm64 deb** | **最终方案**。`dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb` 直连可下；依赖只补 fonts-liberation / libnspr4 / libnss3 |

## Chrome 启动包装器（直接跑 /usr/bin/google-chrome-stable 会崩）
`/usr/local/bin/chrome` 必备 flags：
- `--no-sandbox`（root/伪装root + Android 内核不允许 userns）
- `--disable-gpu-sandbox`（不加则 GPU 进程 exit_code=5 → "GPU process isn't usable. Goodbye." 直接退出）
- `--test-type`（抑制"不受支持的命令行标记"黄条）
- `--hide-crash-restore-bubble`（抑制强杀后"要恢复页面吗"气泡）
- `--force-device-scale-factor=2`（高分屏 1x 字体极小）
- `--start-maximized --no-first-run --no-default-browser-check`
- `--user-data-dir=${HOME}/.config/google-chrome`（两模式共用配置）
- DBUS 为空自动 source `~/.runtime/session.env`（输入法，见 06 文件）

## /dev/shm
- Chrome 依赖共享内存，默认 256M 不够 → tmpfs 1G（写进 start-xfce.sh）。

## 非 root（proot）专项
- proot -0 伪装 uid=0 触发 Chrome root 沙箱拒绝 → proot bind shim 覆盖 `/usr/bin/google-chrome-stable`（详见 03 文件；shim 必须自包含防递归）。

## 默认浏览器接入 XFCE
- `~/.config/xfce4/helpers.rc` → `WebBrowser=chrome-browser`；`xdg-settings set default-web-browser chrome-browser.desktop`；chrome-browser.desktop（Type=X-XFCE-Helper）。
- 没配好时点链接会弹"打不开浏览器"模态框抢焦点（见 04 文件）。

## 坑（v42）："打开你的个人资料出了点问题，可能无法使用"

**真因**：Chrome 被 `kill -9` / 断电 / 切模式后，profile 里的 SQLite 数据库留下 `-journal` / `-wal` / `-shm` 锁文件，下次启动报 `Failed to open UKM database: database is locked`、`Failed to initialize database`，界面就提示"个人资料出了点问题"。同时 `SingletonLock` 可能指向已死 pid。

**修法（已固化，两模式都有）**：
- 启动前删 `$PROFILE/Default/*-journal|*-wal|*-shm`，并校验 `SingletonLock` 指向的 pid 是否存活，死了就清三件套。
- root 模式：`start-desktop.sh` 每次覆盖 `/usr/local/bin/chrome` 一份带自愈的 wrapper。
- proot 模式：`chrome-noroot.sh` 里同样的清理逻辑。

**顺带消除的噪音**（不是故障，别误判）：
- `Failed to connect to socket /run/dbus/system_bus_socket` → chroot/proot 内没有系统总线，启动 `dbus-daemon --system --fork` 即可（socket 权限 666）。
- `SSL handshake failed ... net_error -101` → 访问 Google 服务被墙，与 profile 无关，国内网络必然出现，忽略。
- `org.freedesktop.UPower was not provided` → 没有电源服务，无害。

## 坑（v42）：proot 下点浏览器"没反应"

**真因**：`Failed to create a ProcessSingleton for your profile directory ... Aborting now to avoid profile corruption.`
同一 profile 已有实例持锁（例如上一轮没退干净的 Chrome，或**你正在并行跑另一个 proot 实例做测试**），新实例会直接 abort 且**没有任何界面提示** —— 表现就是"点了没反应"。

**排查要点**：
- 桌面图标走 `/usr/local/bin/chrome`（rootfs 内通用 wrapper），它 exec 真实二进制，**不经过 `chrome-noroot.sh`**。所以排查 proot 问题时日志要写在 `/usr/local/bin/chrome` 里，而不是只写在 shim 里。
- wrapper 现在会把启动过程写进 `~/.runtime/chrome.log`（含 DISPLAY / DBUS / X11 socket / 二进制是否存在），超过 1MB 自动截断。
- 正常启动的日志特征：出现 renderer / gpu-process / network service 子进程，之后才是 SSL 报错（那些无害）。

**处理**：`pkill -9 chrome` 后重开；确认没有第二个 proot 实例在跑同一个 profile。
另：`Failed to read /proc/sys/fs/inotify/max_user_watches`、`zygote write: Broken pipe` 在 proot 下常见，不致命，Chrome 仍能起来。
