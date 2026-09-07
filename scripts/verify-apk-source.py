#!/usr/bin/env python3
"""Verify AGP's embedded Git provenance against the declared build commit."""

import re
import sys
import zipfile


def verify(apk, expected_commit):
    if not re.fullmatch(r"[0-9a-f]{40}", expected_commit):
        raise ValueError("expected source commit must be a full Git SHA")
    metadata_path = "META-INF/version-control-info.textproto"
    with zipfile.ZipFile(apk) as archive:
        if archive.namelist().count(metadata_path) != 1:
            raise ValueError("APK must contain exactly one AGP provenance entry")
        metadata = archive.read(metadata_path).decode("utf-8")
    roots = []
    for block in re.findall(r"repositories\s*\{([^{}]*)\}", metadata):
        paths = re.findall(r'(?m)^\s*local_root_path:\s*"([^"\n]*)"\s*$', block)
        if "$PROJECT_DIR" in paths:
            if paths != ["$PROJECT_DIR"]:
                raise ValueError("APK root repository path is ambiguous")
            systems = re.findall(r"(?m)^\s*system:\s*(\w+)\s*$", block)
            revisions = re.findall(r'(?m)^\s*revision:\s*"([^"\n]*)"\s*$', block)
            if systems != ["GIT"] or revisions != [expected_commit]:
                raise ValueError("APK source commit does not match the declared build commit")
            roots.append(block)
    if len(roots) != 1:
        raise ValueError("APK must identify exactly one root Git repository")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: verify-apk-source.py APK SOURCE_COMMIT")
    try:
        verify(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
