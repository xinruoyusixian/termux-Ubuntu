#!/usr/bin/env python3
"""通过 GitHub API 推送 v38 提交并创建 Release（绕开 git CONNECT 502 / credential 问题）。

前置：本地已有 v38 commit（HEAD），其 parent 为远端 main 当前 HEAD。
流程：校验 token -> 读本地 HEAD/parent -> 对比出改动文件 -> 建 blob -> 建 tree
      -> 建 commit -> 更新 main ref -> 创建 v38 Release -> 上传 APK。

用法: GH_TOKEN=xxx python tools/push_v38_api.py
"""
import os, sys, json, base64, subprocess, urllib.request, urllib.error, urllib.parse

TOKEN = os.environ.get("GH_TOKEN", "")
if not TOKEN:
    raise SystemExit("GH_TOKEN not set")

REPO = "xinruoyusixian/termux-Ubuntu"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "https://api.github.com"
H = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "v38-push-script",
}
APK_LOCAL = os.path.join(ROOT, "releases", "Ubuntu-XFCE-Tablet-v38.apk")
APK_NAME = "Ubuntu-XFCE-Tablet-v38.apk"


def req(method, path, data=None, host=BASE, headers=None, raw=False, timeout=120):
    url = host + path if host.startswith("http") else BASE + path
    body = json.dumps(data).encode() if data is not None else None
    hdr = dict(headers or H)
    r = urllib.request.Request(url, data=body, headers=hdr, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            b = resp.read()
            return b if raw else (json.loads(b.decode()) if b else None)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:600]}")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode().strip()


# 0) 校验 token + 身份
me = req("GET", "/user")
print(f"token ok -> {me.get('login')} ({me.get('type')})", flush=True)

# 1) 本地 commit 信息
head = git("rev-parse", "HEAD")
parent = git("rev-parse", "HEAD^")
msg = git("log", "-1", "--format=%B")
changed = git("diff", "--name-only", f"{parent}..{head}").splitlines()
print(f"local HEAD {head[:8]} parent {parent[:8]} files={len(changed)}", flush=True)

# 2) 远端 main 当前状态
ref = req("GET", f"/repos/{REPO}/git/ref/heads/main")
remote_sha = ref["object"]["sha"]
print(f"remote main {remote_sha[:8]}", flush=True)
if remote_sha == head:
    print("remote already at local HEAD, skip commit push", flush=True)
elif remote_sha != parent:
    raise SystemExit(f"remote main {remote_sha[:8]} != local parent {parent[:8]} -> 需先同步，拒绝覆盖")
base_commit = req("GET", f"/repos/{REPO}/git/commits/{remote_sha}")
base_tree = base_commit["tree"]["sha"]

# 3) 仅当远端落后时才推送 commit
if remote_sha != head:
    entries = []
    for rel in changed:
        full = os.path.join(ROOT, rel.replace("/", os.sep))
        with open(full, "rb") as f:
            content = f.read()
        blob = req("POST", f"/repos/{REPO}/git/blobs", {
            "content": base64.b64encode(content).decode(), "encoding": "base64"})
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  blob {rel} -> {blob['sha'][:8]}", flush=True)
    tree = req("POST", f"/repos/{REPO}/git/trees", {"base_tree": base_tree, "tree": entries})
    commit = req("POST", f"/repos/{REPO}/git/commits", {
        "message": msg, "tree": tree["sha"], "parents": [remote_sha]})
    req("PATCH", f"/repos/{REPO}/git/refs/heads/main", {"sha": commit["sha"]})
    print(f"main -> {commit['sha'][:8]}  PUSH OK", flush=True)

# 4) 找或建 v38 Release
rel = None
try:
    rel = req("GET", f"/repos/{REPO}/releases/tags/v38")
    print(f"release v38 exists id={rel['id']}", flush=True)
except SystemExit:
    pass
if rel is None:
    rel = req("POST", f"/repos/{REPO}/releases", {
        "tag_name": "v38",
        "target_commitish": "main",
        "name": "v38 — 修复 root 模式浏览器/输入法缺失 + 非 root(proot) 桌面黑屏",
        "body": (
            "## 修复\n"
            "- root 模式桌面缺浏览器/输入法：新增 install-desktop-icons.sh，每次启动幂等补齐桌面图标/默认浏览器/fcitx5 自启\n"
            "- 非 root(proot) 桌面黑屏：APK 路径不依赖 pm path（改 .apk-path）、rootfs SELinux MLS 标签对齐（fix_selinux.sh）、日志属主加固、X server 失败快速报错\n"
            "- dev/pts 挂载顺序修正\n\n"
            "## 资源\n"
            "- 本 Release 只提供 APK；系统包沿用 v37 的 Ubuntu-XFCE-rootfs-v37.tar.gz，termux-pkgs 沿用旧版\n\n"
            "## 验证\n"
            "- 真机（小米 Pad 6 Pro / LineageOS / KernelSU）root 与 proot 双模式回归通过"
        ),
        "draft": False,
        "prerelease": False,
    })
    print(f"release v38 created id={rel['id']}", flush=True)

# 5) 上传 APK（若同名资源已存在则跳过）
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
if any(a["name"] == APK_NAME for a in assets):
    print(f"asset {APK_NAME} already uploaded, skip", flush=True)
else:
    size = os.path.getsize(APK_LOCAL)
    print(f"uploading {APK_NAME} ({size/1024/1024:.1f} MB) ...", flush=True)
    up_url = (f"https://uploads.github.com/repos/{REPO}/releases/{rel['id']}/assets"
              f"?name={urllib.parse.quote(APK_NAME)}")
    uh = {**H, "Content-Type": "application/octet-stream", "Content-Length": str(size)}
    with open(APK_LOCAL, "rb") as f:
        data = f.read()
    r = urllib.request.Request(up_url, data=data, method="POST", headers=uh)
    try:
        with urllib.request.urlopen(r, timeout=1800) as resp:
            up = json.loads(resp.read().decode())
        print(f"upload OK {up['name']} url={up['browser_download_url']}", flush=True)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"upload HTTP {e.code}: {e.read().decode()[:600]}")

# 6) 最终校验
final = req("GET", f"/repos/{REPO}/git/ref/heads/main")
print(f"FINAL main {final['object']['sha'][:8]} (local {head[:8]})", flush=True)
print("ALL DONE", flush=True)
