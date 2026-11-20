#!/system/bin/sh
# 下载交换区搬运器：暂存区 <-> 真实 /sdcard/Download 双向同步。
#
# 目录约定：
#   XFER/out  —— 桌面把要导出的文件放这里，搬运器 mv 到真实 Download（同名自动改名）
#   XFER/in   —— 真实 Download 的只读镜像（cp -u 增量复制），桌面从这里取外部文件
#
# 为什么不直接 bind 真实 Download：
#   bind 挂载点位于 App 数据目录内，App 卸载/清数据时系统 rm 会穿透挂载点
#   删除 bind 目标的真实文件（整卡 bind 曾把 SD 卡删空）。暂存区方案下，
#   穿透最坏只清暂存区（临时交换区），真实文件零风险。
#
# 语义（刻意简单，不做删除同步，避免误删）：
#   - out 里的文件搬运成功后从 out 消失，出现在 Download
#   - in 是 Download 的增量镜像；桌面里删除 in 中的文件会在下轮被重新拷回
#
# 用法: sh download-sync.sh <暂存目录> <真实Download> <轮询秒数>
XFER=$1
DL=$2
IV=${3:-5}
[ -d "$XFER" ] || exit 1
mkdir -p "$XFER/out" "$XFER/in" "$DL" 2>/dev/null

while true; do
  # --- 出向：out/* -> Download（mv 优先，失败退 cp+rm；同名加时间戳）---
  for f in "$XFER/out"/*; do
    [ -f "$f" ] || continue
    b=${f##*/}
    ext=${b##*.}
    base=${b%.*}
    [ "$base" = "$b" ] && ext=""          # 无扩展名
    if [ -e "$DL/$b" ]; then
      nb="${base}_$(date +%Y%m%d%H%M%S)"
      [ -n "$ext" ] && nb="$nb.$ext"
      mv -f "$f" "$DL/$nb" 2>/dev/null || { cp -f "$f" "$DL/$nb" 2>/dev/null && rm -f "$f" 2>/dev/null; }
    else
      mv -f "$f" "$DL/$b" 2>/dev/null || { cp -f "$f" "$DL/$b" 2>/dev/null && rm -f "$f" 2>/dev/null; }
    fi
  done
  # --- 入向：Download -> in/（cp -u 只拷比镜像新的）---
  for f in "$DL"/*; do
    [ -f "$f" ] || continue
    b=${f##*/}
    cp -u "$f" "$XFER/in/$b" 2>/dev/null
  done
  sleep "$IV"
done
