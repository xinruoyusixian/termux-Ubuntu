#!/bin/bash
# 在 chroot 内下载 Termux 的 deb 并解包，补进 bind 过来的 /mnt/termux-usr
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
BASE=https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
cd /tmp || exit 1
rm -rf /tmp/txpkg /tmp/txout
mkdir -p /tmp/txpkg /tmp/txout

echo "=== 下载索引 ==="
curl -s --max-time 60 "$BASE/dists/stable/main/binary-aarch64/Packages.gz" -o Packages.gz || { echo "索引下载失败"; exit 1; }
gunzip -f Packages.gz

echo "=== 解析依赖 ==="
python3 - <<'PY'
import re, os, sys
pkgs = {}
cur = {}
for line in open('/tmp/Packages', encoding='utf-8', errors='ignore'):
    if not line.strip():
        if cur.get('Package'): pkgs[cur['Package']] = cur
        cur = {}
        continue
    if line[0] in ' \t': continue
    if ':' in line:
        k, v = line.split(':', 1); cur[k] = v.strip()

want, queue = set(), ['termux-api']
while queue:
    name = queue.pop()
    if name in want: continue
    p = pkgs.get(name)
    if not p:
        print('缺失包定义:', name); continue
    want.add(name)
    deps = p.get('Depends', '')
    for d in deps.split(','):
        d = d.strip().split('|')[0].strip()
        d = re.sub(r'\(.*?\)', '', d).strip()
        d = d.split(':')[0].strip()   # 去掉 :any 之类
        if not d or d.startswith('$'): continue
        if d in ('libc', 'dpkg', 'termux-am'): continue
        queue.append(d)

lines = []
for n in sorted(want):
    p = pkgs[n]
    print(n, p.get('Version'), '->', p.get('Filename'))
    lines.append(p['Filename'])
open('/tmp/txlist.txt','w').write('\n'.join(lines)+'\n')
print('共', len(lines), '个包')
PY

[ -s /tmp/txlist.txt ] || { echo "无下载清单"; exit 1; }

echo "=== 下载 deb ==="
cd /tmp/txpkg || exit 1
while read -r f; do
  [ -z "$f" ] && continue
  n=$(basename "$f")
  [ -f "$n" ] || curl -s --max-time 120 "$BASE/$f" -O
  echo "$n $(stat -c%s "$n" 2>/dev/null)"
done < /tmp/txlist.txt

echo "=== 解包 ==="
for d in /tmp/txpkg/*.deb; do
  dpkg-deb -x "$d" /tmp/txout 2>/dev/null || echo "解包失败 $d"
done

echo "=== 合并到 Termux（只补缺失文件，不覆盖已有）==="
SRC=/tmp/txout/data/data/com.termux/files/usr
if [ -d "$SRC" ]; then
  cp -an "$SRC"/. /mnt/termux-usr/ 2>/dev/null
  echo "已补入"
else
  echo "解包目录不存在"; ls /tmp/txout
fi

echo "=== 检查 ==="
ls -la /mnt/termux-usr/bin/pulseaudio /mnt/termux-usr/bin/pactl 2>&1
