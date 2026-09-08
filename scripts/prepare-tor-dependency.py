#!/usr/bin/env python3
"""Migrate the pinned Conjure client's two legacy STUN imports without altering the module cache."""

import hashlib
import json
from pathlib import Path
import sys
import shutil


def prepare(cache: Path, output: Path, manifest: Path) -> Path:
    pin = json.loads(manifest.read_text())
    source = cache / (pin["module"] + "@" + pin["version"])
    original_mod = (source / "go.mod").read_bytes()
    if hashlib.sha256(original_mod).hexdigest() != pin["goModSha256"]:
        raise ValueError("Conjure module requirements changed before the reviewed STUN migration")
    if output.name != "conjure-patched" or output.is_symlink():
        raise ValueError("Expected a dedicated conjure-patched build directory")
    if output.exists():
        for entry in [output, *output.rglob("*")]:
            entry.chmod(0o755 if entry.is_dir() else 0o644)
        shutil.rmtree(output)
    shutil.copytree(source, output)
    for directory in [output, *output.rglob("*")]:
        directory.chmod(0o755 if directory.is_dir() else 0o644)
    for relative, digest in pin["imports"].items():
        original = source / relative
        content = original.read_bytes()
        if hashlib.sha256(content).hexdigest() != digest:
            raise ValueError(f"Conjure STUN import patch input changed: {relative}")
        old = b'"github.com/pion/stun"'
        if content.count(old) != 1:
            raise ValueError(f"Expected one legacy STUN import: {relative}")
        patched = output / relative
        patched.parent.mkdir(parents=True, exist_ok=True)
        patched.write_bytes(content.replace(old, b'"github.com/pion/stun/v3"'))
    module = output / "go.mod"
    module.write_text(module.read_text().replace("github.com/pion/stun v0.6.1", "github.com/pion/stun/v3 v3.1.5"))
    return output


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    print(prepare(Path(sys.argv[1]), Path(sys.argv[2]),
                  root / "config/native/conjure/stun-v3-patch.json"))
