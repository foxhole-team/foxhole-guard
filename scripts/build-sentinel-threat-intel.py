#!/usr/bin/env python3
"""Build the bundled SENTINEL threat-intel seed from the public stalkerware indicator set.

Source: https://github.com/AssoEchap/stalkerware-indicators (`ioc.yaml`), CC-BY-4.0.

Why this source and only this source: it is the one public dataset that publishes exactly what
the app matches on — Android application ids, signing-certificate fingerprints, and the C2 and
distribution hosts the network matcher checks flow destinations against — without inventing a
mapping. YARA rules and general anti-malware feeds are deliberately not imported: the first needs
an engine the app does not have, and the second has a different threat model. SENTINEL is about
what is installed on THIS phone and watching its owner.

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
SCHEMA = 4

PACKAGE_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$", re.IGNORECASE)
SHA1_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)
# Mirrors foxhole-db/build-threat-intel.sh: seed and signed feed must produce the same sets from the same upstream revision, or a device changes behavior on first update.
DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
IPV4_RE = re.compile(r"^(?:\d{1,3}\.){3}\d{1,3}$")
IPV6_RE = re.compile(r"^[0-9a-f:]{3,45}$")

# Mirrors com.foxhole.core.model.ThreatIndicatorKind.
COMMAND_AND_CONTROL = "COMMAND_AND_CONTROL"
MALWARE_DISTRIBUTION = "MALWARE_DISTRIBUTION"
UNCLASSIFIED = "UNCLASSIFIED"
# Louder first, so a duplicate keeps the stronger claim upstream actually made.
KIND_RANK = {COMMAND_AND_CONTROL: 2, MALWARE_DISTRIBUTION: 1, UNCLASSIFIED: 0}


def kind_for(key: str, container: str | None) -> str:
    """What upstream said this indicator is, never what it might be."""
    if container == "c2" and key in ("domains", "ips"):
        return COMMAND_AND_CONTROL
    if key == "websites":
        return MALWARE_DISTRIBUTION
    return UNCLASSIFIED


def normalize_domain(raw: str) -> str | None:
    value = raw.strip().lower()
    # Feeds mix bare hosts with URLs; keep the host and drop the rest.
    value = value.split("//")[-1].split("/")[0].split("?")[0].strip().strip(".")
    if value.startswith("*."):
        value = value[2:]
    value = value.split(":")[0]
    if not value or len(value) > 253 or not DOMAIN_RE.match(value) or "." not in value:
        return None
    return value


def normalize_ip(raw: str) -> str | None:
    value = raw.strip().lower()
    if IPV4_RE.match(value):
        octets = value.split(".")
        if all(0 <= int(o) <= 255 and (o == "0" or not o.startswith("0")) for o in octets):
            return value
        return None
    if ":" in value and IPV6_RE.match(value):
        return value
    return None


def parse_ioc(text: str) -> tuple[list[str], list[str], list[str], list[str], dict[str, str], int, int]:
    """Read the flat `- name:` list without a YAML dependency.

    The document is a list of mappings whose only nested values are lists of scalars, so a reader
    that tracks the current `- ` entry and the current key covers it exactly — including the `c2:`
    mapping, whose `domains:`/`ips:` sub-keys land on the same key tracker. Anything that does not
    look like an application id, a 40-hex fingerprint, a hostname or a literal address is dropped
    rather than guessed at.

    The `c2:` container is also the classification. Upstream separates the family's control
    endpoints from the vendor's own `websites:`, and flattening the two into one indicator list is
    what let a hit on a vendor's marketing host score like a hit on a controller. `domains:`/`ips:`
    seen inside `c2:` are COMMAND_AND_CONTROL, `websites:` are MALWARE_DISTRIBUTION, and anything
    upstream lists without that context stays UNCLASSIFIED rather than being guessed at.

    Only `type: stalkerware` entries are imported. Upstream also has a `watchware` class for
    consensual monitoring, and a match here makes the app tell its owner that an installed
    application is a KNOWN THREAT — a claim that is wrong when the owner installed the family
    locator on purpose. Today the file happens to be stalkerware end to end; this keeps a future
    addition from silently becoming an accusation.
    """
    packages: set[str] = set()
    certs: set[str] = set()
    domains: set[str] = set()
    ips: set[str] = set()
    kinds: dict[str, str] = {}
    entries = 0
    skipped = 0
    key: str | None = None
    container: str | None = None
    entry_packages: set[str] = set()
    entry_certs: set[str] = set()
    entry_domains: dict[str, str] = {}
    entry_ips: dict[str, str] = {}
    entry_type: str | None = None

    def classify(indicator: str, kind: str) -> None:
        if KIND_RANK[kind] > KIND_RANK.get(kinds.get(indicator), -1):
            kinds[indicator] = kind

    def flush() -> None:
        nonlocal entries, skipped, entry_packages, entry_certs, entry_domains, entry_ips, entry_type
        if entry_type is None and not entry_packages and not entry_certs and not entry_domains and not entry_ips:
            return
        entries += 1
        if entry_type == "stalkerware":
            packages.update(entry_packages)
            certs.update(entry_certs)
            domains.update(entry_domains)
            ips.update(entry_ips)
            for indicator, kind in {**entry_domains, **entry_ips}.items():
                classify(indicator, kind)
        else:
            skipped += 1
        entry_packages = set()
        entry_certs = set()
        entry_domains = {}
        entry_ips = {}
        entry_type = None

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if line.startswith("- "):
            flush()
            key = line[2:].split(":", 1)[0].strip()
            container = None
            continue
        stripped = line.strip()
        if stripped.startswith("- "):
            # An unquoted YAML scalar ends at " #": upstream annotates some hosts inline.
            value = stripped[2:].split(" #", 1)[0].strip().strip("'\"")
            if key == "packages" and PACKAGE_RE.match(value):
                entry_packages.add(value.lower())
            elif key == "certificates" and SHA1_RE.match(value):
                entry_certs.add(value.lower())
            elif key in ("websites", "domains"):
                normalized = normalize_domain(value)
                if normalized is not None:
                    entry_domains[normalized] = kind_for(key, container)
            elif key == "ips":
                normalized = normalize_ip(value)
                if normalized is not None:
                    entry_ips[normalized] = kind_for(key, container)
            continue
        if ":" in stripped:
            key, _, inline = stripped.partition(":")
            key = key.strip()
            if key == "c2" and not inline.strip():
                container = "c2"
            elif key not in ("domains", "ips"):
                container = None
            if key == "type":
                entry_type = inline.strip().strip("'\"").lower()
    flush()
    return sorted(packages), sorted(certs), sorted(domains), sorted(ips), kinds, entries, skipped


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

    packages, certs, domains, ips, kinds, entries, skipped = parse_ioc(raw.decode("utf-8"))
    if not packages:
        print("refusing to write an empty seed: the source parsed to zero packages", file=sys.stderr)
        return 1

    document = {
        "schema": SCHEMA,
        "packages": packages,
        "certs": [],
        "certsSha1": certs,
        "domains": domains,
        "ips": ips,
        # An indicator with no `c2:`/`websites:` context is dropped, which the app reads as UNCLASSIFIED rather than the loudest kind.
        "indicatorKinds": {k: kinds[k] for k in sorted(kinds) if kinds[k] != UNCLASSIFIED},
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
                "domains": len(domains),
                "ips": len(ips),
                "commandAndControl": sum(1 for v in kinds.values() if v == COMMAND_AND_CONTROL),
                "malwareDistribution": sum(1 for v in kinds.values() if v == MALWARE_DISTRIBUTION),
                "builtBy": "scripts/build-sentinel-threat-intel.py",
            },
            indent=2,
        )
        + "\n"
    )
    print(
        f"{len(packages)} packages, {len(certs)} SHA-1 certificates, "
        f"{len(domains)} domains, {len(ips)} ips "
        f"from {entries} entries ({skipped} skipped as non-stalkerware)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
