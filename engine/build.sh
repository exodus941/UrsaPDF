#!/usr/bin/env bash
# Builds the downloadable engine: one zip per ABI holding libpdfium.so (prebuilt by
# bblanchon/pdfium-binaries, pinned below), libursa.so (engine/ursa.c) and the licences.
#
# Usage: engine/build.sh OUTDIR [abi ...]     abis: arm64 arm x64 x86 (default: all)
# Needs ANDROID_NDK_HOME (or ANDROID_HOME with ndk/<version>), curl, tar, python.
set -euo pipefail

PDFIUM_TAG="chromium/8125"
API=$(sed -n 's/^#define ENGINE_API \([0-9]*\).*/\1/p' "$(dirname "$0")/ursa.c")
VERSION=$(cat "$(dirname "$0")/VERSION")
OUT=$(realpath -m "$1"); shift
ABIS=("${@:-arm64 arm x64 x86}"); [ $# -eq 0 ] && ABIS=(arm64 arm x64 x86)
SRC=$(cd "$(dirname "$0")" && pwd)

NDK=${ANDROID_NDK_HOME:-}
[ -z "$NDK" ] && NDK=$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/ndk/* | sort -V | tail -1)
HOST=$(ls "$NDK/toolchains/llvm/prebuilt/")
CC="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang"
[ -x "$CC" ] || CC="$CC.exe"

declare -A TRIPLE=([arm64]=aarch64-linux-android26 [arm]=armv7a-linux-androideabi26 [x64]=x86_64-linux-android26 [x86]=i686-linux-android26)

mkdir -p "$OUT"
WORK=$(mktemp -d)
for abi in "${ABIS[@]}"; do
  d="$WORK/$abi"; mkdir -p "$d/pdfium" "$d/engine"
  curl -sSfL -o "$d/p.tgz" "https://github.com/bblanchon/pdfium-binaries/releases/download/${PDFIUM_TAG/\//%2F}/pdfium-android-$abi.tgz"
  tar xzf "$d/p.tgz" -C "$d/pdfium"
  "$CC" --target="${TRIPLE[$abi]}" -shared -fPIC -O2 -s -Wl,--build-id=none \
    -I "$d/pdfium/include" "$SRC/ursa.c" -o "$d/engine/libursa.so" -ldl
  cp "$d/pdfium/lib/libpdfium.so" "$d/engine/"
  cp -r "$d/pdfium/licenses" "$d/engine/licenses"
  cp "$d/pdfium/LICENSE" "$d/engine/licenses/pdfium-binaries.txt"
  printf '%s\n' "$VERSION" > "$d/engine/VERSION"
  name="ursa-engine-a$API-v$VERSION-$abi.zip"
  PY=$(command -v python3 || command -v python)
  (cd "$d/engine" && "$PY" -m zipfile -c "$OUT/$name" libpdfium.so libursa.so VERSION licenses)
  echo "$name $(stat -c %s "$OUT/$name")"
done
rm -rf "$WORK"
