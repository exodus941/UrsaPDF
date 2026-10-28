#!/usr/bin/env bash
# Publishes the engine into engine/: builds every ABI, signs each zip, removes the
# older zips of the same API and records the version in engine/latest.json, which
# the app reads. Does nothing when engine/latest.json already lists this version.
#
# Usage: engine/src/publish.sh KEYSTORE PASSWORD
set -euo pipefail
SRC=$(cd "$(dirname "$0")" && pwd)
OUT=$(dirname "$SRC")
API=$(sed -n 's/^#define ENGINE_API \([0-9]*\).*/\1/p' "$SRC/ursa.c")
VERSION=$(cat "$SRC/VERSION")
PY=$(command -v python3 || command -v python)
INDEX="$OUT/latest.json"

have=$("$PY" -I -c 'import json,sys
try: print(json.load(open(sys.argv[1]))[sys.argv[2]]["version"])
except Exception: print(0)' "$INDEX" "$API")
if [ "$have" = "$VERSION" ]; then echo "engine a$API v$VERSION is already published"; exit 0; fi

rm -f "$OUT"/ursa-engine-a$API-v*
bash "$SRC/build.sh" "$OUT"
bash "$SRC/sign.sh" "$1" "$2" "$OUT"/ursa-engine-a$API-v$VERSION-*.zip

"$PY" -I -c 'import json,os,sys
path, api, ver, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
try: index = json.load(open(path))
except Exception: index = {}
sizes = {}
for abi in ("arm64", "arm", "x64", "x86"):
    sizes[abi] = os.path.getsize(os.path.join(out, "ursa-engine-a%s-v%d-%s.zip" % (api, ver, abi)))
index[api] = {"version": ver, "sizes": sizes}
json.dump(index, open(path, "w"), indent=2, sort_keys=True)
open(path, "a").write("\n")' "$INDEX" "$API" "$VERSION" "$OUT"
echo "published engine a$API v$VERSION"
