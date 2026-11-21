#!/system/bin/sh
# 启动时在终端直接打印诊断信息（v41），不用再去翻日志文件。
# 参数：$1 = root 或 proot
MODE="$1"
FD=/data/data/com.termux/files
R="$FD/ubuntu"
LOG="$FD/log"
U=$(stat -c %u "$FD" 2>/dev/null)

# 记录本次终端会话真正按哪个模式启动（供模式切换后校验；v41）
echo "$MODE" > "$FD/.boot-mode" 2>/dev/null
chmod 666 "$FD/.boot-mode" 2>/dev/null

# v42: 版本号由 APK 运行时写入，不再硬编码（以前每发一版都要手改，常漏改）
VER=$(cat "$FD/.app-version" 2>/dev/null)
[ -n "$VER" ] || VER="(未知)"

echo ""
echo "============================================================"
echo " Ubuntu XFCE 平板版 $VER"
echo "------------------------------------------------------------"
echo " 启动模式 : $MODE"
echo " 启动时间 : $(date 2>/dev/null)"
echo " App UID  : ${U:-?}"
if [ -x "$R/bin/bash" ]; then
  echo " 系统包   : 就绪 (/bin/bash 可执行)"
else
  echo " 系统包   : 异常！$R/bin/bash 不可执行（检查导入/SELinux 标签）"
fi
echo " 桌面日志 : $LOG/desktop-start.log"
echo " 音频日志 : $LOG/audio.log"
echo " X11 日志 : $LOG/x11.log"
echo "------------------------------------------------------------"
echo " 桌面正在后台启动，稍候点右上角 ≡ →「进入桌面」查看。"
echo " 看日志：cat $LOG/desktop-start.log"
echo "============================================================"
echo ""
