#!/usr/bin/env bash
set -euo pipefail

# shellcheck source-path=SCRIPTDIR source=native-deps.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/native-deps.sh"

repo_root="$native_deps_repo_root"
assets_root="$repo_root/app/src/main/assets/tor"
work_root="$tor_work_dir"

read -r -a abis <<< "${TOR_TRANSPORT_ABIS:-arm64-v8a}"
api="${TOR_TRANSPORT_API:-26}"

go_bin="${GO:-}"
if [[ -z "$go_bin" && -x "$go_root_dir/bin/go" ]]; then
  go_bin="$go_root_dir/bin/go"
fi
go_bin="${go_bin:-go}"
command -v "$go_bin" >/dev/null || { echo "go not found; set GO or run scripts/fetch-native-deps.sh" >&2; exit 1; }

# An ambient GOROOT can point a pinned Go binary at another release's tools.
unset GOROOT GOTOOLDIR

go_have="$("$go_bin" env GOVERSION 2>/dev/null || true)"
if [[ "$go_have" == "$go_version" ]]; then
  go_toolchain="local"
else
  require_offline_seed "Go toolchain $go_version (found ${go_have:-none} at $go_bin)"
  go_toolchain="$go_version"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for candidate in "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"/*; do
    if [[ -d "$candidate" ]]; then
      ANDROID_NDK_HOME="$candidate"
    fi
  done
  export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"
fi
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME or install an NDK in the Android SDK}"

# The NDK ships one host toolchain per platform; darwin-x86_64 is also the Apple Silicon path.
host_tag="linux-x86_64"
if [[ "$(uname -s)" == "Darwin" ]]; then
  host_tag="darwin-x86_64"
fi
toolchain_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
[[ -d "$toolchain_bin" ]] || { echo "NDK toolchain not found: $toolchain_bin" >&2; exit 1; }

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
  local name="$1" repo="$2" ref="$3" commit="$4"
  local dir="$work_root/$name"
  if [[ ! -d "$dir/.git" ]]; then
    require_offline_seed "$name checkout ($dir)"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$repo"
  fi
  if [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]]; then
    require_offline_seed "$name checkout at $commit ($dir)"
    echo "fetching $name @ $ref" >&2
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
  local proxy_env=()
  if native_offline; then
    proxy_env=(GOPROXY=off GOFLAGS=-mod=readonly)
  fi
  # -trimpath and an empty -buildid keep the output reproducible across machines and checkouts.
  ( cd "$src_dir" && env \
      GOTOOLCHAIN="$go_toolchain" \
      GOMODCACHE="$go_mod_cache" \
      GOOS=android \
      GOARCH="$goarch" \
      GOARM=7 \
      CGO_ENABLED=1 \
      CC="$toolchain_bin/$cc" \
      ${proxy_env[@]+"${proxy_env[@]}"} \
      "$go_bin" build \
        -mod=readonly \
        -modfile="$repo_root/config/native/${name/conjure-client/conjure}/go.mod" \
        -trimpath \
        -buildvcs=false \
        -ldflags "-s -w -buildid= -checklinkname=0" \
        -o "$out" \
        "$pkg" )
  echo "  built $name ($abi): $(du -h "$out" | cut -f1)"
}

echo "go: $("$go_bin" env GOVERSION) ($go_bin), GOTOOLCHAIN=$go_toolchain, GOMODCACHE=$go_mod_cache"
lyrebird_src="$(fetch_source lyrebird "$lyrebird_repo" "$lyrebird_ref" "$lyrebird_commit")"
conjure_src="$(fetch_source conjure "$conjure_repo" "$conjure_ref" "$conjure_commit")"

python3 "$repo_root/scripts/prepare-tor-dependency.py" "$go_mod_cache" "$work_root/conjure-patched"

for abi in "${abis[@]}"; do
  echo "== $abi"
  pt_dir="$assets_root/$abi/tor/pluggable_transports"
  build_one lyrebird "$lyrebird_src" ./cmd/lyrebird "$abi" "$pt_dir/lyrebird"
  # conjure-client is only shipped for the ARM ABIs upstream; build it wherever it compiles.
  build_one conjure-client "$conjure_src" ./client "$abi" "$pt_dir/conjure-client"
done

echo "done: transports built from source for ${abis[*]}"
