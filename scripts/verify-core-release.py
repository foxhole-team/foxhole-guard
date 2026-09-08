#!/usr/bin/env python3
"""Check the authenticated Core release against the exact source revision and shipped ABI bytes."""

import hashlib
import json
from pathlib import Path
import shutil
import sys


def verify(source, release, revision, abis, output=None):
    manifest = json.loads((release / "MANIFEST.json").read_text())
    provenance = json.loads((release / "RELEASE.json").read_text())
    if manifest["git"]["commit"] != revision or manifest["git"]["dirty"] or provenance["main_commit"] != revision:
        raise ValueError("Core release does not belong to the pinned clean source commit")
    if (release / "Cargo.lock").read_bytes() != (source / "Cargo.lock").read_bytes():
        raise ValueError("Core release dependency lock differs from pinned source")
    rows = {row["abi"]: row for row in manifest["libs"]}
    if len(rows) != len(manifest["libs"]) or set(rows) != {"arm64-v8a", "armeabi-v7a"}:
        raise ValueError("Core release ABI inventory is invalid")
    for abi in abis:
        row = rows[abi]
        library = release / "jniLibs" / abi / "libfoxhole_native.so"
        data = library.read_bytes()
        if len(data) != row["bytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
            raise ValueError("Core release ELF differs from authenticated manifest")
        if output is not None:
            destination = output / abi / library.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(library, destination)


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4].split(),
           Path(sys.argv[5]) if len(sys.argv) > 5 else None)
    print("Authenticated Core release matches pinned source and ELF hashes")
