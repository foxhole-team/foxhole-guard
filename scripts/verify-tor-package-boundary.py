#!/usr/bin/env python3
"""Resolve both Android transport graphs and enforce narrowly documented package exclusions."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent


def objects(text):
    decoder = json.JSONDecoder()
    while text.strip():
        value, end = decoder.raw_decode(text.lstrip())
        text = text.lstrip()[end:]
        yield value


def inventory(native, ndk, output):
    go = native / "go1.26.8/bin/go"
    version = subprocess.check_output([str(go), "version"], text=True)
    if "go1.26.8 " not in version:
        raise ValueError("Tor package evidence requires pinned Go 1.26.8")
    environment = dict(os.environ, GOTOOLCHAIN="local", GOMODCACHE=str(native / "gomodcache"),
                       GOOS="android", CGO_ENABLED="1", GOPROXY="off")
    for key in ("GOROOT", "GOTOOLDIR", "GOFLAGS"):
        environment.pop(key, None)
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    components, packages = {}, {}
    for arch, compiler in (("arm64", "aarch64-linux-android26-clang"), ("arm", "armv7a-linux-androideabi26-clang")):
        for transport, target in (("lyrebird", "./cmd/lyrebird"), ("conjure", "./client")):
            environment.update(GOARCH=arch, GOARM="7", CC=str(ndk / "toolchains/llvm/prebuilt" / host / "bin" / compiler))
            command = [str(go), "list", "-mod=readonly", "-modfile=" + str(ROOT / "config/native" / transport / "go.mod"),
                       "-deps", "-json", target]
            result = subprocess.run(command, cwd=native / "tor-transports" / transport,
                                    env=environment, text=True, capture_output=True, check=True)
            names = []
            for package in objects(result.stdout):
                name = package["ImportPath"]
                if name == "github.com/pion/stun" or name.startswith("golang.org/x/crypto/openpgp"):
                    raise ValueError(f"Forbidden transport dependency became reachable: {name}")
                names.append(name)
                module = package.get("Module")
                if not module or module.get("Main"):
                    continue
                replacement = module.get("Replace")
                if replacement and replacement.get("Version"):
                    module = replacement
                elif replacement and (module["Path"], module.get("Version"), replacement["Path"]) != (
                        "github.com/refraction-networking/conjure", "v0.9.1", "../conjure-patched"):
                    raise ValueError("Unreviewed local Go replacement")
                ref = "pkg:golang/" + urllib.parse.quote(module["Path"], safe="/") + "@" + module["Version"]
                components[ref] = {"type": "library", "name": module["Path"], "version": module["Version"], "purl": ref, "bom-ref": ref}
            packages[f"{transport}/{arch}"] = sorted(names)
    ref = "pkg:golang/stdlib@1.26.8"
    components[ref] = {"type": "library", "name": "stdlib", "version": "1.26.8", "purl": ref, "bom-ref": ref}
    source_digests = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted((ROOT / "config/native").rglob("*")) if p.is_file()}
    output.mkdir(parents=True, exist_ok=True)
    (output / "tor-package-evidence.json").write_text(json.dumps({"inputs": source_digests, "packages": packages}, indent=2) + "\n")
    document = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
                "metadata": {"component": {"type": "application", "name": "foxhole-tor-transports", "bom-ref": "tor:root"}},
                "components": [components[key] for key in sorted(components)],
                "dependencies": [{"ref": "tor:root", "dependsOn": sorted(components)}]}
    (output / "tor-source.cdx.json").write_text(json.dumps(document, indent=2) + "\n")
    print(f"Tor Android package boundary PASS: {len(components)} modules across {len(packages)} delivery graphs")


if __name__ == "__main__":
    inventory(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
