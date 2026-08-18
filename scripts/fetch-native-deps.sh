#!/usr/bin/env bash
set -euo pipefail

# shellcheck source-path=SCRIPTDIR source=native-deps.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/native-deps.sh"

if native_offline; then
  echo "fetch-native-deps.sh needs the network; unset FOXHOLE_NATIVE_OFFLINE to run it" >&2
  exit 1
fi

want_i2pd=0
want_tor=0
if [[ $# -eq 0 ]]; then
  want_i2pd=1
  want_tor=1
else
  for arg in "$@"; do
    case "$arg" in
      i2pd) want_i2pd=1 ;;
      tor) want_tor=1 ;;
      *) echo "unknown component: $arg (expected i2pd or tor)" >&2; exit 2 ;;
    esac
  done
fi

log() { printf '[fetch-native-deps] %s\n' "$*"; }

seed_i2pd() {
  log "seeding i2pd sources into $i2pd_work_dir"
  fetch_verified "$openssl_url" "$i2pd_work_dir/openssl.tgz" "$openssl_sha256" "OpenSSL $openssl_ver"
  fetch_verified "$boost_url" "$i2pd_work_dir/$boost_us.tar.bz2" "$boost_sha256" "Boost $boost_ver"
}

seed_go_toolchain() {
  if [[ -x "$go_root_dir/bin/go" ]]; then
    log "Go toolchain already installed: $go_root_dir"
    return 0
  fi
  local platform digest tarball
  platform="$(go_host_platform)"
  digest="$(go_tarball_sha256_for "$go_version-$platform")" || {
    echo "no pinned sha256 for $go_version on $platform; add one to scripts/native-deps.sh" >&2
    exit 1
  }
  tarball="$(dirname "$go_root_dir")/$go_version.$platform.tar.gz"
  fetch_verified "https://go.dev/dl/$go_version.$platform.tar.gz" "$tarball" "$digest" \
    "Go toolchain $go_version.$platform"
  rm -rf "$go_root_dir"
  mkdir -p "$go_root_dir"
  tar xf "$tarball" -C "$go_root_dir" --strip-components=1
  log "installed Go toolchain: $("$go_root_dir/bin/go" env GOVERSION) at $go_root_dir"
}

seed_git_source() {
  local name="$1" repo="$2" ref="$3" commit="$4" dir="$tor_work_dir/$1" head
  mkdir -p "$dir"
  if [[ ! -d "$dir/.git" ]]; then
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$repo"
  fi
  if [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]]; then
    log "fetching $name @ $ref"
    git -C "$dir" fetch -q --depth 1 origin "$ref"
    git -C "$dir" checkout -q FETCH_HEAD
  fi
  head="$(git -C "$dir" rev-parse HEAD)"
  [[ "$head" == "$commit" ]] || { echo "$name pin mismatch: expected $commit, got $head" >&2; exit 1; }
  log "verified $name commit=$commit"
}

seed_tor() {
  seed_go_toolchain
  log "seeding Tor transport sources into $tor_work_dir"
  seed_git_source lyrebird "$lyrebird_repo" "$lyrebird_ref" "$lyrebird_commit"
  seed_git_source conjure "$conjure_repo" "$conjure_ref" "$conjure_commit"
  mkdir -p "$go_mod_cache"
  local src
  for src in lyrebird conjure; do
    log "downloading $src Go modules into $go_mod_cache"
    ( cd "$tor_work_dir/$src" && env \
        GOTOOLCHAIN=local \
        GOMODCACHE="$go_mod_cache" \
        GOFLAGS=-mod=mod \
        "$go_root_dir/bin/go" mod download all )
  done
}

[[ $want_i2pd -eq 1 ]] && seed_i2pd
[[ $want_tor -eq 1 ]] && seed_tor

log "done - the Gradle phase can now run with FOXHOLE_NATIVE_OFFLINE=1"
