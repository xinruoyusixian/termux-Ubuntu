#!/usr/bin/env python3
"""上传单个资源到 GitHub Release（文件名含中文也能正确处理）。
用法: GH_TOKEN=xxx python upload_asset.py <本地文件> <资源名> <release_id>
"""
import os, sys, json, urllib.request, urllib.error, urllib.parse

token = os.environ.get("GH_TOKEN", "")
local, name, rid = sys.argv[1], sys.argv[2], sys.argv[3]
url = (f"https://uploads.github.com/repos/xinruoyusixian/termux-Ubuntu/"
       f"releases/{rid}/assets?name={urllib.parse.quote(name)}")
size = os.path.getsize(local)
print(f"uploading {name} ({size/1024/1024:.1f} MB) ...", flush=True)

with open(local, "rb") as f:
    data = f.read()
req = urllib.request.Request(url, data=data, method="POST", headers={
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/octet-stream",
    "User-Agent": "v36-upload-script",
    "Content-Length": str(size),
})
try:
    with urllib.request.urlopen(req, timeout=1800) as resp:
        r = json.loads(resp.read().decode())
    print(f"OK {r['name']} size={r['size']} url={r['browser_download_url']}")
except urllib.error.HTTPError as e:
    print(f"HTTP {e.code}: {e.read().decode()[:500]}")
    sys.exit(1)
