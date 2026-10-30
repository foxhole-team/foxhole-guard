#!/usr/bin/env python3
"""Verify normal source-built JNI against its source and canonical legal inputs."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import sys
import tempfile

sys.dont_write_bytecode = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-root", type=Path, required=True)
    parser.add_argument("--core-root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--install-kind", choices=("core", "sentinel"), required=True)
    args = parser.parse_args()
    root = args.app_root.absolute()
    script = root / "scripts/source-legal-snapshot.py"
    spec = importlib.util.spec_from_file_location("fdroid_native_legal", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    producer = module.read(root / "config/foxcore-revision.txt").decode().strip()
    material = module.load(
        root / "third_party/licenses/source-legal/android4/snapshot.json", root,
        args.core_root.absolute(), producer, "android4",
        root / "app/build/fdroidNativeSource/core",
        root / "app/build/fdroidNativeSource/sentinel/jniLibs", args.manifest.absolute(), True,
    )
    module.recheck(material, args.core_root.absolute())
    library = "libfoxhole_native.so" if args.install_kind == "core" else "libfoxhole_sentinel_yara.so"
    destination = root / ("app/build/generated/foxCoreNativeLibs" if args.install_kind == "core"
                          else "app/build/generated/sentinelNative/jniLibs")
    rows = material["nativeLibraries"][args.install_kind]
    expected = {row["abi"] + "/" + library for row in rows.values()}
    existing = {str(path.relative_to(destination)) for path in destination.rglob("*")
                if path.is_file() or path.is_symlink()}
    module.need(existing <= expected, "Unexpected normal native task output")
    for row in rows.values():
        relative = row["abi"] + "/" + library
        body = module.read(material["roots"][args.install_kind] / relative,
                           128 * 1024**2, row["sha256"], nlinks=(1, 2))
        target = destination / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        module.need(not any(path.is_symlink() for path in (target, *target.parents)),
                    "Linked normal native output refused")
        descriptor, temporary = tempfile.mkstemp(prefix=".source-native-", dir=target.parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(body)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        module.read(target, 128 * 1024**2, row["sha256"])
    module.need({str(path.relative_to(destination)) for path in destination.rglob("*")
                 if path.is_file() or path.is_symlink()} == expected, "Native task output omitted an ABI")
    module.recheck(material, args.core_root.absolute())
    print(json.dumps({"exitCode": 0, "sourceCommit": material["sourceCommit"],
                      "sourceTree": material["sourceTree"], "allFourABIsVerified": True,
                      "installedKind": args.install_kind}))


if __name__ == "__main__":
    main()
