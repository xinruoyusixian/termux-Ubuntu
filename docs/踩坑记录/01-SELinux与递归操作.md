# 01 - SELinux 与递归操作（bind mount 穿透）

## 坑 1：`chcon -R` 改坏真实 /dev/null → 全系统崩溃（本项目最大事故）
- **现象**：对 rootfs 执行 `chcon -R app_data_file files/ubuntu` 后，整个 Android 的 zygote 每 5 秒 SIGABRT："failed to open /dev/null"，系统无限崩溃。
- **真因**：rootfs 里 bind 着真实 `/dev`，`chcon -R` 穿透 bind mount，把**真机** `/dev/null` 的标签从 `u:object_r:null_device:s0` 改成了 `u:object_r:app_data_file:s0:c183,c256,c512,c768`。全系统进程打开 /dev/null 都被 SELinux 拒绝。
- **解法**：`chcon u:object_r:null_device:s0 /dev/null` 恢复。
- **铁律**：对 files/ubuntu 的任何 `-R` 操作前必须先卸载全部挂载（resetRootfs 里的护栏：`grep -qs '/files/ubuntu/' /proc/mounts` 有残留就 ABORT exit 9，绝不 rm）。

## 坑 2：`chcon -R` 不改符号链接 → /bin/bash not found
- **现象**：rootfs 全部 chcon 后，chroot 报 `'/bin/bash' not found`。
- **真因**：`/bin`、`/lib`、`ld-linux` 等是 symlink，`chcon -R` 不改 symlink 本身的标签 → symlink 标签为 `s0` 无类别，kernel 解析 PT_INTERP 时 ENOENT/EACCES。
- **解法**：`chcon -R -h`（-h = 影响符号链接本身）。

## 坑 3：`rm -rf` 穿透 bind mount 删了 /sdcard
- **现象**：清理历史残留目录时 `/sdcard` 内容被删，`/dev/pts` 的 devpts 也被卸掉，`adb reboot` 报 failed to create pty master。
- **真因**：历次卸载 com.termux 留下的 `==deleted==` 数据目录里，chroot 的挂载（dev/proc/sys/mnt/sdcard）一直没清，rm 从挂载点穿透删真实内容。
- **解法**：resetRootfs 流程改为：杀进程 → 逐个 umount（失败则 `-l` 懒卸载）→ `/proc/mounts` 有残留即 ABORT。`/dev/pts` 恢复：`mount -t devpts devpts /dev/pts -o mode=620`。

## 坑 4：同一挂载在 /proc/mounts 里有多个别名
- `/data/data`、`/data/user/0`、`/data_mirror` 三种路径指向同一挂载，卸载判断要全部检查，只查一个路径会误判"已卸载"。

## 坑 5：mountpoint 命令在 Android 不存在
- 判断挂载用 `grep -qs " $1 " /proc/mounts`，不要用 mountpoint。
