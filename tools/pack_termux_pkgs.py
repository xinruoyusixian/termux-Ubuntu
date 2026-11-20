import os, tarfile, stat

SRC = "termux-pkgs-tree"
OUT = "termux-pkgs.tar.gz"

def norm_mode(path, is_dir):
    if is_dir:
        return 0o755
    p = path.replace("\\", "/")
    if "/usr/bin/" in p or "/usr/lib/" in p or p.endswith(".sh"):
        return 0o755
    return 0o644

count = 0
with tarfile.open(OUT, "w:gz", compresslevel=6) as tf:
    for root, dirs, files in os.walk(SRC):
        for name in dirs + files:
            full = os.path.join(root, name)
            arc = os.path.relpath(full, SRC).replace("\\", "/")
            is_dir = os.path.isdir(full)
            if is_dir:
                ti = tarfile.TarInfo(arc)
                ti.type = tarfile.DIRTYPE
                ti.mode = 0o755
                tf.addfile(ti)
                continue
            if os.path.islink(full):
                ti = tarfile.TarInfo(arc)
                ti.type = tarfile.SYMTYPE
                ti.linkname = os.readlink(full)
                ti.mode = 0o777
                tf.addfile(ti)
                count += 1
                continue
            ti = tarfile.TarInfo(arc)
            ti.size = os.path.getsize(full)
            ti.mode = norm_mode(arc, False)
            ti.mtime = 1700000000
            with open(full, "rb") as f:
                tf.addfile(ti, f)
            count += 1

print("打包完成:", OUT, os.path.getsize(OUT) / 1024 / 1024, "MB, 文件数", count)
