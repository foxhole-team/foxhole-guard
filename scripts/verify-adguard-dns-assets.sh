#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${FOXHOLE_PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$ROOT_DIR"

python3 - "$@" <<'PY'
import hashlib
import re
import sys
import zipfile
from pathlib import Path

root = Path.cwd()
installer_path = root / "app/src/main/kotlin/com/foxhole/beta/vpn/DnsFilterAssetInstaller.kt"
source_assets_dir = root / "app/src/main/assets"

installer = installer_path.read_text(encoding="utf-8")


def constant(name: str) -> str:
    match = re.search(rf'const val {re.escape(name)} = "([^"]+)"', installer)
    if not match:
        raise SystemExit(f"Missing {name} in {installer_path}")
    return match.group(1)


def numeric_constant(name: str) -> int:
    match = re.search(rf"const val {re.escape(name)} = ([0-9_]+)L", installer)
    if not match:
        raise SystemExit(f"Missing {name} in {installer_path}")
    return int(match.group(1).replace("_", ""))


filter_name = constant("ADGUARD_DNS_FILTER_FILE_NAME")
filter_asset_path = constant("ADGUARD_DNS_FILTER_ASSET_PATH").replace(
    "$ADGUARD_DNS_FILTER_FILE_NAME",
    filter_name,
)
allowlist_asset_path = constant("ADGUARD_VPN_COMPATIBILITY_ASSET_PATH")
expected_filter_size = numeric_constant("ADGUARD_DNS_FILTER_SIZE_BYTES")
expected_filter_sha256 = constant("ADGUARD_DNS_FILTER_SHA256")

required_assets = {
    filter_asset_path: source_assets_dir / filter_asset_path,
    allowlist_asset_path: source_assets_dir / allowlist_asset_path,
}

for asset_path, source_path in required_assets.items():
    if not source_path.is_file():
        raise SystemExit(f"Missing source asset for {asset_path}: {source_path}")

filter_source = required_assets[filter_asset_path]
actual_filter_size = filter_source.stat().st_size
actual_filter_sha256 = hashlib.sha256(filter_source.read_bytes()).hexdigest()
if actual_filter_size != expected_filter_size:
    raise SystemExit(
        f"{filter_asset_path} size mismatch: expected {expected_filter_size}, got {actual_filter_size}"
    )
if actual_filter_sha256 != expected_filter_sha256:
    raise SystemExit(
        f"{filter_asset_path} sha256 mismatch: expected {expected_filter_sha256}, got {actual_filter_sha256}"
    )

apk_args = [Path(arg) for arg in sys.argv[1:]]
if apk_args:
    apks = apk_args
else:
    apks = sorted(
        list((root / "app/build/outputs/apk/debug").glob("*.apk"))
        + list((root / "app/build/outputs/apk/release").glob("*.apk"))
    )

if not apks:
    raise SystemExit("No APKs supplied or found under app/build/outputs/apk")

missing_apks = [apk for apk in apks if not apk.is_file()]
if missing_apks:
    raise SystemExit("Missing APK(s): " + ", ".join(str(apk) for apk in missing_apks))

for apk in apks:
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        missing_assets = [asset for asset in required_assets if f"assets/{asset}" not in names]
        if missing_assets:
            raise SystemExit(
                f"{apk} is missing AdGuard DNS asset entries: {', '.join(missing_assets)}"
            )

        for asset, source_path in required_assets.items():
            packaged_bytes = archive.read(f"assets/{asset}")
            source_bytes = source_path.read_bytes()
            if packaged_bytes != source_bytes:
                raise SystemExit(f"{apk} packaged assets/{asset} differs from {source_path}")

print("Verified AdGuard DNS assets in APK(s):")
for apk in apks:
    print(f" - {apk}")
print(f"Verified source checksum: {filter_asset_path} {actual_filter_sha256}")
PY
