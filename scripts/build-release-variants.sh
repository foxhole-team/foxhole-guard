#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 && "$1" =~ ^[0-9]+$ ]] || {
  echo "usage: build-release-variants.sh LAST_PUBLISHED_VERSION_CODE" >&2
  exit 2
}

for channel in github fdroid; do
  ./gradlew \
    --no-daemon \
    --console=plain \
    --stacktrace \
    -Pfoxhole.splitApks=true \
    -Pfoxhole.sbom=true \
    "-Pfoxhole.updateChannel=$channel" \
    "-Pfoxhole.lastUploadedVersionCode=$1" \
    :app:validateReleaseSigningInputs \
    :app:assembleRelease \
    :app:verifyReleaseContainsNativeRuntime \
    :app:verifyReleaseContainsBaselineProfile \
    :app:verifyReleaseBuildConfigDefaults \
    :app:verifyReleaseJniSurface

  # Both channels share AGP output paths; retain each before the next build.
  destination="build/release-variants/$channel"
  mkdir -p "$destination"
  cp app/build/outputs/apk/release/app-arm64-v8a-release.apk "$destination/app.apk"
  cp app/build/outputs/mapping/release/mapping.txt "$destination/mapping.txt"
done

# AGP builds the AAB separately from ABI-split APKs.
./gradlew \
  --no-daemon \
  --console=plain \
  --stacktrace \
  -Pfoxhole.sbom=true \
  -Pfoxhole.updateChannel=github \
  "-Pfoxhole.lastUploadedVersionCode=$1" \
  :app:validateReleaseSigningInputs \
  :app:publicReleasePreflight
