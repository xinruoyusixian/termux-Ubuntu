#!/usr/bin/env python3
"""GitHub API 推送 v40 提交 + 创建 Release + 上传三资源。

绕开本机 git push 的 CONNECT 502 / credential 失效问题。
用法: GH_TOKEN=xxx python tools/push_v40_api.py
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
     "User-Agent": "v40-release-script"}


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
# 注意：远端 main 是上次用 API 建的 commit（SHA 与本地不同），本地历史里
# 根本没有那个 SHA，不能用 merge-base/remote..head 判断增量。改用 LOCAL_BASE：
# 它是本地与远端 v39 内容完全一致的那个 commit，用它算增量文件与提交信息。
LOCAL_BASE = "38fc8a6"
head = git("rev-parse", "HEAD")
remote = req("GET", f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
print(f"local HEAD {head[:8]} remote {remote[:8]} base {LOCAL_BASE}", flush=True)
if subprocess.run(["git", "cat-file", "-e", LOCAL_BASE], cwd=ROOT).returncode != 0:
    raise SystemExit(f"LOCAL_BASE {LOCAL_BASE} missing")
if head == LOCAL_BASE:
    print("no local increment beyond base, skip push", flush=True)
    need_push = False
else:
    need_push = True
    changed = git("diff", "--name-only", f"{LOCAL_BASE}..{head}").splitlines()
    msgs = git("log", "--format=%s", f"{LOCAL_BASE}..{head}").splitlines()
    msg = "v40: " + " / ".join(msgs)
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

# 2) 创建 v40 Release
try:
    rel = req("GET", f"/repos/{REPO}/releases/tags/v40")
    print(f"release v40 exists id={rel['id']}", flush=True)
except SystemExit:
    rel = req("POST", f"/repos/{REPO}/releases", {
        "tag_name": "v40",
        "target_commitish": "main",
        "name": "v40 — 终端会话随模式切换（提示符跟着变），无需杀 App",
        "body": (
            "## 修复\n"
            "- **终端会话没跟着模式切**（v39 遗留：提示已切 root、桌面也切了，但终端还是 "
            "`$` / proot，必须杀 App 才变）：「立即重启」改为整系统重启——清理旧桌面 → "
            "kill 旧 Linux 会话 → 按新标记重新 prepare → 新建会话。切 root 终端变 "
            "`root@localhost:~#`，切非 root 变 `$`。\n"
            "- **僵尸会话**：SIGKILL 后 ExecutionCommand 变 failed、finish() 直接 return，"
            "会话残留会导致下次冷启动显示 `[Process completed]`；改为 kill 后显式从列表移除。\n"
            "- 继承 v39：目标模式只读 `.force-noroot`；清理用 root 通杀旧桌面、启动按模式选身份；"
            "跨模式 `.pulse-rt` 属主重建。\n\n"
            "## 资源（三件套均为 v40）\n"
            "- `Ubuntu-XFCE-Tablet-v40.apk` — 平板版 App，含 X11 显示层，装这一个即可\n"
            "- `Ubuntu-XFCE-rootfs-v40.tar.gz` — 系统包，内置桌面入口，已清理个人信息（gzip 通过、隐私复查 0 命中）\n"
            "- `Ubuntu-XFCE-source-v40.tar.gz` — 完整源码 + 文档 + 工具\n"
            "- termux-pkgs / 原版 Termux 未改动，沿用旧版\n\n"
            "## 验证\n"
            "- 真机（小米 Pad 6 Pro / LineageOS / KernelSU）真实 UI 双向回归，全程未杀 App："
            "root→非 root 终端变 `$` + proot 桌面；非 root→root 终端变 `root@localhost:~#` + "
            "chroot 链；均无 `[Process completed]`，桌面图标/面板/音频正常。"
        ),
        "draft": False,
        "prerelease": False,
    })
    print(f"release v40 created id={rel['id']}", flush=True)

# 3) 上传三个资源（已存在则跳过）
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
existing = {a["name"] for a in assets}
for name in ["Ubuntu-XFCE-Tablet-v40.apk", "Ubuntu-XFCE-rootfs-v40.tar.gz",
             "Ubuntu-XFCE-source-v40.tar.gz"]:
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
