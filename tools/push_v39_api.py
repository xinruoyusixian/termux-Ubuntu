#!/usr/bin/env python3
"""GitHub API 推送 v39 提交 + 创建 Release + 上传三资源。

绕开本机 git push 的 CONNECT 502 / credential 失效问题。
用法: GH_TOKEN=xxx python tools/push_v39_api.py
"""
import os, json, base64, subprocess, urllib.request, urllib.error, urllib.parse

TOKEN = os.environ.get("GH_TOKEN", "")
if not TOKEN:
    raise SystemExit("GH_TOKEN not set")
REPO = "xinruoyusixian/termux-Ubuntu"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
H = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
     "User-Agent": "v39-release-script"}


def req(method, path, data=None, timeout=180):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(BASE + path, data=body, headers=H, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            b = resp.read()
            return json.loads(b.decode()) if b else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:600]}")


def git(*a):
    return subprocess.check_output(["git", *a], cwd=ROOT).decode().strip()


# 1) 推送本地领先提交
head = git("rev-parse", "HEAD")
remote = req("GET", f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
print(f"local HEAD {head[:8]} remote {remote[:8]}", flush=True)
if remote == head:
    print("remote already at HEAD, skip push", flush=True)
else:
    if subprocess.run(["git", "merge-base", "--is-ancestor", remote, head], cwd=ROOT).returncode != 0:
        raise SystemExit(f"remote {remote[:8]} is not ancestor of HEAD -> refuse overwrite")
    changed = git("diff", "--name-only", f"{remote}..{head}").splitlines()
    msgs = git("log", "--format=%s", f"{remote}..{head}").splitlines()
    msg = "v39: " + " / ".join(msgs)
    bc = req("GET", f"/repos/{REPO}/git/commits/{remote}")
    entries = []
    for rel in changed:
        with open(os.path.join(ROOT, rel.replace("/", os.sep)), "rb") as f:
            c = f.read()
        blob = req("POST", f"/repos/{REPO}/git/blobs",
                   {"content": base64.b64encode(c).decode(), "encoding": "base64"})
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  blob {rel}", flush=True)
    tree = req("POST", f"/repos/{REPO}/git/trees", {"base_tree": bc["tree"]["sha"], "tree": entries})
    commit = req("POST", f"/repos/{REPO}/git/commits",
                 {"message": msg, "tree": tree["sha"], "parents": [remote]})
    req("PATCH", f"/repos/{REPO}/git/refs/heads/main", {"sha": commit["sha"]})
    print(f"main -> {commit['sha'][:8]}  PUSH OK", flush=True)

# 2) 创建 v39 Release
try:
    rel = req("GET", f"/repos/{REPO}/releases/tags/v39")
    print(f"release v39 exists id={rel['id']}", flush=True)
except SystemExit:
    rel = req("POST", f"/repos/{REPO}/releases", {
        "tag_name": "v39",
        "target_commitish": "main",
        "name": "v39 — 模式切换后立即重启生效 + 跨模式音频修复",
        "body": (
            "## 修复\n"
            "- **模式切换后「立即重启」不生效**：目标模式只读用户选择的 `.force-noroot` 标记；"
            "统一 su 探测缓存；清理用 root 权限通杀旧桌面，启动按目标模式选 App/su 身份；"
            "清理完成前不启动，带超时/退出码检查；su 探测移入后台线程避免 ANR。\n"
            "- **跨模式切换后无声**：root 会话残留的 root 属主 `.pulse-rt` 和日志文件，"
            "在清理阶段按 App data 目录属主重建/校正。\n\n"
            "## 资源（三件套均为 v39）\n"
            "- `Ubuntu-XFCE-Tablet-v39.apk` — 平板版 App，含 X11 显示层，装这一个即可\n"
            "- `Ubuntu-XFCE-rootfs-v39.tar.gz` — 系统包，内置桌面入口，已清理个人信息（gzip 通过、隐私复查 0 命中）\n"
            "- `Ubuntu-XFCE-source-v39.tar.gz` — 完整源码 + 文档 + 工具\n"
            "- termux-pkgs / 原版 Termux 未改动，沿用旧版\n\n"
            "## 验证\n"
            "- 真机（小米 Pad 6 Pro / LineageOS / KernelSU）真实 UI 双向回归："
            "非 root→root 得到 root 的 `su - alan` chroot 链；root→非 root 得到 App uid 的 proot 栈；"
            "均无需杀 App，桌面图标/面板/音频正常。"
        ),
        "draft": False,
        "prerelease": False,
    })
    print(f"release v39 created id={rel['id']}", flush=True)

# 3) 上传三个资源（已存在则跳过）
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
existing = {a["name"] for a in assets}
for name in ["Ubuntu-XFCE-Tablet-v39.apk", "Ubuntu-XFCE-rootfs-v39.tar.gz",
             "Ubuntu-XFCE-source-v39.tar.gz"]:
    if name in existing:
        print(f"asset {name} exists, skip", flush=True)
        continue
    local = os.path.join(ROOT, "releases", name)
    size = os.path.getsize(local)
    print(f"uploading {name} ({size/1024/1024:.1f} MB) ...", flush=True)
    url = (f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets"
           f"?name={urllib.parse.quote(name)}")
    uh = {**H, "Content-Type": "application/octet-stream", "Content-Length": str(size)}
    with open(local, "rb") as f:
        data = f.read()
    r = urllib.request.Request(url, data=data, method="POST", headers=uh)
    try:
        with urllib.request.urlopen(r, timeout=3600) as resp:
            up = json.loads(resp.read().decode())
        print(f"  OK {up['name']} -> {up['browser_download_url']}", flush=True)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"upload HTTP {e.code}: {e.read().decode()[:600]}")

# 4) 最终校验
final = req("GET", f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
print(f"FINAL main {final[:8]}", flush=True)
print(f"FINAL assets {[a['name'] for a in assets]}", flush=True)
print("ALL DONE", flush=True)
