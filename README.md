# Ubuntu-XFCE 平板版（基于 Termux 二次开发）

在小米平板 6 Pro（Android，已 root）上运行的**车机平板版 Ubuntu XFCE 桌面**。基于 Termux App 源码二次开发，内置 X11 显示层，支持 **root（chroot）/ 非 root（proot）双模式**一键启动完整 Ubuntu XFCE 桌面，终端与桌面双向切换。

![版本](https://img.shields.io/badge/版本-v28-blue) ![平台](https://img.shields.io/badge/平台-Android%20arm64-green) ![模式](https://img.shields.io/badge/模式-chroot%20%2F%20proot-orange)

## 目录结构

```
├── README.md                 本文件
├── docs/                     说明文档
│   ├── 使用与部署说明.md          安装、双模式切换、菜单、常见问题
│   ├── Ubuntu chroot 使用说明.md  原始使用文档
│   ├── 源码改动说明.md            相对上游 Termux 的全部改动 + 构建方法
│   └── 踩坑记录/                 11 个主题文件（SELinux/proot/X11/音频/输入法…）
├── releases/                 资源包（安装即用）
│   ├── Ubuntu-XFCE-平板版-v28.apk   最终交付版 App（48MB）
│   ├── Termux-X11-显示端.apk        X11 显示端
│   ├── termux-pkgs.tar.gz           Termux 基础包（proot 运行环境）
│   ├── termux-app_v0.118.3_arm64.apk 原版 Termux（初始化用）
│   └── Ubuntu-XFCE-rootfs.tar.gz    Ubuntu 系统包（902MB，导入用）
├── termux-app-master/        主工程源码（相对上游改动见 docs/源码改动说明.md）
├── termux-x11-master/        X11 参考源码
└── tools/                    打包与部署辅助脚本
    ├── pack_rootfs.py             rootfs 系统包打包
    ├── pack_termux_pkgs.py        termux-pkgs 打包（保执行位）
    ├── extract_debs.py            deb 解包合并
    ├── fetch_all_pkgs.sh 等       Termux 包下载
    ├── clean_rootfs.sh            rootfs 清理
    └── enter-ubuntu.sh / 进入Ubuntu.bat   手动进入 Ubuntu
```

## 功能特性

- **双模式**：root 走 chroot（18 个 bind mount），非 root 走 proot 伪装，`files/.force-noroot` 标记切换
- **终端 ↔ 桌面双向切换**：X11 悬浮菜单「终端模式」回终端；终端抽屉「进入桌面」回桌面，桌面栈后台保持
- **完整桌面栈**：XFCE4 + fcitx5 中文输入法 + PulseAudio 音频 + Chrome 浏览器（非 root 自动挂 `--no-sandbox` shim）
- **目录自包含**：所有数据在 App 私有目录（`files/ubuntu`），卸载即清
- **看门狗**：「系统跟随 App」，App 进程消失自动回收整套桌面栈

## 快速开始

1. 安装 `releases/Ubuntu-XFCE-平板版-v28.apk` 与 `releases/Termux-X11-显示端.apk`
2. 首次启动按提示导入 `releases/Ubuntu-XFCE-rootfs.tar.gz` 系统包
3. 非 root 模式需先放置 `files/.force-noroot` 标记（详见 docs/使用与部署说明.md）

详细步骤、菜单说明、常见问题见 **[docs/使用与部署说明.md](docs/使用与部署说明.md)**。

## 重新构建

```bash
# 需要 JDK 17（含 javac）、Android SDK
export JAVA_HOME=<jdk路径>
cd termux-app-master
./gradlew :app:assembleRelease -PappVersionName=0.118.0-vXX
# 产物: app/build/outputs/apk/release/termux-app_apt-android-7-release_universal.apk
```

打包系统包 / termux-pkgs 用 `tools/` 下对应脚本。

## 版本演进

| 版本 | 内容 |
|------|------|
| v10~v22 | 初始移植、X11 显示、音频、输入法、悬浮菜单 |
| v23 | 终端↔桌面双向切换 |
| v24 | 菜单右侧滑出 + 颜色区分 |
| v25 | 非 root（proot）适配 |
| v26 | 双模式全流程清理测试，修复 4 个重大 bug |
| v27 | 修复非 root 下浏览器/输入法"消失"（Chrome 沙箱 shim）|
| v28 | 菜单逻辑上下文化（当前版本）|

## 重要文档

- 遇到问题先看 **[docs/踩坑记录/00-总览与铁律.md](docs/踩坑记录/00-总览与铁律.md)** —— 四条代价最大的铁律（SELinux 穿透、pkill 自杀等）
- 源码改了什么、怎么构建见 **[docs/源码改动说明.md](docs/源码改动说明.md)**
