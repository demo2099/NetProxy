#!/usr/bin/env python3
"""Fetch sidecar core binaries into app/src/main/jniLibs/<abi>/ as lib*.so.

Exec from nativeLibraryDir is the only W^X-legal exec path on API 29+;
packaging as jniLibs (useLegacyPackaging=true, already set) gets them
extracted there by the installer.

Phase 2 payload: mihomo official android builds (all 4 ABIs).
Xray + hev-socks5-tunnel arrive in Phase 3 (self-built in CI).

Run from repo root:  python tools/fetch_cores.py
"""
import gzip
import urllib.request
from pathlib import Path

MIHOMO_VERSION = "v1.19.30"

# asset arch in release filename -> android ABI / jniLibs dir
MIHOMO_ASSETS = {
    "arm64-v8": "arm64-v8a",
    "armv7": "armeabi-v7a",
    "386": "x86",
    "amd64": "x86_64",
}

ROOT = Path(__file__).resolve().parent.parent
JNILIBS = ROOT / "app" / "src" / "main" / "jniLibs"


def fetch(url: str) -> bytes:
    print(f"fetch {url}")
    with urllib.request.urlopen(url) as resp:
        return resp.read()


def place(abi: str, soname: str, data: bytes) -> None:
    out = JNILIBS / abi / soname
    if out.exists() and out.stat().st_size > 0:
        print(f"skip {out} (exists)")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    print(f"wrote {out} ({len(data) // 1048576} MB)")


def fetch_mihomo() -> None:
    for arch, abi in MIHOMO_ASSETS.items():
        url = (
            "https://github.com/MetaCubeX/mihomo/releases/download/"
            f"{MIHOMO_VERSION}/mihomo-android-{arch}-{MIHOMO_VERSION}.gz"
        )
        place(abi, "libmihomo.so", gzip.decompress(fetch(url)))


def main() -> None:
    fetch_mihomo()


if __name__ == "__main__":
    main()
