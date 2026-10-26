"""Restore pinned FFmpeg native libraries and the matching public C headers.

Run with Python 3. All downloads are verified before use. No Android source is edited.
"""
from pathlib import Path
import hashlib
import re
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "third_party" / "ffmpeg"
DOWNLOADS = DEST / ".downloads"
ARTIFACTS = {
    "ffmpeg-kit-full-6.0.LTS.aar": (
        "https://maven.aliyun.com/repository/central/com/arthenica/ffmpeg-kit-full/6.0.LTS/ffmpeg-kit-full-6.0.LTS.aar",
        "e77eb8d02b67e225759b27e7288f84a8d2a4392abd2418f282c2936a990df4c3",
    ),
    "ffmpeg-6.0.tar.xz": (
        "https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz",
        "57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082",
    ),
}


def fetch(name):
    url, expected = ARTIFACTS[name]
    cached = DOWNLOADS / name
    if not cached.exists():
        DOWNLOADS.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, cached)
    actual = hashlib.sha256(cached.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch for {name}: {actual}")
    return cached


def prepare():
    with zipfile.ZipFile(fetch("ffmpeg-kit-full-6.0.LTS.aar")) as archive:
        libs = {f"lib{name}.so" for name in ("avcodec", "avformat", "avutil", "avfilter", "swresample", "swscale")}
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "jni" and parts[2] in libs:
                target = DEST / "lib" / parts[1] / parts[2]
            elif name.startswith("res/raw/") and not name.endswith("/"):
                target = ROOT / "app/src/main/assets/ffmpeg-licenses" / parts[-1]
            else:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    with tarfile.open(fetch("ffmpeg-6.0.tar.xz")) as archive:
        members = {m.name.removeprefix("ffmpeg-6.0/"): m for m in archive.getmembers() if m.name.endswith(".h")}
        todo = ["libavformat/avformat.h", "libavcodec/avcodec.h", "libavfilter/avfilter.h",
                "libavfilter/buffersrc.h", "libavfilter/buffersink.h", "libavutil/opt.h",
                "libavutil/imgutils.h", "libavutil/time.h", "libswscale/swscale.h"]
        seen = set()
        while todo:
            name = todo.pop()
            if name in seen or name not in members:
                continue
            seen.add(name)
            data = archive.extractfile(members[name]).read()
            target = DEST / "include" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            for include in re.findall(r'#include [<"]([^>"]+)[>"]', data.decode()):
                candidate = include if include.startswith("lib") else (Path(name).parent / include).as_posix()
                if candidate in members:
                    todo.append(candidate)
        config = DEST / "include/libavutil/avconfig.h"
        config.write_text("#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n")
        # Keep only transitive public API headers, not FFmpeg's internal build headers.
        for header in (DEST / "include").rglob("*.h"):
            relative = header.relative_to(DEST / "include").as_posix()
            if relative not in seen and relative != "libavutil/avconfig.h":
                header.unlink()
    for library in (DEST / "lib").glob("*/*.so"):
        print(hashlib.sha256(library.read_bytes()).hexdigest(), library.relative_to(ROOT))


if __name__ == "__main__":
    prepare()
