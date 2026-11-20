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
