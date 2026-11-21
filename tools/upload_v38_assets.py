#!/usr/bin/env python3
"""v38 收尾：推送文档提交 + 上传 rootfs/source 资源 + 更新 Release 正文。

前置：本地 HEAD 为文档提交（parent = 远端 main），releases/ 下已有
      Ubuntu-XFCE-rootfs-v38.tar.gz 与 Ubuntu-XFCE-source-v38.tar.gz。
流程：推送 commit -> 更新 v38 Release 正文 -> 上传两个缺失资源 -> 校验。

用法: GH_TOKEN=xxx python tools/upload_v38_assets.py
"""
import os, json, base64, subprocess, urllib.request, urllib.error, urllib.parse

TOKEN = os.environ.get("GH_TOKEN", "")
if not TOKEN:
    raise SystemExit("GH_TOKEN not set")
REPO = "xinruoyusixian/termux-Ubuntu"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "https://api.github.com"
H = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
     "User-Agent": "v38-assets-script"}


def req(method, path, data=None, raw=False, timeout=120):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(BASE + path, data=body, headers=H, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            b = resp.read()
            return b if raw else (json.loads(b.decode()) if b else None)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:600]}")


def git(*a):
    return subprocess.check_output(["git", *a], cwd=ROOT).decode().strip()


# 1) 推送本地所有领先提交（远端 -> HEAD 的差异打包成一个 commit）
head = git("rev-parse", "HEAD")
ref = req("GET", f"/repos/{REPO}/git/ref/heads/main")
remote = ref["object"]["sha"]
print(f"local HEAD {head[:8]} remote {remote[:8]}", flush=True)
if remote == head:
    print("remote already at HEAD, skip push", flush=True)
else:
    # 确认 remote 是本地 HEAD 的祖先，拒绝覆盖式强推
    if subprocess.run(["git", "merge-base", "--is-ancestor", remote, head], cwd=ROOT).returncode != 0:
        raise SystemExit(f"remote {remote[:8]} is not ancestor of HEAD -> refuse overwrite")
    changed = git("diff", "--name-only", f"{remote}..{head}").splitlines()
    msgs = git("log", "--format=%s", f"{remote}..{head}").splitlines()
    msg = "v38 交付补全: " + " / ".join(msgs)
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

# 2) 更新 Release 正文
rel = req("GET", f"/repos/{REPO}/releases/tags/v38")
new_body = (
    "## v38 完整修复版（APK + 系统包 + 源码均已更新）\n\n"
    "### 修复内容\n"
    "- root 模式桌面缺浏览器/输入法：新增 install-desktop-icons.sh，每次启动幂等补齐桌面图标/默认浏览器/fcitx5 自启\n"
    "- 非 root(proot) 桌面黑屏：APK 路径不依赖 pm path（改 .apk-path）、rootfs SELinux MLS 标签对齐（fix_selinux.sh）、日志属主加固、X server 失败快速报错\n"
    "- dev/pts 挂载顺序修正\n\n"
    "### 资源（三件套均为 v38 修复版）\n"
    "- `Ubuntu-XFCE-Tablet-v38.apk` — 平板版 App，装这一个即可\n"
    "- `Ubuntu-XFCE-rootfs-v38.tar.gz` — 系统包，首次导入用，**内置修复后的桌面入口 + 已清理个人信息**（gzip 校验通过，隐私复查 0 命中）\n"
    "- `Ubuntu-XFCE-source-v38.tar.gz` — 完整源码（源码 + 文档 + 工具，排除构建产物/.git）\n"
    "- termux-pkgs / 原版 Termux 沿用旧版（未改动）\n\n"
    "### 验证\n"
    "- 真机（小米 Pad 6 Pro / LineageOS / KernelSU）root 与 proot 双模式回归通过：桌面全栈、4 图标、默认浏览器、输入法自启齐全\n"
)
req("PATCH", f"/repos/{REPO}/releases/{rel['id']}", {"body": new_body, "name": rel["name"]})
print(f"release body updated (id={rel['id']})", flush=True)

# 3) 上传缺失资源
upload_host = "https://uploads.github.com"
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
existing = {a["name"] for a in assets}
for name in ["Ubuntu-XFCE-rootfs-v38.tar.gz", "Ubuntu-XFCE-source-v38.tar.gz"]:
    if name in existing:
        print(f"asset {name} exists, skip", flush=True)
        continue
    local = os.path.join(ROOT, "releases", name)
    size = os.path.getsize(local)
    print(f"uploading {name} ({size/1024/1024:.1f} MB) ...", flush=True)
    url = (f"{upload_host}/repos/{REPO}/releases/{rel['id']}/assets"
           f"?name={urllib.parse.quote(name)}")
    uh = {**H, "Content-Type": "application/octet-stream", "Content-Length": str(size)}
    with open(local, "rb") as f:
        data = f.read()
    r = urllib.request.Request(url, data=data, method="POST", headers=uh)
    try:
        with urllib.request.urlopen(r, timeout=1800) as resp:
            up = json.loads(resp.read().decode())
        print(f"  OK {up['name']} url={up['browser_download_url']}", flush=True)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"upload HTTP {e.code}: {e.read().decode()[:600]}")

# 4) 校验最终状态
final = req("GET", f"/repos/{REPO}/git/ref/heads/main")
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
print(f"FINAL main {final['object']['sha'][:8]}", flush=True)
print(f"FINAL assets {[a['name'] for a in assets]}", flush=True)
print("ALL DONE", flush=True)
