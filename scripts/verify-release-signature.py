#!/usr/bin/env python3
"""Verify a release commit against the reviewed signing subkey and primary key."""

import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def verify(commit, repository=ROOT, keyring=None, pins=None):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("release source must be a full commit SHA")
    keyring = keyring or ROOT / "config/release-signers.asc"
    pins = pins or ROOT / "config/release-signing-fingerprints.txt"
    allowed = set()
    for line in pins.read_text().splitlines():
        if not re.fullmatch(r"[0-9A-F]{40} [0-9A-F]{40}", line):
            raise ValueError("invalid release signing fingerprint pair")
        allowed.add(tuple(line.split()))
    if not allowed:
        raise ValueError("release signer allowlist is empty")
    with tempfile.TemporaryDirectory(prefix="foxcore-release-gpg-") as home:
        environment = dict(os.environ, GNUPGHOME=home)
        subprocess.run(
            ["gpg", "--batch", "--import", str(keyring)], env=environment,
            check=True, capture_output=True,
        )
        result = subprocess.run(
            ["git", "-c", "gpg.format=openpgp", "-c", "gpg.openpgp.program=gpg",
             "-c", "gpg.minTrustLevel=undefined", "verify-commit", "--raw", commit],
            cwd=repository, env=environment, capture_output=True, text=True,
        )
        signatures = [line.split() for line in result.stderr.splitlines()
                      if line.startswith("[GNUPG:] VALIDSIG ")]
        if result.returncode or len(signatures) != 1:
            raise ValueError("release commit has no valid OpenPGP signature")
        fields = signatures[0]
        if len(fields) != 12 or (fields[2], fields[11]) not in allowed:
            raise ValueError("release commit was signed by an unapproved key")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("usage: verify-release-signature.py COMMIT_SHA")
        verify(sys.argv[1])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
    print("Release commit signature matches the pinned owner signing key.")
