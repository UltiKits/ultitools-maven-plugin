#!/usr/bin/env bash
# Copy the UltiCloud developer-client contract export (schemas, fixtures,
# DEVELOPER-CLIENT.md, MANIFEST.json) into the test resources and verify every
# file against the SHA-256 recorded in MANIFEST.json.
#
# Usage: scripts/sync-contract.sh <ulticloud>/packages/contracts/generated/rest-v1/developer-client/1.0.0
#
# The copied directory is frozen upstream; never edit it by hand. A newer
# contract version is copied into its own directory.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <path to developer-client/<version> export directory>" >&2
  exit 2
fi

src="${1%/}"
if [ ! -f "$src/MANIFEST.json" ]; then
  echo "error: $src/MANIFEST.json not found" >&2
  exit 1
fi

version="$(basename "$src")"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$repo_root/src/test/resources/ulticloud/developer-client/$version"

rm -rf "$dest"
mkdir -p "$dest"
cp -R "$src/." "$dest/"

# MANIFEST.json lists each file with its sha256; check every one.
python3 - "$dest" <<'PY'
import hashlib, json, os, sys
dest = sys.argv[1]
manifest = json.load(open(os.path.join(dest, "MANIFEST.json"), encoding="utf-8"))
files = manifest["files"] if isinstance(manifest, dict) and "files" in manifest else manifest
entries = files.items() if isinstance(files, dict) else ((f["path"], f["sha256"]) for f in files)
bad = 0
count = 0
for path, digest in entries:
    if isinstance(digest, dict):
        digest = digest["sha256"]
    with open(os.path.join(dest, path), "rb") as fh:
        actual = hashlib.sha256(fh.read()).hexdigest()
    count += 1
    if actual != digest:
        print("sha256 mismatch: " + path, file=sys.stderr)
        bad += 1
if bad:
    sys.exit(1)
print("verified %d files against MANIFEST.json" % count)
PY
