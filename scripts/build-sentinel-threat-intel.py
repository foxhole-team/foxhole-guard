#!/usr/bin/env python3
"""Build the bundled SENTINEL threat-intel seed from the public stalkerware indicator set.

Source: https://github.com/AssoEchap/stalkerware-indicators (`ioc.yaml`), CC-BY-4.0.

Why this source and only this source: it is the one public dataset that publishes exactly what
the app's risk scorer matches on — Android application ids and signing-certificate fingerprints —
without inventing a mapping. Network indicators, YARA rules and general anti-malware feeds are
deliberately not imported: the first two need engines the app does not have, and the third has a
different threat model. SENTINEL is about what is installed on THIS phone and watching its owner.

Certificates arrive as SHA-1 (androguard's `cert.sha1_fingerprint`). SHA-1 to SHA-256 is a second
preimage, not a conversion, so they populate the document's `certsSha1` band; the app computes both
digests from the same certificate bytes.

Usage:
    scripts/build-sentinel-threat-intel.py [--source ioc.yaml] [--revision <sha>]

With no --source the upstream file is fetched over HTTPS. The output is written to
app/src/main/assets/sentinel/threat-intel.json, and provenance next to it in
threat-intel.source.json so a reviewer can tell where every line came from.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

UPSTREAM = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"
REPO = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = REPO / "app/src/main/assets/sentinel/threat-intel.json"
PROVENANCE = REPO / "app/src/main/assets/sentinel/threat-intel.source.json"
SCHEMA = 2

PACKAGE_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$", re.IGNORECASE)
SHA1_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)


def parse_ioc(text: str) -> tuple[list[str], list[str], int, int]:
    """Read the flat `- name:` list without a YAML dependency.

    The document is a list of mappings whose only nested values are lists of scalars, so a reader
    that tracks the current `- ` entry and the current key covers it exactly. Anything that does not
    look like an application id or a 40-hex fingerprint is dropped rather than guessed at.

    Only `type: stalkerware` entries are imported. Upstream also has a `watchware` class for
    consensual monitoring, and a match here makes the app tell its owner that an installed
    application is a KNOWN THREAT — a claim that is wrong when the owner installed the family
    locator on purpose. Today the file happens to be stalkerware end to end; this keeps a future
    addition from silently becoming an accusation.
    """
    packages: set[str] = set()
    certs: set[str] = set()
    entries = 0
    skipped = 0
    key: str | None = None
    entry_packages: set[str] = set()
    entry_certs: set[str] = set()
    entry_type: str | None = None

    def flush() -> None:
        nonlocal entries, skipped, entry_packages, entry_certs, entry_type
        if entry_type is None and not entry_packages and not entry_certs:
            return
        entries += 1
        if entry_type == "stalkerware":
            packages.update(entry_packages)
            certs.update(entry_certs)
        else:
            skipped += 1
        entry_packages = set()
        entry_certs = set()
        entry_type = None

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if line.startswith("- "):
            flush()
            key = line[2:].split(":", 1)[0].strip()
            continue
        stripped = line.strip()
        if stripped.startswith("- "):
            value = stripped[2:].strip().strip("'\"")
            if key == "packages" and PACKAGE_RE.match(value):
                entry_packages.add(value.lower())
            elif key == "certificates" and SHA1_RE.match(value):
                entry_certs.add(value.lower())
            continue
        if ":" in stripped:
            key, _, inline = stripped.partition(":")
            key = key.strip()
            if key == "type":
                entry_type = inline.strip().strip("'\"").lower()
    flush()
    return sorted(packages), sorted(certs), entries, skipped


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=pathlib.Path)
    parser.add_argument("--revision", default="")
    args = parser.parse_args()

    if args.source:
        raw = args.source.read_bytes()
    else:
        with urllib.request.urlopen(UPSTREAM, timeout=60) as response:
            raw = response.read()

    packages, certs, entries, skipped = parse_ioc(raw.decode("utf-8"))
    if not packages:
        print("refusing to write an empty seed: the source parsed to zero packages", file=sys.stderr)
        return 1

    document = {
        "schema": SCHEMA,
        "packages": packages,
        "certs": [],
        "certsSha1": certs,
    }
    OUTPUT.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")

    PROVENANCE.write_text(
        json.dumps(
            {
                "source": "https://github.com/AssoEchap/stalkerware-indicators",
                "file": "ioc.yaml",
                "revision": args.revision,
                "license": "CC-BY-4.0",
                "attribution": "Echap (https://echap.eu.org) and contributors",
                "sourceSha256": hashlib.sha256(raw).hexdigest(),
                "entries": entries,
                "skippedNonStalkerware": skipped,
                "packages": len(packages),
                "certsSha1": len(certs),
                "builtBy": "scripts/build-sentinel-threat-intel.py",
            },
            indent=2,
        )
        + "\n"
    )
    print(
        f"{len(packages)} packages, {len(certs)} SHA-1 certificates "
        f"from {entries} entries ({skipped} skipped as non-stalkerware)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
