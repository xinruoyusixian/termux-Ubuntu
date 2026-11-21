# Ubuntu-XFCE 平板版（基于 Termux 二次开发）

在安卓平板上运行的 Ubuntu 24.04 XFCE 桌面。基于 Termux App 源码二次开发，**X11 显示层与悬浮菜单已编译进同一个 APK**，支持 **root（chroot）/ 非 root（proot）双模式**，可一键启动完整 Ubuntu 桌面，终端与桌面双向切换。支持整系统备份/恢复，uid 跨设备自适应。

![版本](https://img.shields.io/badge/版本-v38-blue) ![平台](https://img.shields.io/badge/平台-Android%20arm64-green) ![模式](https://img.shields.io/badge/模式-chroot%20%2F%20proot-orange) ![默认](https://img.shields.io/badge/默认-非root-green)

## 目录结构

```
├── README.md                 本文件
├── docs/                     说明文档
│   ├── 使用与部署说明.md          安装、双模式、菜单、备份恢复、常见问题
│   ├── Ubuntu chroot 使用说明.md  原始使用文档
│   ├── 源码改动说明.md            相对上游 Termux 的全部改动 + 构建方法
│   └── 踩坑记录/                 主题文件（SELinux/proot/X11/音频/输入法/uid/隐私/备份恢复…）
├── releases/                 资源包（体积大，不入 git，见下方「下载」）
├── termux-app-master/        主工程源码（相对上游改动见 docs/源码改动说明.md）
├── termux-x11-master/        X11 参考源码（仅参考，无需单独安装）
└── tools/                    打包与部署辅助脚本
    ├── clean_rootfs.sh            发布前清理：删 Chrome profile/历史/缓存（保证发布包无个人信息）
    ├── unlock-pad.sh              一键解锁平板（自动唤醒/找密码框/输密码/重试，测试用）
    ├── pack_rootfs_on_device.sh   安卓设备上打包发布 rootfs（卸载挂载 + 排除运行时目录）
    ├── push_v36_api.py / upload_asset.py  GitHub API 推送源码 / 上传 Release 资源
    ├── pack_rootfs.py / pack_termux_pkgs.py / extract_debs.py / fetch_*.sh
    └── enter-ubuntu.sh / 进入Ubuntu.bat   手动进入 Ubuntu
```

## 下载

资源包体积大，不进 git，统一发布在 [Releases](https://github.com/xinruoyusixian/termux-Ubuntu/releases/tag/v38)：

| 文件 | 说明 | 大小 |
|------|------|------|
| `Ubuntu-XFCE-Tablet-v38.apk` | 平板版 App（含 X11 显示层，装这一个即可） | 48MB |
| `Ubuntu-XFCE-rootfs-v37.tar.gz` | Ubuntu XFCE 系统包，首次启动导入用（**不含任何个人信息**；v38 未改系统包，沿用 v37） | 816MB |
| `termux-pkgs.tar.gz` | Termux 基础包，非 root（proot）运行环境 | 11MB |
| `termux-app-0.118.3-arm64.apk` | 原版 Termux，初始化用 | 9MB |

> **不需要单独安装 Termux:X11**：显示层（Lorie）已内置在 APK 里。`Termux-X11.apk` 仅在配合原版 Termux 时才需要。
>
> v38 只改了 APK 侧（Java / assets 脚本），修复 root 模式浏览器/输入法缺失与非 root（proot）桌面黑屏；系统包内容与 v37 完全一致，无需重新导入。

## 功能特性

- **显示层内置**：X11 显示 + 悬浮菜单全部编译进同一 APK，无需另装显示端
- **默认非 root**：首次启动默认走非 root（proot）模式；终端菜单可一键切换，设备真没 root 时明确提示
- **root 检测健壮**：装了 KernelSU/Magisk 管理器不算 root，实测 `su -c id` 通过才算；su 丢失自动降级非 root，不再报 `No su program found`
- **uid 跨设备自适应**：不同设备 `com.termux` 的 uid 不同（10167/10183…），脚本运行时动态取，不写死；换设备/格式化重装不会黑屏
- **终端 ↔ 桌面双向切换**：X11 悬浮菜单「终端模式」回终端；终端抽屉「进入桌面」回桌面；**全局只保留一个 X11 窗口**
- **下载目录共享（双模式可用）**：菜单「挂载下载」走 Android「所有文件访问」授权，root 与非 root 都能读写 `/sdcard/Download`，桌面里位于 `/mnt/download`；用 App 私有暂存区同步，卸载/清数据不会穿透删除 SD 卡文件
- **整系统备份/恢复**：菜单「备份系统」把当前系统（**含你的个人信息**）打包到 `/sdcard/UbuntuXFCE/backups/`；「恢复备份」从备份包还原，包列表直接显示文件名、大小、时间。发布的系统包则**绝不含个人信息**
- **完整桌面栈**：XFCE4 + fcitx5 中文输入法 + PulseAudio + Chrome（自动处理沙箱/TMPDIR 兼容）
- **界面汉化**：Termux 原生菜单/设置/提示全部中文
- **看门狗**：「随 App 关闭」开关，App 进程消失自动回收整套桌面栈；「关闭系统」会同时杀掉 App

## 快速开始

1. 从 Releases 下载并安装 **`Ubuntu-XFCE-Tablet-v38.apk`**（本地保存名为 `Ubuntu-XFCE-平板版-v38.apk`，一个包即可）
2. 首次启动按提示导入 `Ubuntu-XFCE-rootfs-v37.tar.gz`（放到 `/sdcard/UbuntuXFCE/rootfs.tar.gz` 后打开 App）
3. 默认进入非 root 模式；如需 root，在终端抽屉点「模式：非ROOT」切换（设备需有可用 su，且已给 com.termux 授权）
4. 需要访问下载目录：点「挂载下载」→ 去系统设置开启「允许访问所有文件」→ 返回自动完成
5. 备份/恢复：终端抽屉或 X11 面板都有「备份系统」「恢复备份」两个按钮

详细步骤、菜单说明、备份恢复、常见问题见 **[docs/使用与部署说明.md](docs/使用与部署说明.md)**。

## 隐私说明

- **发布的系统包**打包前执行 `tools/clean_rootfs.sh`，删除 Chrome profile（History/Cookies/Login Data）、浏览器缓存、shell 历史、最近文件列表，**不含任何个人信息**
- **用户主动备份**是相反策略：`backup-rootfs.sh` 原样保留全部个人数据，方便迁移/留存，包放在公共存储，请妥善保管

## 重新构建

```bash
# 需要 JDK 17（含 javac）、Android SDK
export JAVA_HOME=<jdk路径>
cd termux-app-master
./gradlew :app:assembleRelease -PappVersionName=0.118.0-v38
# 产物: app/build/outputs/apk/release/termux-app_apt-android-7-release_universal.apk
```

> 版本号现在由 `-PappVersionName` 正确写入 `versionName`（v37 起支持；之前只认环境变量 `TERMUX_APP_VERSION_NAME`，导致 APK 一直显示 0.118.0）。

改 `assets/` 里的脚本或 Java/布局，只需重编 APK；**升级时脚本类 asset 会强制覆盖**，不会出现「改了脚本但老用户仍跑旧版」。系统包（rootfs）在**安卓设备上**打包（避免权限问题）：推送并执行 `tools/pack_rootfs_on_device.sh`，产物在设备 `/data/local/tmp/rootfs-release.tar.gz`，再 `adb pull` 回来。发布前务必先跑 `tools/clean_rootfs.sh`。

## 版本演进

| 版本 | 内容 |
|------|------|
| v10~v22 | 初始移植、X11 显示、音频、输入法、悬浮菜单 |
| v23~v28 | 终端↔桌面双向切换、菜单上下文化、非 root 适配 |
| v29~v31 | root 降级检测、SD 卡穿透防护、Chrome TMPDIR 修复 |
| v32 | 终端菜单图标 + 修复 X11 重复窗口 |
| v33 | 自动开启 allow-external-apps（root 音频插件报错修复） |
| v34 | uid 跨设备动态化，告别硬编码 |
| v35 | 关闭系统同时杀 App；rootfs 缓存清理 |
| v36 | 默认非 root + 终端菜单模式切换 + 非 root 挂载下载 + 整系统备份/恢复 + Termux 全菜单汉化 + root 实测加固（防卡死/多路径） |
| **v37** | **修复恢复功能四处致命 bug（su 位置参数、选包列表深色主题空白、桌面运行时 umount 护栏误拦、无 root 时 rm -rf 删残系统）+ 脚本不再依赖 /dev/null + 版本号与升级覆盖修复** |
| **v38** | **修复 root 模式桌面缺浏览器/输入法（启动幂等补桌面图标、默认浏览器、fcitx5 自启）+ 修复非 root（proot）桌面黑屏（APK 路径不依赖 pm、SELinux MLS 标签对齐、日志属主/目录加固、X server 失败快速报错）；双模式真机验证通过** |

## 重要文档

- **新会话 / 其他 AI 接力先读 [docs/接力交接文档.md](docs/接力交接文档.md)** —— 当前状态、未完成事项、环境约定、SOP、坑速查，自包含
- 遇到问题先看 **[docs/踩坑记录/00-总览与铁律.md](docs/踩坑记录/00-总览与铁律.md)** —— 代价最大的几条铁律（SELinux 穿透、pkill 自杀等）
- v37 新增 **[docs/踩坑记录/11-备份恢复与发布流程.md](docs/踩坑记录/11-备份恢复与发布流程.md)** —— su 传参、/dev/null、选包 UI、lazy umount 等
- v38 新增 **[docs/踩坑记录/12-v38桌面黑屏与入口丢失.md](docs/踩坑记录/12-v38桌面黑屏与入口丢失.md)** —— pm path 失败、SELinux MLS 标签、日志属主挡重定向
- 源码改了什么、怎么构建见 **[docs/源码改动说明.md](docs/源码改动说明.md)**
