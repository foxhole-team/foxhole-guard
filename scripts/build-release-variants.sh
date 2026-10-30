#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 && "$1" =~ ^[0-9]+$ ]] || {
  echo "usage: build-release-variants.sh LAST_PUBLISHED_VERSION_CODE" >&2
  exit 2
}
repo=$(git rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" diff --quiet --ignore-submodules=dirty HEAD -- || {
  echo "Release source must have no tracked changes" >&2; exit 1;
}
absolute() { python3 - "$1" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).resolve())
PY
}
FOXCORE_SOURCE_ROOT=$(absolute "${FOXCORE_SOURCE_ROOT:-$repo/../foxhole-core}")
export FOXCORE_SOURCE_ROOT
[[ "$(git -C "$FOXCORE_SOURCE_ROOT" rev-parse HEAD)" == "$(tr -d '[:space:]' < "$repo/config/foxcore-revision.txt")" ]] || {
  echo "Core checkout differs from the release pin" >&2; exit 1;
}
signing_properties=$(absolute "${FOXHOLE_RELEASE_SIGNING_PROPERTIES:-$repo/../dev/signing/release-signing.properties}")
if [[ -n "${FOXHOLE_RELEASE_STORE_FILE:-}" ]]; then
  FOXHOLE_RELEASE_STORE_FILE=$(absolute "$FOXHOLE_RELEASE_STORE_FILE")
  export FOXHOLE_RELEASE_STORE_FILE
fi
work=$(mktemp -d "${TMPDIR:-/tmp}/foxhole-release-variants.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
lanes=(github github-armv7 fdroid)
channels=(github github fdroid)
profiles=(android4 android-armv7 android4)
abis=("arm64-v8a armeabi-v7a x86 x86_64" "armeabi-v7a" "arm64-v8a armeabi-v7a x86 x86_64")
for i in 0 1 2; do
  clone="$work/${lanes[$i]}"
  git clone --quiet --no-hardlinks --no-checkout "$repo" "$clone"
  git -C "$clone" checkout --quiet --detach "$commit"
  git -C "$clone" submodule update --init --recursive
  python3 - "$clone" "${profiles[$i]}" <<'PY'
from pathlib import Path
import json
import sys
root, profile = Path(sys.argv[1]), sys.argv[2]
source_path = root / 'config/foxcore-source-manifest.json'
config_path = root / 'config/foxcore-android-delivery.json'
source = json.loads(source_path.read_text())
config = json.loads(config_path.read_text())
snapshot = json.loads((root / 'third_party/licenses/source-legal' / profile / 'snapshot.json').read_text())
shapes = {'android4': (['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], ['aarch64-linux-android', 'armv7-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android']),
          'android64': (['arm64-v8a', 'x86_64'], ['aarch64-linux-android', 'x86_64-linux-android']),
          'android-armv7': (['armeabi-v7a'], ['armv7-linux-androideabi']),
          'android3': (['arm64-v8a', 'armeabi-v7a', 'x86_64'], ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android'])}
abis, targets = shapes[profile]
assert config == {'schema': 1, **{key: source[key] for key in ('androidDeliveryProfile', 'abis', 'targets')}}
assert source['commit'] == (root / 'config/foxcore-revision.txt').read_text().strip() == snapshot['coreCommit']
assert len(source['files']) == len({row['path'] for row in source['files']}) == 522
assert snapshot['androidDeliveryProfile'] == profile and snapshot['abis'] == abis and snapshot['targets'] == targets
assert snapshot['compiledInputs'] == {row['path']: row['sha256'] for row in source['files']}
shape = {'androidDeliveryProfile': profile, 'abis': abis, 'targets': targets}
config_path.write_text(json.dumps({'schema': 1, **shape}, indent=2) + '\n')
source_path.write_text(json.dumps({**source, **shape}, indent=2) + '\n')
PY
  (
    cd "$clone"
    ./gradlew --no-daemon --console=plain --stacktrace \
      -Pfoxhole.splitApks=false -Pfoxhole.sbom=true \
      "-Pfoxhole.abis=${abis[$i]}" "-Pfoxhole.updateChannel=${channels[$i]}" \
      "-Pfoxhole.releaseSigningProperties=$signing_properties" \
      "-Pfoxhole.lastUploadedVersionCode=$1" \
      :app:validateReleaseSigningInputs :app:assembleRelease \
      :app:verifyReleaseContainsNativeRuntime :app:verifyReleaseContainsBaselineProfile \
      :app:verifyReleaseContainsLicenseAssets :app:verifyReleaseBuildConfigDefaults :app:verifyReleaseJniSurface
  )
  destination="$repo/build/release-variants/${lanes[$i]}"
  [[ ! -e "$destination" ]] || { echo "Retained release output already exists: ${lanes[$i]}" >&2; exit 1; }
  mkdir -p "$destination"
  cp "$clone/app/build/outputs/apk/release/app-release.apk" "$destination/app.apk"
  cp "$clone/app/build/outputs/mapping/release/mapping.txt" "$destination/mapping.txt"
  cp -R "$clone/app/build/generated/licenseAssets" "$destination/licenseAssets"
done
(
  cd "$work/github"
  ./gradlew --no-daemon --console=plain --stacktrace -Pfoxhole.sbom=true \
    -Pfoxhole.splitApks=false "-Pfoxhole.abis=arm64-v8a armeabi-v7a x86 x86_64" -Pfoxhole.updateChannel=github \
    "-Pfoxhole.releaseSigningProperties=$signing_properties" "-Pfoxhole.lastUploadedVersionCode=$1" \
    :app:validateReleaseSigningInputs :app:publicReleasePreflight
)
bom="$repo/build/reports/cyclonedx/bom.json"
[[ ! -e "$bom" ]] || { echo "Retained release SBOM already exists" >&2; exit 1; }
mkdir -p "$(dirname "$bom")"
cp "$work/github/build/reports/cyclonedx/bom.json" "$bom"
git -C "$repo" diff --quiet --ignore-submodules=dirty HEAD -- || {
  echo "Owner release source changed during variant builds" >&2; exit 1;
}
