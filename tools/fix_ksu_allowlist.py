#!/usr/bin/env python3
"""修 KernelSU 白名单里「包名 → uid」的陈旧映射（设备侧一次性修复工具）。

背景
----
App 卸载重装后 Android 会给它分配**新的 uid**（本机实测 10167 → 10183）。
KernelSU 的授权记录是按 uid 存的，管理器如果用了缓存的旧 uid，
就会出现「我明明在 KernelSU 里授权了，App 还是说没 root」——
因为白名单里那条 uid 指向的是已经不存在的老安装。

为什么需要这个脚本
------------------
正确做法是在 KernelSU 管理器里刷新应用列表后**重新授权**，管理器会写入新 uid。
但管理器自身也可能缓存，导致反复授权都写回旧值。此时只能直接改它落盘的白名单。

白名单格式（实测，KernelSU 0.9.x / LKM 模式）
-------------------------------------------
    offset 0    : b"USK\\x7f"        魔数
    offset 4    : u32                版本
    offset 8    : u32                条目数（不可靠，别用来切分）
    offset 12   : 条目区
    条目内      : name[256] + uid(u32) + flags(u32) + ... + domain[256]

所以：在条目区里找到包名（256 字节名字段），其**后 256 字节处**就是该包的 uid。

用法
----
    python tools/fix_ksu_allowlist.py            # 只检查，不改
    python tools/fix_ksu_allowlist.py --apply    # 改（先自动备份）

依赖 adb 与 root（su）。
"""
import os
import struct
import subprocess
import sys

ADB = os.environ.get("ADB", "adb")
ALLOWLIST = "/data/adb/ksu/.allowlist"
PKG = "com.termux"
NAME_LEN = 256


def sh(cmd, timeout=60):
    p = subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=timeout)
    return p.stdout.strip()


def current_uid():
    """动态取当前安装的 uid。

    首选 App 自己落盘的 $FILES/.app-uid（Java 里 Process.myUid()，绝对准确）；
    拿不到再退回 stat / pm 反推。任何环节都不写死数字。
    """
    out = sh(f"su -c 'cat /data/data/{PKG}/files/.app-uid'")
    if out.isdigit():
        return int(out)
    for cmd in (
        f"su -c 'stat -c %u /data/data/{PKG}'",
        f"su -c 'stat -c %u /data/data/{PKG}/files'",
        f"su -c 'pm list packages -U | grep {PKG}'",
    ):
        out = sh(cmd)
        if not out:
            continue
        if "uid:" in out:
            return int(out.split("uid:")[1].split()[0])
        for tok in out.split():
            if tok.isdigit():
                return int(tok)
    raise SystemExit("取不到当前 uid，请确认设备已 root 且 App 已安装")


def main():
    apply = "--apply" in sys.argv
    uid = current_uid()
    print(f"当前安装 uid = {uid}")

    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".allowlist.tmp")
    local = os.path.abspath(local)
    subprocess.run([ADB, "pull", ALLOWLIST, local], check=True,
                   capture_output=True)
    data = bytearray(open(local, "rb").read())
    print(f"白名单 {len(data)} 字节")

    if not data.startswith(b"USK\x7f"):
        raise SystemExit("不是预期的 KernelSU 白名单格式，放弃修改")

    name = PKG.encode()
    pos = data.find(name + b"\x00" * (NAME_LEN - len(name)))
    if pos < 0:
        raise SystemExit(f"白名单里没有 {PKG} 的条目：请在 KernelSU 里手动授权一次")

    uid_off = pos + NAME_LEN
    old = struct.unpack_from("<I", data, uid_off)[0]
    print(f"白名单记录 uid = {old}（条目起点 {pos}，uid 字段 {uid_off}）")

    if old == uid:
        print("一致，无需修改")
        return
    if not apply:
        print(f"不一致：{old} -> {uid}。加 --apply 才会改。")
        return

    backup = ALLOWLIST + ".bak"
    sh(f"su -c 'cp -f {ALLOWLIST} {backup}'")
    struct.pack_into("<I", data, uid_off, uid)
    open(local, "wb").write(bytes(data))

    subprocess.run([ADB, "push", local, "/data/local/tmp/.allowlist.new"],
                   check=True, capture_output=True)
    sh(f"su -c 'cp -f /data/local/tmp/.allowlist.new {ALLOWLIST}; "
       f"chown root:root {ALLOWLIST}; chmod 644 {ALLOWLIST}; "
       f"rm -f /data/local/tmp/.allowlist.new'")
    os.remove(local)

    check = sh(f"su -c 'od -An -tx1 {ALLOWLIST}'").replace(" ", "").replace("\n", "")
    want = struct.pack("<I", uid).hex()
    print(f"校验：{'OK，已写入 ' + str(uid) if want in check else '失败，未见 ' + want}")
    print(f"原文件已备份到 {backup}")


if __name__ == "__main__":
    main()
