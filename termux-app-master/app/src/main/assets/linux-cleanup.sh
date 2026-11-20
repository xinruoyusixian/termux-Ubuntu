#!/system/bin/sh
#
# 杀干净整套 Linux 桌面栈，并卸载所有 chroot 挂载。
#
# 为什么必须是独立脚本、不能用 su -c "..." 内联：
#   内联时 su 与 sh 的命令行里含有 `[s]tartxfce4` 这类字符串，
#   而 `pkill -f '[x]fce4'` 的正则会匹配到其中的 "xfce4" 子串，
#   结果命令把自己所在的 shell 一起杀掉（exit 137）。
#   作为脚本文件运行，进程命令行只有脚本路径，不会自匹配。
#
# 退出码：0 表示挂载已清零，2 表示仍有挂载残留。

FILES=/data/data/com.termux/files
R="$FILES/ubuntu"

# X server 的 /proc/<pid>/comm 是 "main"（--nice-name 只改 cmdline），只能按命令行匹配
for i in 1 2 3 4 5 6 7 8; do
    p=$(pgrep -f '[t]ermux-x11')
    [ -z "$p" ] && break
    kill -9 $p 2>/dev/null
    sleep 1
done

# 三轮：xfce4-session 被 kill 后 dbus-run-session 可能把它重新拉起来。
# 必须按命令行匹配 —— xfce4-power-manager 这类名字超过 15 字符，comm 会被内核
# 截断成 "xfce4-power-man"，按 -x 全名匹配不到。
for round in 1 2 3; do
    pkill -9 -f '[s]tart-desktop.sh'  2>/dev/null
    pkill -9 -f '[d]bus-run-session'  2>/dev/null
    pkill -9 -f '[s]tartxfce4'        2>/dev/null
    pkill -9 -f '[x]fce4'             2>/dev/null
    pkill -9 -f '[x]fdesktop'         2>/dev/null
    pkill -9 -f '[x]fwm4'             2>/dev/null
    pkill -9 -f '[x]fconfd'           2>/dev/null
    pkill -9 -f '[f]citx5'            2>/dev/null
    pkill -9 -f '[p]ulseaudio'        2>/dev/null
    pkill -9 -f '[d]bus-daemon'       2>/dev/null
    pkill -9 -f '[s]sh-agent'         2>/dev/null
    sleep 1
done

# 兜底：任何 root 还指着 rootfs 的进程
for p in /proc/[0-9]*; do
    r=$(readlink "$p/root" 2>/dev/null)
    case "$r" in
        *com.termux/files/ubuntu*) kill -9 "${p#/proc/}" 2>/dev/null ;;
    esac
done
sleep 1

# 卸载。同一个挂载会以 /data/data、/data/user/0、/data_mirror 三种别名出现在
# /proc/mounts 里，只有其中一个路径能真正 umount，另外两个返回 Invalid argument。
#
# 注意 umount -l 是"延迟卸载"：调用返回 0 之后，只要还有进程/内核对象持有引用，
# 该挂载仍会留在 /proc/mounts 里，等引用释放才真正消失（实测需要几十秒）。
# 所以这里先整体 detach 一遍，再轮询等待计数归零，而不是反复 umount。
grep '/files/ubuntu/' /proc/mounts 2>/dev/null | awk '{print $2}' | while read m; do
    umount -f "$m" 2>/dev/null || umount -l "$m" 2>/dev/null
done

for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    n=$(grep -c '/files/ubuntu/' /proc/mounts 2>/dev/null)
    [ "$n" = "0" ] && break
    # 还有残留就再 detach 一次（新别名可能出现），然后等引用释放
    grep '/files/ubuntu/' /proc/mounts 2>/dev/null | awk '{print $2}' | while read m; do
        umount -l "$m" 2>/dev/null
    done
    sleep 1
done

rm -f "$R/tmp/.X0-lock" "$R/tmp/.X11-unix/X0" 2>/dev/null

left=$(grep -c '/files/ubuntu/' /proc/mounts 2>/dev/null)
echo "mounts=$left"
[ "$left" = "0" ] || exit 2
exit 0
