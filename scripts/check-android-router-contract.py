#!/usr/bin/env python3
"""Offline source-contract check; optional hash-pinned JNI evidence is metadata only."""
import argparse
import hashlib
import json
import re
import stat
import subprocess
import sys
from pathlib import Path, PurePosixPath

HASH = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
JNI_PREFIX = "Java_com_foxhole_core_runtime_FoxholeNativeEngine_"
MIRRORS = {"RouterInstallCatalog.kt", "RouterTlsIdentity.kt", "RouterPackageUpdate.kt", "RouterSetupLicense.kt"}
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


class ContractError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ContractError(message)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def read(path, limit=1024 * 1024):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "Expected a regular single-link input")
    require(not info.st_mode & 0o022 and 0 < info.st_size <= limit, "Unsafe input mode or size")
    with path.open("rb") as stream:
        body = stream.read(limit + 1)
    require(len(body) == info.st_size, "Input changed while reading")
    return body


def within(root, relative):
    parts = PurePosixPath(relative)
    require(isinstance(relative, str) and not parts.is_absolute() and parts.parts and
            all(part not in ("..", ".") for part in parts.parts), "Unsafe contract-relative path")
    path = root.joinpath(*parts.parts)
    require(not path.is_symlink() and path.resolve().is_relative_to(root.resolve()), "Contract path escapes its root")
    return path


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def document(body):
    value = json.loads(body.decode("utf-8"), object_pairs_hook=unique_object)
    require(isinstance(value, dict), "Expected a JSON object")
    return value


def hashed(root, row):
    require(isinstance(row, dict) and HASH.fullmatch(row.get("sha256", "")), "Invalid content pin")
    body = read(within(root, row["path"]))
    require(digest(body) == row["sha256"], "Pinned contract content differs: " + row["path"])
    return body


def normalize(body, application):
    prefix = ("com.foxhole." + application + ".router").encode("ascii")
    require(body.startswith(b"package " + prefix + b"\n"), "Unexpected Kotlin namespace")
    return body.replace(prefix, b"com.foxhole.app.router")


def java_exports(body):
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", body.decode("utf-8"), flags=re.S)
    names = re.findall(r"\bpublic\s+static\s+native\s+[\w.\[\]]+\s+(native\w+)\s*\(", text)
    require(names and len(names) == len(set(names)), "Unsupported empty or overloaded native facade")
    return {JNI_PREFIX + name.replace("_", "_1") for name in names}


def check_sources(root, application, manifest):
    rows = manifest["kotlinMirrors"]
    require({row["name"] for row in rows} == MIRRORS and len(rows) == 4, "Unexpected mirror inventory")
    for row in rows:
        body = read(within(root, row["paths"][application]))
        require(digest(normalize(body, application)) == row["normalizedSha256"], "Router mirror differs: " + row["name"])
    facade = manifest["javaFacade"]
    body = read(within(root, facade["paths"][application]))
    require(digest(body) == facade["sha256"], "JNI Java facade bytes differ")
    required = set(manifest["jni"]["requiredExports"])
    require(java_exports(body) == required, "Java declaration/export contract differs")
    test = manifest["fixtureTest"]
    body = read(within(root, test["paths"][application]))
    require(digest(normalize(body, application)) == test["normalizedSha256"], "Shared fixture test wiring differs")
    require(len(required) == len(manifest["jni"]["requiredExports"]), "Duplicate required JNI export")
    return required


def native_evidence(args):
    require(HASH.fullmatch(args.elf_sha256 or "") and args.abi in ABIS, "JNI evidence needs exact selected ELF SHA-256 and ABI")
    if args.elf:
        require(args.readelf and not args.exports_sha256, "ELF evidence needs readelf, without exports-manifest pin")
        body = read(args.elf, 128 * 1024 * 1024)
        require(digest(body) == args.elf_sha256 and body.startswith(b"\x7fELF"), "Selected pinned Core ELF differs")
        expected = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}[args.abi]
        require(len(body) >= 64 and body[5] == 1 and int.from_bytes(body[16:18], "little") == 3 and
                (body[4], int.from_bytes(body[18:20], "little")) == expected,
                "Selected Core ELF ABI differs")
        result = subprocess.run([str(args.readelf), "--dyn-syms", "--wide", str(args.elf)],
                                check=True, capture_output=True, text=True, timeout=30)
        require(len(result.stdout) <= 8 * 1024 * 1024, "Oversized ELF symbol output")
        exports = set()
        for line in result.stdout.splitlines():
            fields = line.split()
            if (len(fields) >= 8 and fields[3] == "FUNC" and fields[4] in {"GLOBAL", "WEAK"} and
                    fields[5] in {"DEFAULT", "PROTECTED"} and fields[6] != "UND"):
                exports.add(fields[7].split("@", 1)[0])
        return exports, "pinned-elf-dynamic-symbols"
    require(not args.readelf and HASH.fullmatch(args.exports_sha256 or ""), "Verified export evidence needs its exact SHA-256")
    body = read(args.exports_manifest)
    require(digest(body) == args.exports_sha256, "Verified exports manifest pin differs")
    value = document(body)
    require(value.get("schema") == 1 and value.get("abi") == args.abi and value.get("elfSha256") == args.elf_sha256,
            "Verified exports manifest names another selected Core ELF")
    exports = value.get("exports")
    require(isinstance(exports, list) and exports and all(isinstance(item, str) for item in exports) and
            len(exports) == len(set(exports)), "Invalid verified export inventory")
    return set(exports), "hash-pinned-verified-export-manifest"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--reference", default="config/android-router-contract.json")
    parser.add_argument("--peer-root", type=Path)
    evidence = parser.add_mutually_exclusive_group()
    evidence.add_argument("--elf", type=Path)
    evidence.add_argument("--exports-manifest", type=Path)
    parser.add_argument("--elf-sha256")
    parser.add_argument("--exports-sha256")
    parser.add_argument("--abi", choices=sorted(ABIS))
    parser.add_argument("--readelf", type=Path)
    parser.add_argument("--require-jni", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    reference = document(read(within(root, args.reference)))
    require(reference.get("schema") == 1 and reference.get("application") in {"guard", "vpn"}, "Invalid application reference")
    application = reference["application"]
    origin = reference["canonicalOrigin"]
    require(origin.get("repository") == "foxhole-team/foxhole-core" and
            (origin.get("commit") is None and origin.get("status") == "PENDING_CLEAN_PUBLIC_COMMIT" or
             isinstance(origin.get("commit"), str) and COMMIT.fullmatch(origin["commit"]) and origin.get("status") == "PINNED"),
            "Canonical origin must use a fixed commit or explicit pending state")
    bundle = within(root, reference["bundle"])
    row = reference["manifest"]
    require(row["path"] == "manifest." + row["sha256"] + ".json", "Manifest is not content addressed")
    manifest = document(hashed(bundle, row))
    require(manifest.get("schema") == 1 and manifest.get("contract") == "android-router" and manifest.get("revision") == 1,
            "Unknown Android router contract revision")
    require(manifest.get("normalization") == "replace-exact-router-package-prefix-only-v1", "Unknown normalization")
    require(digest(read(Path(__file__).resolve())) == manifest["checkerSha256"], "Checker differs from canonical version")
    require(set(manifest["jni"]["allowedLegacyExtraExports"]) == {JNI_PREFIX + "nativeStart"} and
            manifest["jni"]["policy"] == "required-java-exports-subset", "Unexpected JNI compatibility policy")
    for fixture in manifest["fixtures"]:
        hashed(root, {"path": fixture["consumerPath"], "sha256": fixture["sha256"]})
    required = check_sources(root, application, manifest)
    if args.peer_root:
        check_sources(args.peer_root.resolve(), "vpn" if application == "guard" else "guard", manifest)
    native = {"status": "NOT_CHECKED", "scope": "No selected native evidence supplied"}
    if args.elf or args.exports_manifest:
        exports, kind = native_evidence(args)
        require(required <= exports, "Selected Core ELF misses required Java JNI exports")
        extra = {name for name in exports if name.startswith(JNI_PREFIX)} - required
        native = {"status": "REQUIRED_SUBSET_MATCH", "evidence": kind, "abi": args.abi,
                  "elfSha256": args.elf_sha256,
                  "knownLegacyExtras": sorted(extra & set(manifest["jni"]["allowedLegacyExtraExports"])),
                  "otherExtraCount": len(extra - set(manifest["jni"]["allowedLegacyExtraExports"]))}
    else:
        require(not any((args.elf_sha256, args.exports_sha256, args.abi, args.readelf)), "Unused JNI evidence options")
        require(not args.require_jni, "Selected pinned JNI evidence is required")
    print(json.dumps({"contractSha256": row["sha256"], "revision": 1, "application": application,
                      "sourceContract": "MATCH", "fixtures": "HASH_BOUND_NOT_EXECUTED", "jni": native,
                      "canonicalOrigin": origin, "scope": "Source metadata contract, not a native producer or release qualification"}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as failure:
        print("Android router contract rejected: " + str(failure), file=sys.stderr)
        sys.exit(1)
