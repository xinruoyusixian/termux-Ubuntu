#!/usr/bin/env python3
"""通过 GitHub API 推送 v36 改动（绕开 git CONNECT 502 / credential 问题）。
流程：创建 blob -> 创建 tree -> 创建 commit -> 更新 main ref。
"""
import os, re, json, base64, urllib.request, urllib.error

TOKEN = os.environ.get("GH_TOKEN", "")
if not TOKEN:
    raise SystemExit("GH_TOKEN not set")

REPO = "xinruoyusixian/termux-Ubuntu"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "https://api.github.com"
HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "v36-push-script",
}

FILES = [
    "README.md",
    "docs/使用与部署说明.md",
    "termux-app-master/app/src/main/assets/backup-rootfs.sh",
    "termux-app-master/app/src/main/assets/restore-rootfs.sh",
    "termux-app-master/app/src/main/java/com/termux/app/LinuxLauncher.java",
    "termux-app-master/app/src/main/java/com/termux/app/PadMenu.java",
    "termux-app-master/app/src/main/java/com/termux/app/TermuxActivity.java",
    "termux-app-master/app/src/main/java/com/termux/app/TermuxApplication.java",
    "termux-app-master/app/src/main/java/com/termux/x11/MainActivity.java",
    "termux-app-master/app/src/main/res/drawable/ic_pad_mode.xml",
    "termux-app-master/app/src/main/res/layout/activity_termux.xml",
    "termux-app-master/app/src/main/res/layout/main_activity.xml",
    "termux-app-master/app/src/main/res/values/strings.xml",
    "tools/clean_rootfs.sh",
    "tools/pack_v36_on_device.sh",
]

def req(method, path, data=None):
    url = f"{BASE}{path}"
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(url, data=body, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:500]}")

# 1) 当前 main 的 commit sha 与 tree sha
ref = req("GET", f"/repos/{REPO}/git/ref/heads/main")
base_sha = ref["object"]["sha"]
base_commit = req("GET", f"/repos/{REPO}/git/commits/{base_sha}")
base_tree = base_commit["tree"]["sha"]
print(f"base commit {base_sha[:8]} tree {base_tree[:8]}")

# 2) 为每个文件创建 blob
tree_entries = []
for rel in FILES:
    full = os.path.join(ROOT, rel.replace("/", os.sep))
    with open(full, "rb") as f:
        content = f.read()
    blob = req("POST", f"/repos/{REPO}/git/blobs", {
        "content": base64.b64encode(content).decode(),
        "encoding": "base64",
    })
    tree_entries.append({
        "path": rel,
        "mode": "100644",
        "type": "blob",
        "sha": blob["sha"],
    })
    print(f"  blob {rel} -> {blob['sha'][:8]}")

# 3) 创建 tree（基于 base tree）
tree = req("POST", f"/repos/{REPO}/git/trees", {
    "base_tree": base_tree,
    "tree": tree_entries,
})
print(f"tree {tree['sha'][:8]}")

# 4) 创建 commit
message = ("v36: 默认非root/模式切换/下载挂载/备份恢复/全中文/root实测加固\n\n"
           "- 默认非root，.mode-initialized 只初始化一次，尊重用户切换\n"
           "- 终端菜单 root<->非root 切换，无可用su明确提示，子线程检测防ANR\n"
           "- root实测: 先waitFor再读流(防授权弹窗卡死)、遍历多su路径、缓存可用路径\n"
           "- 挂载下载 root/非root 都可用，走Android文件访问权限+真实读写校验\n"
           "- 备份系统/恢复备份: 整系统tar包(用户备份含个人信息,发布包不含)\n"
           "- backup脚本拆分tar/gzip正确捕获退出码; restore护栏防bind穿透+uid自适应\n"
           "- Termux原生菜单/设置/提示全量汉化\n"
           "- clean_rootfs.sh 删除Chrome profile/历史/recently-used; 新增设备端打包脚本\n"
           "- 文档更新到v36")
commit = req("POST", f"/repos/{REPO}/git/commits", {
    "message": message,
    "tree": tree["sha"],
    "parents": [base_sha],
})
print(f"commit {commit['sha'][:8]}")

# 5) 更新 main ref
updated = req("PATCH", f"/repos/{REPO}/git/refs/heads/main", {"sha": commit["sha"]})
print(f"main -> {updated['object']['sha'][:8]}")
print("PUSH OK")
