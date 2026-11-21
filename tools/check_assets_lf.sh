#!/bin/sh
# 检查 assets 里的 shell 脚本是否混入 CRLF。
#
# 为什么需要：Windows 上编辑文件（Python 文本模式 / 某些编辑器）会把换行写成 CRLF。
# Android 的 toybox sh 遇到 \r 会把它当成命令的一部分，报一堆莫名其妙的东西：
#   - "syntax error: unmatched 'if'"
#   - 变量里带 \r → 路径变成 /data/data/com.termux//.app-uid
#   - 明明文件在，却报 No such file or directory
# v42 踩过一次（改 uid 落盘时把 6 个脚本写成了 CRLF，root 模式直接起不来）。
#
# 用法：sh tools/check_assets_lf.sh [assets目录]
DIR="${1:-termux-app-master/app/src/main/assets}"
bad=0
for f in "$DIR"/*.sh; do
  [ -e "$f" ] || continue
  # 按字节数 0d（CR）——不能用 tr -dc '\r'：不同实现的转义解释不一致，
  # 会把普通字母 r 也数进去（v42 就被这个假警报坑了一次）。
  n=$(od -An -tx1 "$f" | tr -d ' \n' | grep -o '0d' | wc -l | tr -d ' ')
  if [ "${n:-0}" -gt 0 ]; then
    echo "CRLF! $f  ($n 个 CR)"
    bad=$((bad+1))
  fi
done
if [ "$bad" -gt 0 ]; then
  echo "发现 $bad 个文件含 CRLF，必须转成 LF 再打包"
  exit 1
fi
echo "OK: 所有 shell 脚本均为 LF"
