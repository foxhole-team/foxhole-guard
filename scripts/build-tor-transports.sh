#!/usr/bin/env bash
# Build the Tor pluggable transports (lyrebird, conjure-client) from pinned upstream source for
# every shipped ABI. Reproducible from a clean checkout — no prebuilt executable lives in git.
#
# Why this exists: Android executes only from `nativeLibraryDir`, so the transports have to ride
# inside the APK as `lib*.so` and cannot be fetched at runtime (W^X). "Everything from source"
# therefore means compiling them here, the same way `build-i2pd.sh` handles i2pd.
#
# Output (consumed by `preparePrivacyNativeLibs`, which renames them to lib*.so):
#   app/src/main/assets/tor/<abi>/tor/pluggable_transports/{lyrebird,conjure-client}
#
# Environment:
#   ANDROID_NDK_HOME       - NDK root; falls back to the Android SDK's newest installed NDK
#   TOR_TRANSPORT_ABIS     - space-separated ABI list (default: arm64-v8a)
#   TOR_TRANSPORT_API      - Android API level for the toolchain (default: 26, the app's minSdk)
#   GO                     - go binary (default: go from PATH)
#   TOR_TRANSPORT_GO       - Go toolchain to build with (default: go1.25.8)
#
# The Go version is pinned because it is part of the output; any `go` on PATH new enough to honour
# GOTOOLCHAIN (1.21+) fetches and verifies the pinned one on demand. `-checklinkname=0` is required
# with it: lyrebird's snowflake dependency reaches `net.zoneCache` through //go:linkname, which the
# linker has rejected by default since Go 1.23.
set -euo pipefail

# Pinned upstream revisions. lyrebird ships tags; conjure has none, so it is pinned by commit.
LYREBIRD_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git"
# The commit the annotated tag peels to, not the tag object's own hash.
LYREBIRD_REF="lyrebird-0.8.1"
LYREBIRD_COMMIT="0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1"

CONJURE_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/conjure.git"
CONJURE_REF="main"
CONJURE_COMMIT="0090962226b82aa4a8fc38506f9b98de67d0781e"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_root="$repo_root/app/src/main/assets/tor"
work_root="${TMPDIR:-/tmp}/foxhole-tor-transports"

abis=(${TOR_TRANSPORT_ABIS:-arm64-v8a})
api="${TOR_TRANSPORT_API:-26}"
go_bin="${GO:-go}"
go_toolchain="${TOR_TRANSPORT_GO:-go1.25.8}"

command -v "$go_bin" >/dev/null || { echo "go not found; set GO or install it" >&2; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for candidate in "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"/*; do
    [[ -d "$candidate" ]] && ANDROID_NDK_HOME="$candidate"
  done
  export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"
fi
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME or install an NDK in the Android SDK}"

# The NDK ships one host toolchain per platform; darwin-x86_64 is also the Apple Silicon path.
host_tag="linux-x86_64"
[[ "$(uname -s)" == "Darwin" ]] && host_tag="darwin-x86_64"
toolchain_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
[[ -d "$toolchain_bin" ]] || { echo "NDK toolchain not found: $toolchain_bin" >&2; exit 1; }

# Go's android/* targets need cgo, which is what produces the PIE binary linked against the
# platform libc that `/system/bin/linker64` can load.
go_target_for() {
  case "$1" in
    arm64-v8a)   echo "arm64 aarch64-linux-android$api-clang" ;;
    armeabi-v7a) echo "arm armv7a-linux-androideabi$api-clang" ;;
    x86_64)      echo "amd64 x86_64-linux-android$api-clang" ;;
    x86)         echo "386 i686-linux-android$api-clang" ;;
    *) echo "unsupported ABI: $1" >&2; return 1 ;;
  esac
}

fetch_source() {
  # Separate statements: `local` expands every argument before it assigns any of them, so `$name`
  # would still be unbound if `dir` were declared on the same line.
  local name="$1" repo="$2" ref="$3" commit="$4"
  local dir="$work_root/$name"
  if [[ ! -d "$dir/.git" ]]; then
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$repo"
  fi
  if [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]]; then
    echo "fetching $name @ $ref"
    git -C "$dir" fetch -q --depth 1 origin "$ref"
    git -C "$dir" checkout -q FETCH_HEAD
  fi
  local head
  head="$(git -C "$dir" rev-parse HEAD)"
  [[ "$head" == "$commit" ]] || {
    echo "$name pin mismatch: expected $commit, got $head" >&2
    exit 1
  }
  echo "$dir"
}

build_one() {
  local name="$1" src_dir="$2" pkg="$3" abi="$4" out="$5"
  local goarch cc
  read -r goarch cc <<<"$(go_target_for "$abi")"

  mkdir -p "$(dirname "$out")"
  # -trimpath and an empty -buildid keep the output reproducible across machines and checkouts.
  ( cd "$src_dir" && env \
      GOTOOLCHAIN="$go_toolchain" \
      GOOS=android \
      GOARCH="$goarch" \
      GOARM=7 \
      CGO_ENABLED=1 \
      CC="$toolchain_bin/$cc" \
      "$go_bin" build \
        -trimpath \
        -buildvcs=false \
        -ldflags "-s -w -buildid= -checklinkname=0" \
        -o "$out" \
        "$pkg" )
  echo "  built $name ($abi): $(du -h "$out" | cut -f1)"
}

lyrebird_src="$(fetch_source lyrebird "$LYREBIRD_REPO" "$LYREBIRD_REF" "$LYREBIRD_COMMIT")"
conjure_src="$(fetch_source conjure "$CONJURE_REPO" "$CONJURE_REF" "$CONJURE_COMMIT")"

for abi in "${abis[@]}"; do
  echo "== $abi"
  pt_dir="$assets_root/$abi/tor/pluggable_transports"
  build_one lyrebird "$lyrebird_src" ./cmd/lyrebird "$abi" "$pt_dir/lyrebird"
  # conjure-client is only shipped for the ARM ABIs upstream; build it wherever it compiles.
  build_one conjure-client "$conjure_src" ./client "$abi" "$pt_dir/conjure-client"
done

echo "done: transports built from source for ${abis[*]}"
