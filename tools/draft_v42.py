#!/usr/bin/env python3
"""把 v42 Release 转为草稿（对外不可见），避免过早公开。"""
import os, json, subprocess, urllib.request, urllib.error

REPO = "xinruoyusixian/termux-Ubuntu"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def get_token():
    p = subprocess.run(["git", "credential", "fill"], cwd=ROOT,
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("no credential")


TOKEN = os.environ.get("GH_TOKEN") or get_token()
H = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
     "User-Agent": "v42-draft-script"}


def req(method, path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request("https://api.github.com" + path, data=body,
                               headers=H, method=method)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            b = resp.read()
            return json.loads(b.decode()) if b else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code}: {e.read().decode()[:400]}")


rel = req("GET", f"/repos/{REPO}/releases/tags/v42")
print(f"before: id={rel['id']} draft={rel['draft']} tag={rel['tag_name']}", flush=True)
rel = req("PATCH", f"/repos/{REPO}/releases/{rel['id']}", {"draft": True})
print(f"after : id={rel['id']} draft={rel['draft']}", flush=True)
print(f"assets: {[a['name'] for a in req('GET', f'/repos/{REPO}/releases/{rel['id']}/assets')]}",
      flush=True)
print("DRAFTED", flush=True)
