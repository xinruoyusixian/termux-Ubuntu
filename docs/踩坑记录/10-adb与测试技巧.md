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
- 锁屏：**直接用 `bash tools/unlock-pad.sh`**（已脚本化并真机验证：自动找 adb、自动唤醒、等密码框、点聚焦、输密码、失败重试）。手动流程与坑见下方「一键解锁脚本」。
- A/B 双槽设备重启可能换槽 → 另一槽 boot 没 patch KSU → root 消失，重刷 boot。
- FUSE 断连（/sdcard Transport endpoint）重启 vold 无效 → 完整重启设备 + 解锁。
- 强杀/旋转救不回的 SurfaceView 渲染停止 → adb reboot。
- screencap 恒定黑图 + /data/data 目录名乱码 = 显示管线未恢复 / CE 存储未解锁，等物理确认。

## 一键解锁脚本（tools/unlock-pad.sh）

```bash
bash tools/unlock-pad.sh                                  # 默认密码 xw + 4 个空格
UNLOCK_PASS=abc UNLOCK_SPACES=0 bash tools/unlock-pad.sh  # 换密码/无空格
ADB_BIN=/path/to/adb bash tools/unlock-pad.sh             # 指定 adb
```

脚本做的事：找 adb（PATH 或其他常见安装路径）→ 已在桌面则直接退出（幂等）→ 循环等 `mWakefulness=Awake` → 按 `wm size` 计算上滑坐标 → dump 找 `passwordEntry` → 点击中心聚焦 → `input text 密码` + `keyevent 62`×N + `keyevent 66` → 校验 `mDreamingLockscreen` 与前台窗口，最多 3 轮，失败打印界面文字。

写成脚本前踩的 4 个坑：

1. **不能用 screencap 判断界面**：这台设备截图恒为黑图，"黑图 ≠ 没锁/没跑"。只信 `uiautomator dump` 与 `dumpsys window`。
2. **Git Bash 会转换设备路径**：`adb shell uiautomator dump /data/local/tmp/ui.xml` 里路径被改写成 Windows 路径，dump 静默失败。脚本内 `export MSYS_NO_PATHCONV=1`。
3. **密码框必须先 tap 聚焦**：直接 `input text` 大概率丢字或界面不动；先 tap 中心（如 `1439,723`）再输入。
4. **Dozing 状态下输入无效**：屏幕 AOD 时 `input text` 不生效还会浪费一次尝试；必须轮询到 `Awake` 再操作（第一版因此首轮失败、第二轮才成功）。
5. **`dumpsys window` 有多行 `mCurrentFocus`**：先输出 `mCurrentFocus=null` 再输出真实窗口，判断要用 `grep 'mCurrentFocus=Window{' | tail -1`，取第一行会把已解锁误判成锁屏。

## 权限授予清单（装机后）
```
pm grant com.termux READ/WRITE_EXTERNAL_STORAGE
appops set com.termux MANAGE_EXTERNAL_STORAGE allow
pm grant com.android.shell com.termux.permission.RUN_COMMAND
adb install -r -g termux-api.apk   # 一次性授予运行时权限
# 电池优化白名单：com.termux / com.termux.api / com.termux.x11
```
