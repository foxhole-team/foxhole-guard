#!/usr/bin/env python3
"""Bind only the tracked i2pd source consumed by the native snapshot builder."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import stat
import subprocess
import time


def git_output(root, *arguments):
    command = ["git", "--no-pager", "--no-replace-objects", "-C", str(root), *arguments]
    with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as process:
        assert process.stdout is not None
        output = bytearray()
        deadline = time.monotonic() + 15
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not selector.select(remaining):
                        raise ValueError("Tracked i2pd metadata command exceeded deadline")
                    chunk = os.read(process.stdout.fileno(), 65536)
                    if not chunk:
                        break
                    output.extend(chunk)
                    if len(output) > 2 * 1024 * 1024:
                        raise ValueError("Tracked i2pd metadata exceeds its size bound")
            if process.wait(timeout=max(0.01, deadline - time.monotonic())) != 0:
                raise ValueError("Actual tracked i2pd Git metadata is unavailable")
            return bytes(output)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)


def file_hash(path):
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > 64 * 1024 * 1024:
        raise ValueError("Unsupported tracked i2pd file")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_mode) != (
            after.st_ino, after.st_size, after.st_mtime_ns, after.st_mode):
        raise ValueError("Tracked i2pd source changed during fingerprinting")
    return digest, before.st_size


def source_snapshot(root):
    root = Path(root).resolve(strict=True)
    head = git_output(root, "rev-parse", "HEAD").decode().strip()
    epoch = git_output(root, "show", "-s", "--format=%ct", "HEAD").decode().strip()
    if not re.fullmatch(r"[a-f0-9]{40}", head) or not re.fullmatch(r"[0-9]+", epoch):
        raise ValueError("Actual i2pd source revision and epoch required")
    encoded = git_output(root, "ls-files", "--cached", "-z")
    names = sorted(os.fsdecode(name) for name in encoded.split(b"\0") if name)
    if not 1 <= len(names) <= 10000 or len(set(names)) != len(names):
        raise ValueError("Bounded unique tracked i2pd paths required")
    records = {}
    for name in names:
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or ".git" in relative.parts:
            raise ValueError("Unsafe tracked i2pd source path")
        path = root / name
        parent = root
        for part in relative.parts[:-1]:
            parent /= part
            if parent.is_symlink():
                raise ValueError("Tracked i2pd parent link is unsupported")
        try:
            info = path.lstat()
        except FileNotFoundError:
            records[name] = {"kind": "deleted"}
            continue
        mode = stat.S_IMODE(info.st_mode)
        if stat.S_ISLNK(info.st_mode):
            target = os.readlink(path)
            if len(os.fsencode(target)) > 4096:
                raise ValueError("Tracked i2pd link exceeds size bound")
            if Path(target).is_absolute():
                raise ValueError("Tracked i2pd link must be relative to its source snapshot")
            target_parent = path.parent
            for part in PurePosixPath(target).parts[:-1]:
                target_parent = target_parent.parent if part == ".." else target_parent / part
                if not target_parent.is_relative_to(root) or target_parent.is_symlink():
                    raise ValueError("Tracked i2pd target parent link is unsupported")
            resolved = path.resolve()
            if not resolved.is_relative_to(root) or str(resolved.relative_to(root)) not in names:
                raise ValueError("Tracked i2pd link leaves the tracked source snapshot")
            if not resolved.is_file():
                raise ValueError("Tracked i2pd link target is missing")
            digest, size = file_hash(resolved)
            after_link = path.lstat()
            if (info.st_ino, info.st_mode, info.st_mtime_ns, target) != (
                    after_link.st_ino, after_link.st_mode, after_link.st_mtime_ns, os.readlink(path)):
                raise ValueError("Tracked i2pd link changed during fingerprinting")
            records[name] = {"kind": "link", "mode": mode, "target": target,
                             "resolvedTarget": str(resolved.relative_to(root)),
                             "targetSha256": digest, "targetBytes": size}
        elif stat.S_ISREG(info.st_mode):
            if not path.resolve(strict=True).is_relative_to(root):
                raise ValueError("Tracked i2pd file leaves its source root")
            digest, size = file_hash(path)
            if not path.resolve(strict=True).is_relative_to(root):
                raise ValueError("Tracked i2pd file moved outside its source root")
            records[name] = {"kind": "file", "mode": mode, "sha256": digest, "bytes": size}
        else:
            raise ValueError("Unsupported tracked i2pd source type")
    if git_output(root, "rev-parse", "HEAD").decode().strip() != head:
        raise ValueError("i2pd source revision changed during fingerprinting")
    if git_output(root, "ls-files", "--cached", "-z") != encoded:
        raise ValueError("i2pd tracked source list changed during fingerprinting")
    if git_output(root, "show", "-s", "--format=%ct", "HEAD").decode().strip() != epoch:
        raise ValueError("i2pd source epoch changed during fingerprinting")
    result = {"head": head, "epoch": epoch, "files": records}
    result["fingerprint"] = hashlib.sha256(json.dumps(result, sort_keys=True, separators=(",", ":"),
                                                     ensure_ascii=True).encode()).hexdigest()
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    print(source_snapshot(args.root)["fingerprint"])
