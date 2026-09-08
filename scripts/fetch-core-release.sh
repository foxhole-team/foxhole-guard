#!/usr/bin/env bash
set -euo pipefail

source_root="${1:?pinned Core source directory}"
output="${2:?release download directory}"
repository="${3:-foxhole-team/foxhole-core}"
revision="$(tr -d '[:space:]' < config/foxcore-revision.txt)"
version="$(sed -n 's/^version = "\([^"]*\)"$/\1/p' "$source_root/Cargo.toml" | head -n 1)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$revision" =~ ^[0-9a-f]{40}$ ]]
mkdir -p "$output"
archive="foxcore-android-v$version.tar.gz"
gh release download "v$version" --repo "$repository" --pattern "$archive" --pattern SHA256SUMS --dir "$output" --clobber
gh attestation verify "$output/$archive" --repo "$repository" --signer-workflow "$repository/.github/workflows/release.yml"
(cd "$output" && sha256sum --check SHA256SUMS)
python3 - "$output/$archive" "$output/release" <<'PY'
import pathlib, sys, tarfile
with tarfile.open(sys.argv[1]) as archive:
    archive.extractall(pathlib.Path(sys.argv[2]), filter="data")
PY
python3 scripts/verify-core-release.py "$source_root" "$output/release" "$revision" 'arm64-v8a armeabi-v7a'
echo "FOXCORE_PREBUILT_DIR=$output/release" >> "${GITHUB_ENV:?GitHub environment file}"
