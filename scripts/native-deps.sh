#!/usr/bin/env bash
# shellcheck shell=bash
# shellcheck disable=SC2034  # every pin below is consumed by the scripts that source this file


openssl_ver="${I2PD_OPENSSL_VERSION:-3.5.8}"
case "$openssl_ver" in
  3.5.8) openssl_sha256="${I2PD_OPENSSL_SHA256:-a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2}" ;;
  *) openssl_sha256="${I2PD_OPENSSL_SHA256:?set I2PD_OPENSSL_SHA256 when overriding I2PD_OPENSSL_VERSION}" ;;
esac
openssl_url="https://github.com/openssl/openssl/releases/download/openssl-$openssl_ver/openssl-$openssl_ver.tar.gz"

boost_ver="${I2PD_BOOST_VERSION:-1.84.0}"
case "$boost_ver" in
  1.84.0) boost_sha256="${I2PD_BOOST_SHA256:-cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454}" ;;
  *) boost_sha256="${I2PD_BOOST_SHA256:?set I2PD_BOOST_SHA256 when overriding I2PD_BOOST_VERSION}" ;;
esac
boost_us="boost_${boost_ver//./_}"
boost_url="https://archives.boost.io/release/$boost_ver/source/$boost_us.tar.bz2"

go_version="${TOR_TRANSPORT_GO:-go1.26.8}"
go_tarball_sha256_for() {
  case "$1" in
    go1.26.8-linux-amd64)   echo d0f743b33e8d8945e6b1f432edd15785c70507121d6e2a723b21285eddf8b57b ;;
    go1.26.8-linux-arm64)   echo 211ffced9dcb9633a55eac6364816ec0ddd951389a740e88fa8b3337971bdda0 ;;
    go1.26.8-darwin-amd64)  echo 186be014105aa6542b767d2c6ed5cca10a0214bdff809ef1724022a8c7894150 ;;
    go1.26.8-darwin-arm64)  echo a012b25b571bd0138a03dcd25375ceba866fe5ca822f426d2c66a4de56fd3f4b ;;
    *) return 1 ;;
  esac
}

lyrebird_repo="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git"
lyrebird_ref="lyrebird-0.8.1"
lyrebird_commit="0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1"

conjure_repo="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/conjure.git"
conjure_commit="0090962226b82aa4a8fc38506f9b98de67d0781e"
conjure_ref="$conjure_commit"

native_deps_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
native_deps_dir="${FOXHOLE_NATIVE_DEPS_DIR:-$(dirname "$native_deps_repo_root")/foxhole-native-deps}"
i2pd_work_dir="${I2PD_WORK_DIR:-$native_deps_dir/i2pd-deps}"
tor_work_dir="${TOR_TRANSPORT_WORK_DIR:-$native_deps_dir/tor-transports}"
go_root_dir="${FOXHOLE_GOROOT:-$native_deps_dir/$go_version}"
go_mod_cache="${FOXHOLE_GOMODCACHE:-$native_deps_dir/gomodcache}"


native_offline() { [[ "${FOXHOLE_NATIVE_OFFLINE:-0}" == "1" ]]; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_sha256() {
  local file="$1" expected="$2" label="$3" actual
  actual="$(sha256_file "$file")"
  if [[ "$actual" != "$expected" ]]; then
    printf '%s checksum mismatch: expected %s, got %s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  printf '[native-deps] verified %s sha256=%s\n' "$label" "$expected"
}

require_offline_seed() {
  local what="$1"
  if native_offline; then
    cat >&2 <<EOF
[native-deps] offline build needs a pre-seeded $what, and it is not present.
[native-deps] Seed it before the Gradle phase:  scripts/fetch-native-deps.sh
[native-deps] (FOXHOLE_NATIVE_OFFLINE=1 is set, so nothing will be downloaded here.)
EOF
    exit 1
  fi
}

fetch_verified() {
  local url="$1" dest="$2" expected="$3" label="$4"
  if [[ -f "$dest" ]]; then
    verify_sha256 "$dest" "$expected" "$label"
    return 0
  fi
  require_offline_seed "$label ($dest)"
  printf '[native-deps] downloading %s\n' "$label"
  mkdir -p "$(dirname "$dest")"
  curl -fL --retry 6 --retry-delay 4 --retry-all-errors --connect-timeout 20 \
    --no-progress-meter -o "$dest.part" "$url"
  verify_sha256 "$dest.part" "$expected" "$label"
  mv "$dest.part" "$dest"
}

go_host_platform() {
  local os arch
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux) os=linux ;;
    *) echo "unsupported build host: $(uname -s)" >&2; return 1 ;;
  esac
  case "$(uname -m)" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64 | amd64) arch=amd64 ;;
    *) echo "unsupported build host arch: $(uname -m)" >&2; return 1 ;;
  esac
  echo "$os-$arch"
}
