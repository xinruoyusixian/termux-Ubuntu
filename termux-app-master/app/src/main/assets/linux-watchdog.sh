#!/system/bin/sh
#
# 生命周期看门狗：App 进程消失后关闭整套 Linux 桌面栈。
#
# 为什么需要它：X server / pulseaudio / xfce4-session 都是通过 su 拉起的，
# 内核会把它们的 PPID 改成 1，从此与 App 进程彻底脱钩。Android 上没有可用的
# “父进程死亡信号”，只能由 root 独立进程轮询 /proc/<pid> 来判断。
#
# 由 LinuxLauncher.prepare() 以 root 启动，参数是 App 的 pid。

APP_PID="$1"
FILES=/data/data/com.termux/files
PIDFILE="$FILES/.linux-watchdog.pid"
CLOSING_FLAG="$FILES/.session-closing"
CLEANUP="$FILES/linux-cleanup.sh"
LOG=/data/data/com.termux/files/log/watchdog.log
# App uid 动态解析：不同设备/重装会变，禁止写死 10183
APP_UID=$(stat -c %u /data/data/com.termux/files 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(stat -c %u /data/data/com.termux 2>/dev/null)
[ -n "$APP_UID" ] || APP_UID=$(pm list packages -U com.termux 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1)
mkdir -p /data/data/com.termux/files/log; chown "$APP_UID:$APP_UID" /data/data/com.termux/files/log 2>/dev/null

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

[ -z "$APP_PID" ] && exit 1

# 同一时间只允许一个看门狗
if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && [ "$old" != "$$" ]; then
        kill -9 "$old" 2>/dev/null
    fi
fi
echo $$ > "$PIDFILE"

# 等桌面真的起来（最多 120 秒）
n=0
while [ -z "$(pgrep -f '[t]ermux-x11')" ]; do
    n=$((n + 1))
    if [ "$n" -gt 60 ]; then
        log "x server never came up, exit"
        rm -f "$PIDFILE"
        exit 0
    fi
    sleep 2
done
log "watching app pid $APP_PID"

while :; do
    if [ ! -d "/proc/$APP_PID" ]; then
        log "app process $APP_PID gone"
        break
    fi
    # 防止 pid 被别的进程复用后误判：Android 应用进程的 comm 就是包名
    comm=$(cat "/proc/$APP_PID/comm" 2>/dev/null)
    case "$comm" in
        "") ;;
        com.termux*) ;;
        *)
            log "pid $APP_PID reused by '$comm', exit"
            rm -f "$PIDFILE"
            exit 0
            ;;
    esac
    # 桌面已被用户手动关掉，看门狗失去意义
    if [ -z "$(pgrep -f '[t]ermux-x11')" ] && ! pgrep -f '[x]fce4-session' > /dev/null 2>&1; then
        log "desktop already stopped, exit"
        rm -f "$PIDFILE"
        exit 0
    fi
    sleep 4
done

# ---- App 已退出：关掉整套桌面栈 ----
# 先立旗：start-xfce.sh 可能还在启动过程中（它是独立进程，App 死了它照跑），
# 见到这个标记就会主动放弃，否则看门狗刚清完它又把桌面起回来。
: > "$CLOSING_FLAG"

# 真正的清理逻辑在 linux-cleanup.sh 里（独立脚本，避免 pkill -f 自匹配）
if [ -f "$CLEANUP" ]; then
    sh "$CLEANUP"
    log "cleanup rc=$? $(grep -c '/files/ubuntu/' /proc/mounts 2>/dev/null)"
else
    log "cleanup script missing!"
fi

rm -f "$PIDFILE"
log "session closed"
