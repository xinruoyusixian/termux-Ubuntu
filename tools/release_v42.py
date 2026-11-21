#!/usr/bin/env python3
"""v42 Release：创建 tag + 上传三资源（APK / rootfs / source）。

代码提交已经用 git push 推上去了，本脚本只做 Release 部分。
Token 从本机 git credential helper 取（不落盘、不打印）。
用法: python tools/release_v42.py
"""
import os, json, subprocess, urllib.request, urllib.error, urllib.parse

REPO = "xinruoyusixian/termux-Ubuntu"
TAG = "v42"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def get_token():
    p = subprocess.run(
        ["git", "credential", "fill"], cwd=ROOT, input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("no credential found; set GH_TOKEN env instead")


TOKEN = os.environ.get("GH_TOKEN") or get_token()
H = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
     "User-Agent": "v42-release-script"}


def req(method, path, data=None, timeout=180, base=BASE):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(base + path, data=body, headers=H, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            b = resp.read()
            return json.loads(b.decode()) if b else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:600]}")


# 1) 确认远端 main 就是 v42 提交
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
remote = req("GET", f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
print(f"local HEAD {head[:8]}  remote main {remote[:8]}", flush=True)
if head != remote:
    print("WARN: local HEAD != remote main（不影响 Release，但请注意）", flush=True)

# 2) 建 Release（已存在则复用）
BODY = (
    "## 本次修复（双模式真机验证）\n"
    "- **Chrome「打开你的个人资料出了点问题」**：强杀后 SQLite 残留的 "
    "`-journal/-wal/-shm` 会导致 `database is locked`；`SingletonLock` 里记的 pid 若已死，"
    "Chrome 会 `Failed to create a ProcessSingleton ... Aborting now` —— **静默 abort，"
    "界面毫无反应**。改为启动前删残留 journal 并校验锁内 pid。\n"
    "- **输入法状态栏空白、点一下才生效**：fcitx5 的 `ActiveByDefault` 默认是 `False`。"
    "两模式启动时强制改写为 `True`。\n"
    "- **切模式后输入法/Chrome 互抢**：proot 会话起的进程留在 Android 原生命名空间，"
    "与 chroot 内实例抢 XIM 和 profile 锁。启动前清掉另一端的 `fcitx5`/`chrome`。\n"
    "- **重启后黑屏**：`termux-x11` 进程在但没建 X socket，旧逻辑 `pgrep` 命中就认为"
    "「已运行」直接跳过启动。改为僵尸检测：有进程无 socket 即清理重启。\n"
    "- **面板「中英切换」按钮**：精简 rootfs 的 xfce4-panel 只带 XEmbed 托盘，"
    "没有 StatusNotifier 宿主（noble 已移除 `xfce4-statusnotifier-plugin`），"
    "托盘区永远空白。改用面板 launcher + 自绘 SVG 图标。\n"
    "- **App UID 全链路动态解析**：`stat files` → `stat /data/data/com.termux` → "
    "`pm list packages -U` 三级回退，去掉硬编码 10183（本机实测 10167）。\n"
    "- **启动横幅版本号动态化**：改由 APK 写 `$FILES/.app-version`、脚本读取，"
    "不再硬编码（以前每发一版都要手改，常漏改）。\n\n"
    "## 资源（三件套均为 v42）\n"
    "- `Ubuntu-XFCE-Tablet-v42.apk` — 平板版 App，含 X11 显示层，装这一个即可\n"
    "- `Ubuntu-XFCE-rootfs-v42.tar.gz` — 系统包，已清理个人信息"
    "（Chrome profile/Cookie/历史全删、apt 索引与缓存清理，回收 441M）\n"
    "- `Ubuntu-XFCE-source-v42.tar.gz` — 完整源码 + 文档 + 工具\n"
    "- termux-pkgs / 原版 Termux 未改动，沿用旧版\n\n"
    "## 升级说明\n"
    "直接覆盖安装 APK 即可，系统包无需重导。**装完请先把上一次的桌面栈清干净再开**"
    "（`adb install -r` 会杀掉 App 进程，但 `termux-x11` 和 chroot 挂载会变孤儿，"
    "不清就会黑屏）—— 全新安装的用户不受影响。"
)

try:
    rel = req("GET", f"/repos/{REPO}/releases/tags/{TAG}")
    print(f"release {TAG} 已存在 id={rel['id']}", flush=True)
except SystemExit:
    rel = req("POST", f"/repos/{REPO}/releases", {
        "tag_name": TAG,
        "target_commitish": "main",
        "name": "v42 — 输入法默认激活 / Chrome profile 自愈 / 僵尸 X server 自愈",
        "body": BODY,
        "draft": False,
        "prerelease": False,
    })
    print(f"release {TAG} 创建成功 id={rel['id']}", flush=True)

# 3) 上传三资源（同名已存在则跳过）
ASSETS = [
    ("Ubuntu-XFCE-Tablet-v42.apk", "Ubuntu-XFCE-平板版-v42.apk"),
    ("Ubuntu-XFCE-rootfs-v42.tar.gz", "Ubuntu-XFCE-rootfs-v42.tar.gz"),
    ("Ubuntu-XFCE-source-v42.tar.gz", "Ubuntu-XFCE-source-v42.tar.gz"),
]
existing = {a["name"] for a in req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")}
for remote_name, local_name in ASSETS:
    if remote_name in existing:
        print(f"asset {remote_name} 已存在，跳过", flush=True)
        continue
    local = os.path.join(ROOT, "releases", local_name)
    if not os.path.exists(local):
        raise SystemExit(f"本地文件缺失: {local}")
    size = os.path.getsize(local)
    print(f"上传 {remote_name} ({size/1024/1024:.1f} MB) ...", flush=True)
    url = (f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets"
           f"?name={urllib.parse.quote(remote_name)}")
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

# 4) 校验
assets = req("GET", f"/repos/{REPO}/releases/{rel['id']}/assets")
for a in assets:
    print(f"  {a['name']}  {a['size']/1024/1024:.1f} MB  state={a['state']}", flush=True)
print("ALL DONE", flush=True)
