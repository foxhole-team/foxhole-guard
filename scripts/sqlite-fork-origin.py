#!/usr/bin/env python3
"""Verify the exact SQLite WAL security backport before deriving an advisory policy."""
import argparse
import hashlib
import json
import os
import stat
from pathlib import Path
import re
import tomllib


METADATA = "config/source-forks/libsqlite3-sys-0.37.0.json"
UPSTREAM_SHA256 = "b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1"
UPSTREAM_FILES_SHA256 = "34f98feda3895192091ab2bddb43d24c9f1fcd98961704d2f389c23e62ae7b17"
PATCH_SHA256 = "79d17741e57ce5e8d09ee3f992bd3ca7946cd35f6e0efbf7599b92523ae53f4b"
ADVISORY = "RUSTSEC-2026-0459"
CHANGED = {"sqlite3/sqlite3.c", "sqlcipher/sqlite3.c"}
NOTICE_PREFIX = "source-forks/libsqlite3-sys-0.37.0/"
SQLITE_INPUTS = {
    "vendor/libsqlite3-sys/sqlite3/sqlite3.c": (9390518, "1792e34d22e514ffb21f7938cdc63a193bd75331a88eeed75d529d5ff8c3e2b9"),
    "vendor/libsqlite3-sys/sqlcipher/sqlite3.c": (9515245, "da7cdd55cdee08b61ead43a9725032c2f7b0e19d07e3fb7d95b21f144b37b3f8"),
}
BEFORE = "  sz = (sz&0xfe00) + ((sz&0x0001)<<16);\n  testcase( sz<=32768 );"
AFTER = "  sz = (sz&0xfe00) + ((sz&0x0001)<<16);\n  if( nOut>sz ){\n    memset(pOut+sz, 0, nOut-sz);\n    nOut = sz;\n  }\n  testcase( sz<=32768 );"
OLD_RETURN = "return sqlite3OsRead(pWal->pWalFd, pOut, (nOut>sz ? sz : nOut), iOffset);"
NEW_RETURN = "return sqlite3OsRead(pWal->pWalFd, pOut, nOut, iOffset);"


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def validate_origin(read_source, names):
    raw = read_source(METADATA)[0]
    metadata = json.loads(raw)
    require(metadata["upstreamCrateSHA256"] == UPSTREAM_SHA256, "Unexpected upstream crate")
    require(metadata["crate"] == "libsqlite3-sys" and metadata["version"] == "0.37.0", "Unexpected crate identity")
    require(metadata["vendorPath"] == "vendor/libsqlite3-sys", "Unexpected vendor path")
    require(metadata["patchPath"] == "patches/libsqlite3-sys-0.37.0-CVE-2026-119375.patch", "Unexpected patch path")
    require(metadata["advisory"] == ADVISORY and set(metadata["changedPaths"]) == CHANGED, "Unexpected backport scope")
    require(sha(json.dumps(metadata["upstreamFiles"], sort_keys=True, separators=(",", ":")).encode()) == UPSTREAM_FILES_SHA256, "Upstream file inventory changed")
    manifest = tomllib.loads(read_source("Cargo.toml")[0].decode())
    require(manifest["patch"]["crates-io"]["libsqlite3-sys"] == {"path": "vendor/libsqlite3-sys"}, "Fork is not selected")
    packages = [x for x in tomllib.loads(read_source("Cargo.lock")[0].decode())["package"] if x["name"] == "libsqlite3-sys"]
    require(len(packages) == 1 and packages[0]["version"] == "0.37.0" and "source" not in packages[0], "Lockfile does not select the exact local fork")
    prefix = metadata["vendorPath"] + "/"
    vendor_names = {name for name in names if name.startswith(prefix)}
    actual = {name[len(prefix):]: sha(read_source(name)[0]) for name in vendor_names}
    original = {x["path"]: x["sha256"] for x in metadata["upstreamFiles"]}
    expected = {x["path"]: x["sha256"] for x in metadata["vendorFiles"]}
    require(len(original) == len(metadata["upstreamFiles"]) and len(expected) == len(metadata["vendorFiles"]), "Duplicate file identity")
    require(actual == expected and set(original) == set(expected), "Vendor file digest or inventory changed")
    require({p for p in original if original[p] != expected[p]} == CHANGED, "Unreviewed upstream modification")
    for name in CHANGED:
        text = read_source(prefix + name)[0].decode()
        require(text.count(AFTER) == 1 and text.count(NEW_RETURN) == 1, "WAL fix is missing or ambiguous")
        old = text.replace(AFTER, BEFORE).replace(NEW_RETURN, OLD_RETURN)
        require(sha(old.encode()) == original[name], "WAL fix differs from the exact upstream backport")
    patch_raw = read_source(metadata["patchPath"])[0]
    require(sha(patch_raw) == metadata["patchSHA256"] == PATCH_SHA256, "Patch provenance changed")
    return {"advisory": ADVISORY, "crate": "libsqlite3-sys", "version": "0.37.0", "metadataSHA256": sha(raw), "patchSHA256": sha(patch_raw), "vendorFiles": len(actual),
            "sourceBound": True, "vendorManifest": "vendor/libsqlite3-sys/Cargo.toml",
            "noticeFiles": {NOTICE_PREFIX + "ORIGIN.json": raw,
                            NOTICE_PREFIX + "upstream-security.patch": patch_raw,
                            NOTICE_PREFIX + "FOXHOLE-MODIFICATIONS.md": read_source("config/source-forks/libsqlite3-sys-0.37.0.NOTICE.md")[0],
                            NOTICE_PREFIX + "LICENSE": read_source(prefix + "LICENSE")[0]}}


def source_bytes(root, name):
    path = root / name
    require(not any(p.is_symlink() for p in (path, *path.parents)), "Source symlinks are forbidden")
    facts, parent = path.lstat(), path.parent.lstat()
    fingerprint = lambda entry: tuple(getattr(entry, field) for field in ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns"))
    require(stat.S_ISREG(facts.st_mode) and facts.st_nlink == 1 and not facts.st_mode & 0o022 and not parent.st_mode & 0o022, "Source file and parent must be regular and protected")
    if name in SQLITE_INPUTS:
        size, expected = SQLITE_INPUTS[name]
        require(facts.st_size == size, "Exact SQLite source size required before reading")
    else:
        require(0 < facts.st_size <= 1048576, "Bounded regular source required")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as stream:
        require(fingerprint(os.fstat(stream.fileno())) == fingerprint(facts), "Source inode changed")
        data = stream.read(facts.st_size + 1)
        require(fingerprint(os.fstat(stream.fileno())) == fingerprint(facts) and fingerprint(path.lstat()) == fingerprint(facts) and fingerprint(path.parent.lstat()) == fingerprint(parent) and len(data) == facts.st_size, "Source changed while reading")
    if name in SQLITE_INPUTS:
        require(sha(data) == expected, "Exact SQLite source digest differs")
    return data, stat.S_IMODE(facts.st_mode)


def validate(root):
    names = {str(p.relative_to(root)) for p in (root / "vendor/libsqlite3-sys").rglob("*") if p.is_file()}
    origin = validate_origin(lambda name: source_bytes(root, name), names)
    return {key: value for key, value in origin.items() if key != "noticeFiles"}


def policy(root, source, destination):
    proof = validate(root)
    text = source.read_text()
    require(ADVISORY not in text, "Advisory is already suppressed in the source policy")
    text += '\n[[advisories.ignore]]\nid = "' + ADVISORY + '"\nreason = "Exact local libsqlite3-sys 0.37.0 WAL backport verified by scripts/sqlite-fork-origin.py; metadata SHA256 ' + proof["metadataSHA256"] + '"\n'
    tomllib.loads(text)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text)
    return proof


def identity_url(proof, revision):
    require(re.fullmatch("[0-9a-f]{40}", revision) is not None, "Actual source revision required")
    return "pkg:generic/foxhole/libsqlite3-sys@0.37.0?core_commit=" + revision + "&source_sha256=" + proof["metadataSHA256"]


def component_properties(proof, revision):
    identity_url(proof, revision)
    return [{"name": name, "value": value} for name, value in (
        ("foxhole:source-kind", "vendored-modified"),
        ("foxhole:upstream-cargo-purl", "pkg:cargo/libsqlite3-sys@0.37.0"),
        ("foxhole:upstream-crate-sha256", UPSTREAM_SHA256),
        ("foxhole:source-fork-metadata-sha256", proof["metadataSHA256"]),
        ("foxhole:source-patch-sha256", PATCH_SHA256),
        ("cargo:source-revision", revision))]


def validate_component(component, proof, revision):
    require(proof.get("sourceBound") is True or proof.get("sourcePinsBound") is True, "SQLite fork has no source binding")
    require(component.get("name") == "libsqlite3-sys" and component.get("version") == "0.37.0", "SQLite component identity differs")
    require(component.get("purl") == identity_url(proof, revision), "SQLite component source URL differs")
    rows = component.get("properties", [])
    require(isinstance(rows, list) and all(isinstance(row, dict) and set(row) == {"name", "value"} for row in rows), "Invalid SQLite component properties")
    require(len({row["name"] for row in rows}) == len(rows), "Duplicate SQLite component property")
    expected = component_properties(proof, revision)
    require(all(rows.count(row) == 1 for row in expected), "SQLite component provenance differs")
    require(not any(row.get("name") == "cargo:workspace-manifest" for row in rows), "SQLite fork cannot be a workspace component")


def validate_packaged_origin(read_notice, pins):
    raw = read_notice(NOTICE_PREFIX + "ORIGIN.json")
    metadata = json.loads(raw)
    require(sha(raw) == pins.get(METADATA), "Packaged SQLite origin differs from compiled source pins")
    require(metadata.get("crate") == "libsqlite3-sys" and metadata.get("version") == "0.37.0" and metadata.get("vendorPath") == "vendor/libsqlite3-sys", "Packaged SQLite identity differs")
    require(metadata.get("advisory") == ADVISORY and metadata.get("upstreamCrateSHA256") == UPSTREAM_SHA256, "Packaged SQLite upstream identity differs")
    require(sha(json.dumps(metadata["upstreamFiles"], sort_keys=True, separators=(",", ":")).encode()) == UPSTREAM_FILES_SHA256, "Packaged SQLite upstream inventory differs")
    original = {row["path"]: row["sha256"] for row in metadata["upstreamFiles"]}
    effective = {row["path"]: row["sha256"] for row in metadata["vendorFiles"]}
    expected = dict(original)
    expected.update({name.removeprefix("vendor/libsqlite3-sys/"): value[1] for name, value in SQLITE_INPUTS.items()})
    require(len(effective) == len(metadata["vendorFiles"]) and effective == expected and set(metadata["changedPaths"]) == CHANGED, "Packaged SQLite effective inventory differs")
    selected = {name.removeprefix("vendor/libsqlite3-sys/"): value for name, value in pins.items() if name.startswith("vendor/libsqlite3-sys/")}
    require(selected == effective, "Packaged SQLite compiled source closure differs")
    patch = read_notice(NOTICE_PREFIX + "upstream-security.patch")
    notice = read_notice(NOTICE_PREFIX + "FOXHOLE-MODIFICATIONS.md")
    license_raw = read_notice(NOTICE_PREFIX + "LICENSE")
    require(metadata.get("patchPath") == "patches/libsqlite3-sys-0.37.0-CVE-2026-119375.patch", "Packaged SQLite patch path differs")
    require(metadata.get("patchSHA256") == sha(patch) == pins.get(metadata["patchPath"]) == PATCH_SHA256, "Packaged SQLite patch differs")
    require(sha(notice) == pins.get("config/source-forks/libsqlite3-sys-0.37.0.NOTICE.md") and sha(license_raw) == original["LICENSE"], "Packaged SQLite notices differ")
    return {"advisory": ADVISORY, "crate": "libsqlite3-sys", "version": "0.37.0", "metadataSHA256": sha(raw), "patchSHA256": PATCH_SHA256,
            "sourcePinsBound": True, "vendorManifest": "vendor/libsqlite3-sys/Cargo.toml",
            "noticeFiles": {NOTICE_PREFIX + "ORIGIN.json": raw, NOTICE_PREFIX + "upstream-security.patch": patch,
                            NOTICE_PREFIX + "FOXHOLE-MODIFICATIONS.md": notice, NOTICE_PREFIX + "LICENSE": license_raw}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if bool(args.policy) != bool(args.output):
        parser.error("--policy and --output are required together")
    result = policy(args.root, args.policy, args.output) if args.policy else validate(args.root)
    print(json.dumps(result, sort_keys=True))
