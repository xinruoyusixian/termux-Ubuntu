# 09 - Shell 与进程管理陷阱

## 坑 1：`pkill -f` 自匹配自杀（本项目踩了三次！）
- **原理**：`pkill -f` 匹配整条命令行。而 `su -c '...'`、后台脚本的父 shell 命令行里**包含脚本全文**——只要模式字符串出现在脚本任何地方，父 shell 就会被自己杀掉。
- **踩坑实例**：
  1. mountCmd 里 `pkill -f '[l]inux-watchdog'` → 匹配到含 "linux-watchdog.sh" 字面量的 su 父 shell → 整个会话 signal 15
  2. 测试脚本里 `pkill -f "chrome --no"` → 脚本文本在 su -c 命令行里被匹配 → 脚本被 SIGTERM
  3. `pkill -f termux-x11` → 自己的 adb shell 被杀
- **方括号技巧 `[t]ermux-x11` 也不够**：它能防"匹配 pkill 自己"，防不了"父 shell 命令行含路径字面量"。
- **可靠解法**：
  - 按 pid 文件 kill：`[ -f pidfile ] && kill -9 $(cat pidfile)`（看门狗方案）
  - 按进程名精确匹配：`pgrep -x chrome` / `pkill -x pulseaudio`（进程名不会出现在命令行文本里……注意 -x 匹配的是 comm，comm 有 15 字符限制）
  - /proc/<pid>/comm 陷阱：app_process 起的进程 comm 是 `main`，`pkill -x termux-x11` 永远匹配不到，此时只能 `pgrep -f '[t]ermux-x11'` 但要确认父链安全

## 坑 2：su 拉起的后台进程随会话死亡
- `su -c "xxx --daemonize"` 的进程挂在 su 会话下。**解法**：setsid + 前台运行（`setsid pulseaudio -n -F pa.pa --exit-idle-time=-1 --disallow-exit`），或 Java 侧用独立 su 进程拉看门狗（PPID 变 1）。

## 坑 3：看门狗误杀新会话
- 旧看门狗盯着已死的旧 App pid，新桌面一起来就被杀。prepare 时先按 pid 文件清残留看门狗。

## 坑 4：重复实例竞争
- 多次强杀后会残留重复 xfce4-panel → systray "notification area lost selection"；dbus 重复实例互抢端口（表现为连接被 reset）。启动前检查"已在运行就跳过"（幂等脚本）。

## 坑 5：dbus-run-session 的随机地址
- `dbus-run-session` 生成随机 /tmp/dbus-XXXX 地址，外部拉起的程序拿不到 → 改固定地址 `/tmp/xfce-bus` + session.env。

## 坑 6：shell 环境变量继承
- chroot 里必须显式 `export PATH=...`（否则 command not found）、`export HOME=/root`（否则 bash -l 不读 profile）、`SHELL/TERM`（见 04 文件）。

## 坑 7：toybox find 不支持 `-exec {} +`
- 改 `xargs -0` 或 `\;`。

## 坑 8：重定向先于脚本执行
- `sh script > log 2>&1` 的 log 文件在 spawn 时创建——目录不存在/没权限时脚本"看似没跑"。Java 端先 `mkdir -p` 再重定向。
