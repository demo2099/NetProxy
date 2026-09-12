#!/usr/bin/env python3
"""Build sidecar cores into app/src/main/jniLibs/<abi>/ as lib*.so.

Exec from nativeLibraryDir is the only W^X-legal exec path on API 29+;
useLegacyPackaging (already set) gets jniLibs extracted there.

mihomo — self-built with the `cmfa` tag: official android binaries build
android package rules from /data/system/packages.xml (root-only) and fatal
in an unprivileged sidecar. arm64 links internally (no CGO); arm/386/amd64
need NDK clang.

hev-socks5-tunnel — the in-process TUN→socks5 JNI bridge (v2rayNG-style).
Built with ndk-build against package com.interstellar.proxy.core /
class TProxyService. Git symlinks (include/ shims) must be materialized on
Windows checkouts.

Run from repo root:  python tools/fetch_cores.py
Requires: git, go (mihomo); ndk-build via ANDROID_NDK_HOME or
$ANDROID_HOME/ndk/<ver> for the non-arm64 mihomo ABIs and hev.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

MIHOMO_VERSION = "v1.19.30"
MIHOMO_TAG = "cmfa"  # excludes the root-only android-rules path
HEV_VERSION = "2.17.1"
HEV_PKG = "com/interstellar/proxy/core"

ROOT = Path(__file__).resolve().parent.parent
JNILIBS = ROOT / "app" / "src" / "main" / "jniLibs"
BUILD = Path(os.environ.get("INTERSTELLAR_BUILD_DIR", ROOT / "build" / "cores"))

ABIS = {  # goarch -> android abi
    "arm64": "arm64-v8a",
    "arm": "armeabi-v7a",
    "386": "x86",
    "amd64": "x86_64",
}
NDK_TRIPLE = {
    "arm": "armv7a-linux-androideabi24-clang",
    "386": "i686-linux-android24-clang",
    "amd64": "x86_64-linux-android24-clang",
}


def run(cmd, cwd=None, env=None):
    print(f"$ {' '.join(str(c) for c in cmd)}")
    subprocess.run([str(c) for c in cmd], cwd=cwd, env=env, check=True)


def find_ndk_build() -> Path | None:
    home = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    candidates = []
    if home:
        candidates.append(Path(home))
    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        # local checkouts: parse sdk.dir from local.properties
        props = ROOT / "local.properties"
        if props.is_file():
            for line in props.read_text().splitlines():
                if line.startswith("sdk.dir="):
                    android_home = line.split("=", 1)[1].strip().replace("\\\\", "/")
                    break
    if android_home:
        ndk_root = Path(android_home) / "ndk"
        if ndk_root.is_dir():
            candidates += sorted(ndk_root.iterdir(), reverse=True)
    for base in candidates:
        for name in ("ndk-build", "ndk-build.cmd"):
            p = base / name
            if p.is_file():
                return p
        p = base / "build" / "ndk-build.cmd"
        if p.is_file():
            return p
    return None


def build_mihomo() -> None:
    src = BUILD / "mihomo"
    if not (src / "go.mod").is_file():
        BUILD.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--depth", "1", "--branch", MIHOMO_VERSION,
             "https://github.com/MetaCubeX/mihomo", src])
    missing = [a for a in ABIS if not (JNILIBS / ABIS[a] / "libmihomo.so").is_file()]
    if not missing:
        print("mihomo binaries present, skipping build")
        return

    ndk_build = find_ndk_build()
    ndk_bin = ndk_build.parent / "toolchains" / "llvm" / "prebuilt"
    for arch in missing:
        env = dict(os.environ)
        env["CGO_ENABLED"] = "1"
        env["GOOS"] = "android"
        env["GOARCH"] = arch
        if arch == "arm64":
            env["CGO_ENABLED"] = "0"  # internal linker, no NDK needed
        else:
            if ndk_build is None:
                print(f"!! no ndk-build found, cannot build mihomo {arch}")
                continue
            prebuilt = next(ndk_bin.iterdir())  # e.g. windows-x86_64 / linux-x86_64
            cc = prebuilt / "bin" / NDK_TRIPLE[arch]
            if not cc.is_file():
                cc = prebuilt / "bin" / (NDK_TRIPLE[arch] + ".exe")
            env["CC"] = str(cc)
        out = JNILIBS / ABIS[arch]
        out.mkdir(parents=True, exist_ok=True)
        run(["go", "build", "-tags", f"with_gvisor,{MIHOMO_TAG}", "-trimpath",
             "-ldflags", f'-X "github.com/metacubex/mihomo/constant.Version={MIHOMO_VERSION}-sidecar" -w -s -buildid=',
             "-o", out / "libmihomo.so", "./"], cwd=src, env=env)
        print(f"mihomo {arch} -> {out / 'libmihomo.so'}")


def materialize_git_symlinks(tree: Path) -> None:
    """Windows checkouts turn git symlinks into text files with the target
    path as content — replace them with copies of their targets."""
    for p in tree.rglob("*"):
        if not p.is_file() or p.stat().st_size > 200:
            continue
        content = p.read_text(errors="ignore").strip()
        if content.startswith(("../", "./")) and "/" in content:
            target = (p.parent / content).resolve()
            if target.is_file():
                shutil.copyfile(target, p)


def build_hev() -> None:
    if all((JNILIBS / abi / "libhev-socks5-tunnel.so").is_file() for abi in ABIS.values()):
        print("hev binaries present, skipping build")
        return
    ndk_build = find_ndk_build()
    if ndk_build is None:
        print("!! no ndk-build found, cannot build hev")
        return
    src = BUILD / "hev-socks5-tunnel"
    if not (src / "Android.mk").is_file():
        BUILD.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--depth", "1", "--branch", HEV_VERSION,
             "https://github.com/heiher/hev-socks5-tunnel", src])
        run(["git", "submodule", "update", "--init", "--recursive"], cwd=src)
    materialize_git_symlinks(src)

    work = BUILD / "hev-jni"
    jni = work / "jni"
    jni.mkdir(parents=True, exist_ok=True)
    link = jni / "hev-socks5-tunnel"
    if link.is_symlink() or link.exists():
        link.unlink()
    if os.name == "nt":
        shutil.copytree(src, link)  # junction/symlink needs privileges on windows
    else:
        link.symlink_to(src, target_is_directory=True)
    (jni / "Android.mk").write_text("include $(call all-subdir-makefiles)\n")
    libs = BUILD / "hev-libs"
    run([ndk_build, "NDK_PROJECT_PATH=.", f"APP_BUILD_SCRIPT={jni / 'Android.mk'}",
         f"APP_ABI={' '.join(ABIS.values())}", "APP_PLATFORM=android-24",
         f"NDK_LIBS_OUT={libs}",
         f"APP_CFLAGS=-O3 -DPKGNAME={HEV_PKG}",
         "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"], cwd=work)
    for abi in ABIS.values():
        shutil.copyfile(libs / abi / "libhev-socks5-tunnel.so", JNILIBS / abi / "libhev-socks5-tunnel.so")
        print(f"hev {abi} ok")


def main() -> None:
    build_mihomo()
    build_hev()


if __name__ == "__main__":
    main()
