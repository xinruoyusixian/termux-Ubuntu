# Ubuntu 24.04 chroot — 小米 Pad 6 Pro

已在设备上部署完成，性能为原生 arm64（共享 Android 内核，无 proot 翻译开销）。

## 设备上的位置

| 路径 | 说明 |
|---|---|
| `/data/linux/ubuntu/` | Ubuntu 根文件系统（958M，含基础工具） |
| `/data/linux/enter-ubuntu.sh` | 进入脚本（自动挂载 /dev /proc /sys 等） |
| `/data/linux/ubuntu-base.tar.gz` | 原始 rootfs 包（29M，可删以省空间） |

## 怎么进入

方式 A — 电脑上（推荐）：
1. 平板 USB 连电脑，授权 USB 调试
2. 双击 `进入Ubuntu.bat`
3. 在 adb shell 里执行：
   ```
   su
   sh /data/linux/enter-ubuntu.sh
   ```
4. 退出：输入 `exit` 回到 Android，再 `exit` 退出 adb shell

方式 B — 平板本机终端（如 Termux / KernelSU 管理器终端）：
```
su -c "sh /data/linux/enter-ubuntu.sh"
```

## 已安装内容

Ubuntu 24.04.5 LTS / aarch64 / 6 核
bash 5.2、python3 3.12、pip3、gcc/g++ 13、git、vim、htop、nano、curl、wget、net-tools、ping、ca-certificates

apt 源已换清华镜像（http），`apt update` / `apt install` 正常。

## 常用操作

```bash
apt update && apt install <软件>     # 装软件
df -h                                # 磁盘
nproc                                # CPU 核数
```

## 性能基准（参考）

- SHA256: ~1.99 GB/s
- 1 亿次整数循环: 1.7s
- 这是原生 arm64 二进制速度

## 说明

- 图形界面：本项目未配 GUI。若需要，可后续安装 VNC（如 `apt install tightvncserver` + 桌面环境）后从平板/电脑用 VNC 客户端连接。
- 与 Android 共享内核，因此无法加载 Linux 内核模块，systemd 也用不了（Ubuntu base 默认不用 systemd 也没问题）。
- 删 rootfs 包省空间：`su -c "rm /data/linux/ubuntu-base.tar.gz"`
