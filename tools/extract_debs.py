import os, sys, io, lzma, tarfile, glob, shutil

SRC = "txpkg"
OUT = "termux-pkgs-tree"

# 只保留 Termux bootstrap 里没有、且运行时真正需要的包；
# bash/coreutils/util-linux/termux-tools 等在 bootstrap 中已存在，覆盖反而危险。
WHITELIST = {
    "pulseaudio", "libltdl", "libsndfile", "libsoxr", "speexdsp",
    "libwebrtc-audio-processing", "libandroid-execinfo", "libandroid-glob",
    "libflac", "libogg", "libvorbis", "libopus", "libmp3lame", "libmpg123",
    "libx11", "libxcb", "libxau", "libxdmcp", "libexpat", "libiconv",
    "termux-api", "termux-am-socket", "dbus", "dbus",
}

def read_ar(path):
    """返回 dict: name -> bytes（仅取 data.tar.*）"""
    data = open(path, "rb").read()
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("not ar: " + path)
    off = 8
    files = {}
    while off + 60 <= len(data):
        hdr = data[off:off + 60]
        name = hdr[0:16].decode("utf-8", "ignore").strip().rstrip("/")
        try:
            size = int(hdr[48:58].decode().strip())
        except ValueError:
            break
        body = data[off + 60: off + 60 + size]
        files[name] = body
        off += 60 + size + (size % 2)
    return files

def decompress(name, body):
    if name.endswith(".xz"):
        return lzma.decompress(body)
    if name.endswith(".gz"):
        import gzip
        return gzip.decompress(body)
    if name.endswith(".zst"):
        try:
            import zstandard
            return zstandard.ZstdDecompressor().decompress(body)
        except ImportError:
            raise RuntimeError("需要 zstandard 模块来解 .zst")
    if name.endswith(".tar"):
        return body
    raise ValueError("未知压缩: " + name)

def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    total = 0
    lost = 0
    for deb in sorted(glob.glob(os.path.join(SRC, "*.deb"))):
        pkgname = os.path.basename(deb).split("_")[0]
        if pkgname not in WHITELIST:
            continue
        try:
            files = read_ar(deb)
            name = [n for n in files if n.startswith("data.tar")][0]
            raw = decompress(name, files[name])
            with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
                members = tf.getmembers()
                tf.extractall(OUT, filter="tar") if hasattr(tarfile, "data_filter") else tf.extractall(OUT)
                total += len(members)
            print(f"OK  {os.path.basename(deb):55s} {len(members):4d} entries ({name})")
        except Exception as e:
            lost += 1
            print(f"ERR {os.path.basename(deb)}: {e}")
    print(f"\n共提取 {total} 个条目，失败 {lost} 个包")
    usr = os.path.join(OUT, "data/data/com.termux/files/usr")
    print("usr 目录存在:", os.path.isdir(usr))
    if os.path.isdir(usr):
        n = sum(len(f) for _, _, f in os.walk(usr))
        print("usr 文件数:", n)

main()
