#!/usr/bin/env bash
set -euo pipefail


usage() {
  cat >&2 <<'USAGE'
usage:
  scripts/package-release-candidate.sh prepare OUTPUT_DIR SOURCE_COMMIT SOURCE_TREE
  scripts/package-release-candidate.sh verify  CANDIDATE_DIR [SOURCE_COMMIT SOURCE_TREE]
  scripts/package-release-candidate.sh published-code OWNER/REPOSITORY

Certificate source:
  config/release-cert-sha256.txt is authoritative. EXPECTED_RELEASE_CERT_SHA256 may be
  supplied by a caller, but it must match that file and the fingerprints documented in
  README.md and docs/README.ru.md.

prepare reads signed APKs and their generated licenseAssets from
build/release-variants/{github,github-armv7,fdroid}/. Each directory contains app.apk.
The CycloneDX JSON comes from FOXHOLE_SBOM_PATH (default: build/reports/cyclonedx/bom.json).
The exact ABI profiles are android4, android-armv7 and android4 respectively.
The primary GitHub asset keeps its established filename for update compatibility.
USAGE
  exit 2
}

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

find_build_tool() {
  local name=$1
  local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  if [[ -n "$sdk_root" ]]; then
    local candidate
    candidate="$(find "$sdk_root/build-tools" -type f -name "$name" 2>/dev/null | LC_ALL=C sort | tail -n 1)"
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  fi
  command -v "$name" 2>/dev/null || die "$name was not found in Android build-tools or PATH"
}

read_release_coordinates() {
  VERSION_CODE="$(python3 - app/build.gradle.kts <<'PY'
from pathlib import Path
import re
import sys
text = Path(sys.argv[1]).read_text()
values = re.findall(r'^\s*versionCode\s*=\s*(.+?)\s*$', text, re.M)
if len(values) != 1:
    raise SystemExit('Expected one release versionCode declaration')
value = values[0]
if value == 'localBuildNumber ?: baseAppVersionCode':
    bases = re.findall(r'^\s*val baseAppVersionCode\s*=\s*([1-9][0-9]*)\s*$', text, re.M)
    if len(bases) != 1:
        raise SystemExit('Expected one public baseAppVersionCode')
    value = bases[0]
if re.fullmatch(r'[1-9][0-9]*', value) is None:
    raise SystemExit('Unsupported release versionCode expression')
print(value)
PY
)"
  VERSION_NAME="$(
    sed -nE 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([^"]+)".*$/\1/p' \
      build.gradle.kts | head -n 1
  )"
  CORE_REVISION="$(tr -d '[:space:]' < config/foxcore-revision.txt)"

  [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || die "invalid app versionCode: $VERSION_CODE"
  [[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] ||
    die "invalid release versionName: $VERSION_NAME"
  [[ "$CORE_REVISION" =~ ^[0-9a-f]{40}$ ]] || die "invalid pinned FoxHole Core revision"
  RELEASE_TAG="v$VERSION_NAME"
}

normalized_expected_cert() {
  local cert_file=config/release-cert-sha256.txt
  [[ -f "$cert_file" ]] || die "release certificate source is missing: $cert_file"

  local configured supplied
  configured="$(tr -d '[:space:]: ' < "$cert_file" | tr '[:upper:]' '[:lower:]')"
  [[ "$configured" =~ ^[0-9a-f]{64}$ ]] || die "invalid release certificate in $cert_file"

  supplied="$(printf '%s' "${EXPECTED_RELEASE_CERT_SHA256:-$configured}" | tr -d ': ' | tr '[:upper:]' '[:lower:]')"
  [[ "$supplied" == "$configured" ]] || die "release certificate environment does not match $cert_file"

  local readme
  for readme in README.md docs/README.ru.md; do
    [[ "$(grep -Fxc "$configured" "$readme")" == "1" ]] ||
      die "$readme must document the authoritative release certificate exactly once"
  done
  printf '%s' "$configured"
}

verify_fdroid_signing_blocks() {
  local apk=$1
  command -v python3 >/dev/null 2>&1 || die "python3 is required to inspect APK signing blocks"

  python3 - "$apk" <<'PY' || die "F-Droid signing-block verification failed for $(basename "$apk")"
import struct
import sys
from pathlib import Path

apk = Path(sys.argv[1])
data = apk.read_bytes()
eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65_557))
if eocd < 0:
    raise SystemExit("APK end-of-central-directory record is missing")

central_directory_offset = struct.unpack_from("<I", data, eocd + 16)[0]
if central_directory_offset < 24 or central_directory_offset == 0xFFFFFFFF:
    raise SystemExit("APK central-directory offset is invalid or unsupported")

footer = data[central_directory_offset - 24 : central_directory_offset]
block_size = struct.unpack_from("<Q", footer)[0]
if footer[8:] != b"APK Sig Block 42":
    raise SystemExit("APK Signing Block footer is missing")

block_start = central_directory_offset - block_size - 8
if block_start < 0 or struct.unpack_from("<Q", data, block_start)[0] != block_size:
    raise SystemExit("APK Signing Block size is invalid")

forbidden = {
    0x2146444E: "Google Play Signature (Frosting)",
    0x504B4453: "Dependency metadata",
    0x71777777: "Meituan payload",
}
position = block_start + 8
entries_end = central_directory_offset - 24
while position < entries_end:
    entry_size = struct.unpack_from("<Q", data, position)[0]
    if entry_size < 4 or position + 8 + entry_size > entries_end:
        raise SystemExit("APK Signing Block entry is invalid")
    entry_id = struct.unpack_from("<I", data, position + 8)[0]
    if entry_id in forbidden:
        raise SystemExit(f"forbidden F-Droid signing block: {forbidden[entry_id]}")
    position += 8 + entry_size

if position != entries_end:
    raise SystemExit("APK Signing Block entry alignment is invalid")
PY
}

verify_apk() {
  local apk=$1
  local cert_report=$2
  local expected_cert=$3
  local apksigner=$4
  local aapt2=$5
  local channel=$6
  local profile=$7

  local report actual package_line native_line
  report="$($apksigner verify --verbose --print-certs "$apk")"
  printf '%s\n' "$report" >> "$cert_report"
  grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$report" ||
    die "APK Signature Scheme v2 is missing from $(basename "$apk")"
  actual="$(
    awk -F': ' '/certificate SHA-256 digest/ { print $NF; exit }' <<<"$report" |
      tr -d ':' |
      tr '[:upper:]' '[:lower:]'
  )"
  [[ -n "$actual" ]] || die "could not read signing certificate digest from $(basename "$apk")"
  [[ "$actual" == "$expected_cert" ]] || die "release certificate mismatch for $(basename "$apk")"
  verify_fdroid_signing_blocks "$apk"

  package_line="$($aapt2 dump badging "$apk" | sed -n '1p')"
  [[ "$package_line" == *"name='com.foxhole.guard'"* ]] || die "unexpected package in $(basename "$apk")"
  [[ "$package_line" == *"versionCode='$VERSION_CODE'"* ]] || die "unexpected versionCode in $(basename "$apk")"
  [[ "$package_line" == *"versionName='$VERSION_NAME'"* ]] || die "unexpected versionName in $(basename "$apk")"
  native_line="$($aapt2 dump badging "$apk" | sed -n '/^native-code:/p' | sed -n '1p')"
  python3 - "$native_line" "$profile" <<'PY' || die "release APK has an unexpected native ABI inventory: $(basename "$apk")"
import re
import sys
profiles = {'android4': {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'},
            'android64': {'arm64-v8a', 'x86_64'}, 'android-armv7': {'armeabi-v7a'},
            'android3': {'arm64-v8a', 'armeabi-v7a', 'x86_64'}}
line, profile = sys.argv[1:]
if profile not in profiles or re.fullmatch(r"native-code: '[A-Za-z0-9_-]+'(?: '[A-Za-z0-9_-]+')*", line) is None:
    raise SystemExit('Invalid native ABI declaration')
abis = re.findall(r"'([^']+)'", line)
if len(abis) != len(set(abis)) or set(abis) != profiles[profile]:
    raise SystemExit('Native ABI set differs from the selected release profile')
PY
  verify_apk_update_channel "$apk" "$aapt2" "$channel"
}

verify_apk_update_channel() {
  local apk=$1 aapt2=$2 channel=$3 permissions
  permissions="$("$aapt2" dump permissions "$apk")"
  case "$channel" in
    github)
      grep -Fq "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'" <<<"$permissions" ||
        die "GitHub APK is missing REQUEST_INSTALL_PACKAGES: $(basename "$apk")"
      ;;
    fdroid)
      if grep -Fq "android.permission.REQUEST_INSTALL_PACKAGES" <<<"$permissions"; then
        die "F-Droid APK must not request REQUEST_INSTALL_PACKAGES: $(basename "$apk")"
      fi
      ;;
    *) die "unsupported APK update channel: $channel" ;;
  esac
}

package_license_notices() {
  local source_dir=$1 output_file=$2
  [[ -f "$source_dir/SHA256SUMS" ]] || die "generated license assets are missing: $source_dir"
  if find "$source_dir" -mindepth 1 \( -type l -o \( ! -type f ! -type d \) \) -print -quit | grep -q .; then
    die "license assets contain a directory symlink or special file"
  fi
  (
    cd "$source_dir"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum --check --strict SHA256SUMS >/dev/null
    else
      shasum -a 256 -c SHA256SUMS >/dev/null
    fi
  )
  python3 - "$source_dir" "$output_file" <<'PY'
import sys
import zipfile
from pathlib import Path

source = Path(sys.argv[1])
output = Path(sys.argv[2])
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(candidate for candidate in source.rglob("*") if candidate.is_file()):
        relative = path.relative_to(source).as_posix()
        entry = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
PY
}

verify_license_notices_archive() {
  local archive=$1 profile=$2
  python3 - "$archive" "$profile" "$CORE_REVISION" <<'PY' || die "license-notices archive verification failed"
import hashlib
import json
import re
import stat
import sys
import zipfile
from pathlib import PurePosixPath

with zipfile.ZipFile(sys.argv[1]) as archive:
    infos = archive.infolist()
    names = [info.filename for info in infos]
    if len(names) != len(set(names)):
        raise SystemExit("duplicate license archive entry")
    for info in infos:
        path = PurePosixPath(info.filename)
        mode = info.external_attr >> 16
        if path.is_absolute() or ".." in path.parts or info.is_dir():
            raise SystemExit(f"unsafe license archive entry: {info.filename}")
        if mode and not stat.S_ISREG(mode):
            raise SystemExit(f"non-regular license archive entry: {info.filename}")
    required = {
        "FoxHole-Guard-GPL-3.0-or-later.txt",
        "MANIFEST.json",
        "THIRD_PARTY_NOTICES.md",
        "SHA256SUMS",
        "android/JNA-Apache-2.0.txt",
        "android/JNA-LGPL-2.1.txt",
        "android/JNA-LICENSE.txt",
        "android/SQLCipher-BSD-3-Clause.txt",
        "android/lazysodium-MPL-2.0.txt",
        "android/libsodium-ISC.txt",
        "flags/app-assets.sha256",
        "flags/flag-icons-LICENSE.txt",
        "fonts/JetBrainsMono-OFL.txt",
        "fonts/Tiny5-OFL.txt",
        "foxcore/FoxHole-Core-GPL-3.0-or-later.txt",
        "foxcore/THIRD_PARTY_NOTICES.md",
        "i2pd/Android-NDK-29-NOTICE.txt",
        "i2pd/Android-NDK-29-NOTICE.toolchain.txt",
        "i2pd/Boost-1.84.0-BSL-1.0.txt",
        "i2pd/OpenSSL-3.5.8-Apache-2.0.txt",
        "i2pd/i2pd-BSD-3-Clause.txt",
        "icons/Tabler-MIT.txt",
        "tor/GO-MODULES.json",
        "tor/Go-1.26.10-BSD-3-Clause.txt",
        "tor/conjure-client-BSD-3-Clause.txt",
        "tor/lyrebird-BSD-3-Clause.txt",
        "tor/lyrebird-GPL-3.0-or-later.txt",
        "tor-config/PROVENANCE.txt",
        "tor-config/assets.sha256",
        "tor-config/upstream-members/README.CONJURE.md",
        "tor-config/upstream-members/pt_config.json",
        "tor-config/upstream-members/torrc-defaults.txt",
    }
    profiles = {'android4': (['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'],
                             ['aarch64-linux-android', 'armv7-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android']),
                'android64': (['arm64-v8a', 'x86_64'], ['aarch64-linux-android', 'x86_64-linux-android']),
                'android-armv7': (['armeabi-v7a'], ['armv7-linux-androideabi']),
                'android3': (['arm64-v8a', 'armeabi-v7a', 'x86_64'],
                             ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android'])}
    profile = sys.argv[2]
    if profile not in profiles:
        raise SystemExit('Unknown release license profile')
    abis, targets = profiles[profile]
    required.update('foxcore/foxcore-' + target + '.cdx.json' for target in targets)
    if not required.issubset(names):
        raise SystemExit(f"missing license entries: {sorted(required - set(names))}")
    checksums = {}
    for line in archive.read("SHA256SUMS").decode().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise SystemExit(f"invalid license checksum line: {line}")
        digest, name = match.groups()
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or name in checksums:
            raise SystemExit(f"unsafe or duplicate license checksum path: {name}")
        checksums[name] = digest
    expected = set(names) - {"SHA256SUMS"}
    if set(checksums) != expected:
        raise SystemExit("license checksum inventory does not match archive entries")
    for name, expected_digest in checksums.items():
        if hashlib.sha256(archive.read(name)).hexdigest() != expected_digest:
            raise SystemExit(f"license checksum mismatch: {name}")
    manifest = json.loads(archive.read("MANIFEST.json"))
    if (manifest.get("schema") != 1 or not re.fullmatch(r"[0-9a-f]{40}", manifest.get("foxcoreRevision", "")) or
            manifest.get("foxcoreRevision") != sys.argv[3]):
        raise SystemExit("invalid license manifest metadata")
    if (manifest.get('androidDeliveryProfile'), manifest.get('abis'), manifest.get('targets')) != (profile, abis, targets):
        raise SystemExit('License manifest differs from the selected release profile')
    manifest_entries = manifest.get("files")
    if not isinstance(manifest_entries, list):
        raise SystemExit("license manifest has no file inventory")
    indexed = {}
    for entry in manifest_entries:
        if not isinstance(entry, dict):
            raise SystemExit("invalid license manifest entry")
        name = entry.get("path")
        if not isinstance(name, str) or name in indexed:
            raise SystemExit(f"invalid or duplicate license manifest path: {name}")
        indexed[name] = entry
    expected_manifest = set(names) - {"MANIFEST.json", "SHA256SUMS"}
    if set(indexed) != expected_manifest:
        raise SystemExit("license manifest inventory does not match archive entries")
    for name, entry in indexed.items():
        data = archive.read(name)
        if entry.get("size") != len(data) or entry.get("sha256") != hashlib.sha256(data).hexdigest():
            raise SystemExit(f"license manifest mismatch: {name}")
PY
}

verify_android_sbom_inventory() {
  local sbom=$1 version_name=$2
  jq -e \
    --arg versionName "$version_name" '
      def exact_component($name; $version; $purl):
        [.components[] |
          select(
            .name == $name and
            .version == $version and
            (.purl | startswith($purl))
          )
        ] | length == 1;
      .bomFormat == "CycloneDX" and
      .metadata.component.name == "foxhole-android" and
      .metadata.component.version == $versionName and
      (.components | type == "array" and length > 0) and
      all(
        .components[];
        (."bom-ref" | type == "string" and length > 0) and
        (.name | type == "string" and length > 0) and
        (.version | type == "string" and length > 0) and
        (.purl | type == "string" and length > 0)
      ) and
      ([.components[]."bom-ref"] | unique | length) == (.components | length) and
      exact_component("jna"; "5.19.1"; "pkg:maven/net.java.dev.jna/jna@5.19.1") and
      exact_component("lazysodium-android"; "5.2.0"; "pkg:maven/com.goterl/lazysodium-android@5.2.0") and
      exact_component("okhttp"; "5.4.0"; "pkg:maven/com.squareup.okhttp3/okhttp@5.4.0") and
      exact_component("sqlcipher-android"; "4.19.1"; "pkg:maven/net.zetetic/sqlcipher-android@4.19.1") and
      exact_component(
        "zxing-android-embedded";
        "4.3.0";
        "pkg:maven/com.journeyapps/zxing-android-embedded@4.3.0"
      )
    ' "$sbom" >/dev/null || die "Android CycloneDX dependency inventory is incomplete or malformed"
}

verify_sbom_apk_digest() {
  local sbom=$1 apk=$2
  jq -e --arg digest "$(sha256_file "$apk")" '
    [.metadata.properties[] | select(.name == "foxhole:apkSha256") | .value] == [$digest]
  ' "$sbom" >/dev/null || die "SBOM does not describe the expected APK: $(basename "$sbom")"
}

aggregate_profile_sbom() {
  local maven=$1 licenses=$2 apk=$3 output=$4 profile=$5
  python3 - "$maven" "$licenses" "$apk" "$output" "$profile" <<'PY'
from pathlib import Path
import importlib.util
import json
import subprocess
import sys
import tempfile

maven, licenses, apk, output = map(Path, sys.argv[1:5])
profile = sys.argv[5]
root = Path.cwd()
shapes = {'android4': (['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'],
                       ['aarch64-linux-android', 'armv7-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android']),
          'android64': (['arm64-v8a', 'x86_64'], ['aarch64-linux-android', 'x86_64-linux-android']),
          'android-armv7': (['armeabi-v7a'], ['armv7-linux-androideabi']),
          'android3': (['arm64-v8a', 'armeabi-v7a', 'x86_64'],
                       ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android'])}
if profile not in shapes:
    raise SystemExit('Unknown aggregate release profile')
spec = importlib.util.spec_from_file_location('release_delivery_inventory', root / 'scripts/aggregate-delivery-sbom.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
# Each aggregate uses committed source with only its two declared profile headers projected.
with tempfile.TemporaryDirectory(prefix='foxhole-release-profile-') as directory:
    projected = Path(directory)
    subprocess.run(['git', 'checkout-index', '--all', '--prefix=' + str(projected) + '/'], check=True)
    pins_path = projected / 'config/foxcore-source-manifest.json'
    config_path = projected / 'config/foxcore-android-delivery.json'
    pins = json.loads(pins_path.read_text())
    config = json.loads(config_path.read_text())
    if (config != {'schema': 1, **{key: pins[key] for key in ('androidDeliveryProfile', 'abis', 'targets')}} or
            pins['commit'] != (projected / 'config/foxcore-revision.txt').read_text().strip() or
            len(pins['files']) != 522 or len({row['path'] for row in pins['files']}) != 522):
        raise SystemExit('Committed delivery headers disagree')
    snapshot = json.loads((projected / 'third_party/licenses/source-legal' / profile / 'snapshot.json').read_text())
    abis, targets = shapes[profile]
    shape = {'androidDeliveryProfile': profile, 'abis': abis, 'targets': targets}
    if (snapshot.get('coreCommit') != pins['commit'] or snapshot.get('androidDeliveryProfile') != profile or
            snapshot.get('abis') != abis or snapshot.get('targets') != targets or
            snapshot.get('compiledInputs') != {row['path']: row['sha256'] for row in pins['files']}):
        raise SystemExit('Canonical release profile source binding differs')
    config_path.write_text(json.dumps({'schema': 1, **shape}, indent=2) + '\n')
    pins_path.write_text(json.dumps({**pins, **shape}, indent=2) + '\n')
    document = module.aggregate(json.loads(maven.read_text()), licenses, apk, projected)
    output.write_text(json.dumps(document, indent=2) + '\n')
PY
}

release_matrix_names() {
  apk_names=("FoxHole-${RELEASE_TAG}-arm64-v8a-release.apk" "FoxHole-${RELEASE_TAG}-armeabi-v7a-release.apk" "FoxHole-${RELEASE_TAG}-universal-fdroid.apk")
  sbom_names=("FoxHole-${RELEASE_TAG}-sbom.cdx.json" "FoxHole-${RELEASE_TAG}-armeabi-v7a-sbom.cdx.json" "FoxHole-${RELEASE_TAG}-fdroid-sbom.cdx.json")
  license_names=("FoxHole-${RELEASE_TAG}-license-notices.zip" "FoxHole-${RELEASE_TAG}-armeabi-v7a-license-notices.zip" "FoxHole-${RELEASE_TAG}-fdroid-license-notices.zip")
  profiles=(android4 android-armv7 android4)
  channels=(github github fdroid)
}

write_update_manifest() {
  local apk=$1 output=$2
  jq -n --argjson versionCode "$VERSION_CODE" --arg versionName "$VERSION_NAME" \
    --arg apkName "$(basename "$apk")" --arg apkSha256 "$(sha256_file "$apk")" \
    '{versionCode: $versionCode, versionName: $versionName, apkName: $apkName,
      apkSha256: $apkSha256, notes: "See the release notes on GitHub."}' > "$output"
}

verify_update_manifest() {
  local manifest=$1 apk=$2
  jq -e --argjson versionCode "$VERSION_CODE" --arg versionName "$VERSION_NAME" \
    --arg apkName "$(basename "$apk")" --arg apkSha256 "$(sha256_file "$apk")" \
    '.versionCode == $versionCode and .versionName == $versionName and
     .apkName == $apkName and .apkSha256 == $apkSha256' "$manifest" >/dev/null
}

matrix_artifacts() {
  jq -n --arg gh64 "${apk_names[0]}" --arg gh32 "${apk_names[1]}" --arg fd3 "${apk_names[2]}" \
    --arg sb64 "${sbom_names[0]}" --arg sb32 "${sbom_names[1]}" --arg sb3 "${sbom_names[2]}" \
    --arg li64 "${license_names[0]}" --arg li32 "${license_names[1]}" --arg li3 "${license_names[2]}" \
    '[{profile:"android4",updateChannel:"github",apk:$gh64,sbom:$sb64,licenseNotices:$li64},
      {profile:"android-armv7",updateChannel:"github",apk:$gh32,sbom:$sb32,licenseNotices:$li32},
      {profile:"android4",updateChannel:"fdroid",apk:$fd3,sbom:$sb3,licenseNotices:$li3}]'
}

prepare_candidate() {
  local output_dir=$1 source_commit=$2 source_tree=$3
  [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || die "invalid source commit"
  [[ "$source_tree" =~ ^[0-9a-f]{40}$ ]] || die "invalid source tree"
  [[ "$(git rev-parse HEAD)" == "$source_commit" ]] || die "source commit does not match checkout"
  [[ "$(git rev-parse 'HEAD^{tree}')" == "$source_tree" ]] || die "source tree does not match checkout"
  git diff --quiet --ignore-submodules=dirty HEAD -- || die "release source contains tracked changes"
  mkdir -p "$output_dir"
  [[ -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "candidate output directory is not empty: $output_dir"
  read_release_coordinates
  local expected_cert apksigner aapt2 i
  expected_cert=$(normalized_expected_cert)
  apksigner=$(find_build_tool apksigner)
  aapt2=$(find_build_tool aapt2)
  release_matrix_names
  local sources=(build/release-variants/github/app.apk build/release-variants/github-armv7/app.apk build/release-variants/fdroid/app.apk)
  local license_sources=("${FOXHOLE_GITHUB_LICENSE_ASSETS_PATH:-build/release-variants/github/licenseAssets}" "${FOXHOLE_ARMV7_LICENSE_ASSETS_PATH:-build/release-variants/github-armv7/licenseAssets}" "${FOXHOLE_FDROID_LICENSE_ASSETS_PATH:-build/release-variants/fdroid/licenseAssets}")
  local sbom_source=${FOXHOLE_SBOM_PATH:-build/reports/cyclonedx/bom.json}
  [[ -f "$sbom_source" ]] || die "CycloneDX SBOM was not generated: $sbom_source"
  local cert_report="$output_dir/release-certs.txt"
  : > "$cert_report"
  for i in 0 1 2; do
    [[ -f "${sources[$i]}" ]] || die "release APK was not produced: ${profiles[$i]}"
    cp "${sources[$i]}" "$output_dir/${apk_names[$i]}"
    printf '== %s ==\n' "${apk_names[$i]}" >> "$cert_report"
    verify_apk "$output_dir/${apk_names[$i]}" "$cert_report" "$expected_cert" "$apksigner" "$aapt2" "${channels[$i]}" "${profiles[$i]}"
    python3 scripts/verify-apk-source.py "$output_dir/${apk_names[$i]}" "$source_commit"
    aggregate_profile_sbom "$sbom_source" "${license_sources[$i]}" "$output_dir/${apk_names[$i]}" "$output_dir/${sbom_names[$i]}" "${profiles[$i]}"
    verify_android_sbom_inventory "$output_dir/${sbom_names[$i]}" "$VERSION_NAME"
    package_license_notices "${license_sources[$i]}" "$output_dir/${license_names[$i]}"
    verify_license_notices_archive "$output_dir/${license_names[$i]}" "${profiles[$i]}"
  done
  scripts/run-osv-source-scan.sh "${sbom_names[@]/#/$output_dir/}"
  write_update_manifest "$output_dir/${apk_names[0]}" "$output_dir/update-manifest.json"
  write_update_manifest "$output_dir/${apk_names[1]}" "$output_dir/update-manifest-armeabi-v7a.json"
  jq -n --arg sourceCommit "$source_commit" --arg sourceTree "$source_tree" \
    --arg versionName "$VERSION_NAME" --argjson versionCode "$VERSION_CODE" --arg tag "$RELEASE_TAG" \
    --arg coreRevision "$CORE_REVISION" --argjson artifacts "$(matrix_artifacts)" \
    '{schema:2,artifactMatrixVersion:1,sourceCommit:$sourceCommit,sourceTree:$sourceTree,versionName:$versionName,
      versionCode:$versionCode,tag:$tag,coreRevision:$coreRevision,artifacts:$artifacts,
      arm64Apk:$artifacts[0].apk,fdroidArm64Apk:$artifacts[2].apk,fdroidUniversalApk:$artifacts[2].apk,
      sbom:$artifacts[0].sbom,fdroidSbom:$artifacts[2].sbom,licenseNotices:$artifacts[0].licenseNotices,
      armv7Apk:$artifacts[1].apk,armv7Sbom:$artifacts[1].sbom,
      armv7LicenseNotices:$artifacts[1].licenseNotices,fdroidLicenseNotices:$artifacts[2].licenseNotices,
      updateChannel:"github",
      updaterManifests:{android4:"update-manifest.json","android-armv7":"update-manifest-armeabi-v7a.json"}}' > "$output_dir/CANDIDATE.json"
  : > "$output_dir/SHA256SUMS"
  local file
  for file in "${apk_names[@]}" "${sbom_names[@]}" "${license_names[@]}" release-certs.txt update-manifest.json update-manifest-armeabi-v7a.json CANDIDATE.json; do
    printf '%s  %s\n' "$(sha256_file "$output_dir/$file")" "$file" >> "$output_dir/SHA256SUMS"
  done
  verify_candidate "$output_dir" "$source_commit" "$source_tree"
}

verify_candidate() {
  local candidate_dir=$1 expected_source_commit=${2:-} expected_source_tree=${3:-}
  read_release_coordinates
  release_matrix_names
  [[ -f "$candidate_dir/CANDIDATE.json" ]] || die "candidate metadata is missing"
  [[ -f "$candidate_dir/SHA256SUMS" ]] || die "candidate checksums are missing"
  local expected_files actual_files
  expected_files="$(printf '%s\n' CANDIDATE.json SHA256SUMS "${apk_names[@]}" "${sbom_names[@]}" "${license_names[@]}" release-certs.txt update-manifest.json update-manifest-armeabi-v7a.json | LC_ALL=C sort)"
  actual_files="$(find "$candidate_dir" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | LC_ALL=C sort)"
  [[ "$actual_files" == "$expected_files" ]] || die "candidate contains an unexpected file set"
  if find "$candidate_dir" -mindepth 1 -maxdepth 1 ! -type f -print -quit | grep -q .; then
    die "candidate contains a directory, symlink, or special file"
  fi
  (
    cd "$candidate_dir"
    if command -v sha256sum >/dev/null 2>&1; then sha256sum --check --strict SHA256SUMS
    else shasum -a 256 -c SHA256SUMS; fi
  )
  local metadata="$candidate_dir/CANDIDATE.json" source_commit source_tree i
  source_commit=$(jq -er '.sourceCommit' "$metadata")
  source_tree=$(jq -er '.sourceTree' "$metadata")
  [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || die "candidate source commit is invalid"
  [[ "$source_tree" =~ ^[0-9a-f]{40}$ ]] || die "candidate source tree is invalid"
  [[ -z "$expected_source_commit" || "$source_commit" == "$expected_source_commit" ]] || die "candidate source commit does not match the expected source commit"
  [[ -z "$expected_source_tree" || "$source_tree" == "$expected_source_tree" ]] || die "candidate source tree does not match the expected source tree"
  jq -e --arg versionName "$VERSION_NAME" --argjson versionCode "$VERSION_CODE" --arg tag "$RELEASE_TAG" \
    --arg coreRevision "$CORE_REVISION" --argjson artifacts "$(matrix_artifacts)" '
      .schema == 2 and .artifactMatrixVersion == 1 and .versionName == $versionName and .versionCode == $versionCode and
      .tag == $tag and .coreRevision == $coreRevision and .artifacts == $artifacts and
      .arm64Apk == $artifacts[0].apk and .fdroidArm64Apk == $artifacts[2].apk and
      .fdroidUniversalApk == $artifacts[2].apk and .sbom == $artifacts[0].sbom and
      .fdroidSbom == $artifacts[2].sbom and .licenseNotices == $artifacts[0].licenseNotices and
      .armv7Apk == $artifacts[1].apk and .armv7Sbom == $artifacts[1].sbom and
      .armv7LicenseNotices == $artifacts[1].licenseNotices and
      .fdroidLicenseNotices == $artifacts[2].licenseNotices and .updateChannel == "github" and
      .updaterManifests == {android4:"update-manifest.json","android-armv7":"update-manifest-armeabi-v7a.json"}
    ' "$metadata" >/dev/null
  for i in 0 1 2; do
    python3 scripts/verify-apk-source.py "$candidate_dir/${apk_names[$i]}" "$source_commit"
    verify_license_notices_archive "$candidate_dir/${license_names[$i]}" "${profiles[$i]}"
    verify_android_sbom_inventory "$candidate_dir/${sbom_names[$i]}" "$VERSION_NAME"
    verify_sbom_apk_digest "$candidate_dir/${sbom_names[$i]}" "$candidate_dir/${apk_names[$i]}"
  done
  verify_update_manifest "$candidate_dir/update-manifest.json" "$candidate_dir/${apk_names[0]}"
  verify_update_manifest "$candidate_dir/update-manifest-armeabi-v7a.json" "$candidate_dir/${apk_names[1]}"
  local expected_cert apksigner aapt2 verify_report
  expected_cert=$(normalized_expected_cert)
  apksigner=$(find_build_tool apksigner)
  aapt2=$(find_build_tool aapt2)
  verify_report=$(mktemp)
  for i in 0 1 2; do
    verify_apk "$candidate_dir/${apk_names[$i]}" "$verify_report" "$expected_cert" "$apksigner" "$aapt2" "${channels[$i]}" "${profiles[$i]}"
  done
  rm -f "$verify_report"
}

published_version_code() {
  local repository=$1
  [[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "invalid GitHub repository"
  command -v gh >/dev/null 2>&1 || die "gh is required to inspect published releases"
  command -v jq >/dev/null 2>&1 || die "jq is required to inspect published releases"

  local releases_json manifest_json
  releases_json=$(mktemp)
  manifest_json=$(mktemp)
  gh api -X GET "repos/$repository/releases" -f per_page=100 > "$releases_json"

  local release_id
  release_id=$(jq -r 'map(select(.draft == false))[0].id // empty' "$releases_json")
  if [[ -z "$release_id" ]]; then
    rm -f "$releases_json" "$manifest_json"
    printf '0\n'
    return
  fi

  local manifest_count manifest_url
  manifest_count=$(
    jq --argjson releaseId "$release_id" '
      map(select(.id == $releaseId))[0].assets |
      map(select(.name == "update-manifest.json")) |
      length
    ' "$releases_json"
  )
  [[ "$manifest_count" == "1" ]] ||
    die "latest published release must contain exactly one update-manifest.json"
  manifest_url=$(
    jq -er --argjson releaseId "$release_id" '
      map(select(.id == $releaseId))[0].assets |
      map(select(.name == "update-manifest.json"))[0].url
    ' "$releases_json"
  )
  gh api -X GET -H 'Accept: application/octet-stream' "$manifest_url" > "$manifest_json"

  local version_code
  version_code=$(jq -er '.versionCode | select(type == "number" and floor == . and . >= 1)' "$manifest_json")
  [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || die "published update manifest has an invalid versionCode"
  rm -f "$releases_json" "$manifest_json"
  printf '%s\n' "$version_code"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  [[ $# -ge 2 ]] || usage
  command=$1
  shift
  case "$command" in
    prepare)
      [[ $# -eq 3 ]] || usage
      prepare_candidate "$@"
      ;;
    verify)
      [[ $# -ge 1 && $# -le 3 ]] || usage
      verify_candidate "$@"
      ;;
    published-code)
      [[ $# -eq 1 ]] || usage
      published_version_code "$@"
      ;;
    *) usage ;;
  esac
fi
