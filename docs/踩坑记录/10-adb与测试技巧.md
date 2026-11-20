# 10 - adb 与远程测试技巧

## 基础环境
- adb 路径：`D:\xiaomiPad6Pro\UotanToolbox_Windows_x64_3.7.2\Bin\platform-tools\adb.exe`，设备 ID c6ca45a5（1800x2880）。
- Git Bash 下 adb push 目标 `/data/...` 会被 MSYS 转成 Windows 路径 → `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"`；或 heredoc/base64 在设备上直接写文件。
- 多层引号嵌套（adb shell → su -c → sh -c）极易被吃掉：**复杂逻辑一律写成脚本文件 push 上去执行**，别硬拼引号。

## App 域测试方法论（核心技巧）
- 非 root 进程（uid 10183）跑的验证不能直接 adb su 模拟。可靠做法：
  1. 脚本 push 到 `/data/data/com.termux/files/`，`chown 10183:10183`、`chmod 755`
  2. 往 Termux 终端粘一行 `sh /data/data/com.termux/files/xxx.sh`
  3. **输出重定向写进脚本内部**（exec > out.txt 2>&1），不要依赖命令行重定向（会被 input text 转义搞坏）
- 原因：/sdcard App 域可能读不到；`adb input text` 对空格/重定向符号转义不可靠（空格用 %s）。

## UI 自动化定位
- `uiautomator dump /data/local/tmp/ui.xml` → pull 回本地 → grep resource-id + bounds → 算中心坐标 → `input tap X Y`。
- 比 `input text` 可靠得多（前台常被抢走是 input text 的老问题）。

## 判断"黑屏 ≠ 没跑"
- `screencap` 黑图时先查进程与 X 状态：`xlsclients`、`xwininfo -root -tree`（10x10 = 未映射窗口）。
- `dumpsys audio` 看播放器 muted 状态；logcat 里 `OpPlayAudio ... muted` = AudioHardening 静音（不是没声输出）。

## 调试优先级
- rootfs 内调试**优先 SSH**（ssh root@IP -p 2222，root/root；或 alan/alan），不要 adb 套 chroot（挂载环境不一致）。
- `xwininfo` / `xrefresh` / `fcitx5-remote` 都要在带正确环境变量的会话里执行。

## 设备状态坑
- 锁屏：`input keyevent` 解锁（密码 xw + 4 空格 → keyevent 52/51/62×4 + Enter）。
- A/B 双槽设备重启可能换槽 → 另一槽 boot 没 patch KSU → root 消失，重刷 boot。
- FUSE 断连（/sdcard Transport endpoint）重启 vold 无效 → 完整重启设备 + 解锁。
- 强杀/旋转救不回的 SurfaceView 渲染停止 → adb reboot。
- screencap 恒定黑图 + /data/data 目录名乱码 = 显示管线未恢复 / CE 存储未解锁，等物理确认。

## 权限授予清单（装机后）
```
pm grant com.termux READ/WRITE_EXTERNAL_STORAGE
appops set com.termux MANAGE_EXTERNAL_STORAGE allow
pm grant com.android.shell com.termux.permission.RUN_COMMAND
adb install -r -g termux-api.apk   # 一次性授予运行时权限
# 电池优化白名单：com.termux / com.termux.api / com.termux.x11
```
