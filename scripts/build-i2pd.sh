#!/usr/bin/env bash
#
# Builds libi2pd.so (the i2pd daemon executable, packaged under a lib*.so name so Android extracts it
# into nativeLibraryDir and lets us exec it, exactly like libTor.so) from the vendored i2pd source at
# third_party/i2pd, for every shipped ABI. Reproducible from a clean checkout — no prebuilt ELF in
# the repo — which is what F-Droid's scanner requires.
#
# STATUS: authored + executed end-to-end 2026-07-12 on Ubuntu 26.04 with NDK r28c. The arm64-v8a
# artifact was smoke-tested on a device: i2pd v2.60.0 boots, reseeds against the bundled certificates,
# and builds inbound+outbound tunnels. Re-run on a clean box to reproduce all four ABIs.
#
# What it does, per shipped ABI (arm64-v8a armeabi-v7a x86_64):
#   1. Cross-compiles OpenSSL (static, no-shared) from the pinned source tarball.
#   2. Cross-compiles Boost filesystem+program_options (static) directly with b2 — the only two
#      components i2pd needs. (Boost-for-Android's helper caps at 1.82; b2 handles any version.)
#   3. Configures i2pd against those + the NDK's own zlib, links a dynamic PIE (WITH_STATIC=OFF: a
#      fully static Android binary breaks getaddrinfo/NSS, which i2pd needs for reseed DNS), with a
#      static libc++ so no libc++_shared.so is required at runtime.
#   4. Copies build/i2pd -> app/src/main/jniLibs/<abi>/libi2pd.so (extractable, +x).
# It also syncs i2pd's reseed/family certificates into the app assets: without them reseed cannot
# verify the su3 bundles and the router never bootstraps.
#
# Required env:
#   ANDROID_NDK_HOME          - NDK r28 root (has build/cmake/android.toolchain.cmake)
# Optional env:
#   I2PD_ABIS                 - space-separated ABI list (default: all shipped ABIs)
#   I2PD_WORK_DIR             - scratch dir for OpenSSL/Boost builds (default: build/i2pd-deps)
#   I2PD_OPENSSL_VERSION      - default 3.5.4
#   I2PD_BOOST_VERSION        - default 1.84.0
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
i2pd_dir="${I2PD_SOURCE_DIR:-$repo_root/third_party/i2pd}"
version_file="$repo_root/third_party/i2pd.version"
abis=(${I2PD_ABIS:-arm64-v8a armeabi-v7a x86_64})
api="${I2PD_ANDROID_API:-26}"
work="${I2PD_WORK_DIR:-$repo_root/build/i2pd-deps}"
openssl_ver="${I2PD_OPENSSL_VERSION:-3.5.4}"
boost_ver="${I2PD_BOOST_VERSION:-1.84.0}"
boost_us="boost_${boost_ver//./_}"

log() { printf '[build-i2pd] %s\n' "$*"; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  sdk_ndk_root="$android_sdk_root/ndk"
  if [[ -n "$android_sdk_root" && -d "$sdk_ndk_root" ]]; then
    while IFS= read -r ndk_candidate; do
      if [[ -f "$ndk_candidate/source.properties" ]]; then
        export ANDROID_NDK_HOME="$ndk_candidate"
      fi
    done < <(find "$sdk_ndk_root" -mindepth 1 -maxdepth 1 -type d | sort)
  elif [[ -f "/opt/homebrew/share/android-ndk/source.properties" ]]; then
    export ANDROID_NDK_HOME="/opt/homebrew/share/android-ndk"
  fi
fi
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME or Android SDK root with an installed NDK}"
toolchain="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[[ -f "$toolchain" ]] || { echo "NDK cmake toolchain not found: $toolchain" >&2; exit 1; }
# The NDK ships exactly one host toolchain per platform (darwin-x86_64 is also the Apple Silicon
# tag); resolve it instead of hardcoding the Linux one so the bootstrap is reproducible on macOS.
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
toolbin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
[[ -d "$toolbin" ]] || { echo "NDK host toolchain not found: $toolbin" >&2; exit 1; }
sysroot="$toolbin/../sysroot"
jobs="$( (command -v nproc >/dev/null 2>&1 && nproc) || sysctl -n hw.ncpu )"
mkdir -p "$work"

dl() { curl -fL --retry 6 --retry-delay 4 --retry-all-errors -C - --connect-timeout 20 -o "$2" "$1"; }

# ---- source tarballs (pre-seed $work/openssl.tgz and $work/$boost_us.tar.bz2 to skip download) ----
if [[ ! -f "$work/openssl.tgz" ]]; then
  log "downloading OpenSSL $openssl_ver"
  dl "https://github.com/openssl/openssl/releases/download/openssl-$openssl_ver/openssl-$openssl_ver.tar.gz" "$work/openssl.tgz"
fi
if [[ ! -f "$work/$boost_us.tar.bz2" ]]; then
  log "downloading Boost $boost_ver"
  dl "https://archives.boost.io/release/$boost_ver/source/$boost_us.tar.bz2" "$work/$boost_us.tar.bz2"
fi

# ---- apply tracked i2pd source patches idempotently (same approach as build-libbox.sh) ----
shopt -s nullglob
for patch_file in "$repo_root"/scripts/patches/i2pd-*.patch; do
  patch_name="$(basename "$patch_file")"
  if git -C "$i2pd_dir" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
    log "patch already applied: $patch_name"
  else
    log "applying patch: $patch_name"
    git -C "$i2pd_dir" apply "$patch_file"
  fi
done
shopt -u nullglob

# ---- sync reseed/family certificates into app assets (required for reseed to verify su3) ----
cert_src="$i2pd_dir/contrib/certificates"
cert_dst="$repo_root/app/src/main/assets/i2pd/certificates"
if [[ -d "$cert_src" ]]; then
  mkdir -p "$cert_dst"
  cp -r "$cert_src/reseed" "$cert_src/family" "$cert_dst/"
  log "synced $(find "$cert_dst" -type f | wc -l | tr -d ' ') reseed/family certificates into assets"
fi

# ---- per-ABI toolchain mapping ----
ssl_target_for() { case "$1" in
  arm64-v8a) echo android-arm64 ;; armeabi-v7a) echo android-arm ;;
  x86) echo android-x86 ;; x86_64) echo android-x86_64 ;; esac; }
clang_for() { case "$1" in
  arm64-v8a) echo "aarch64-linux-android$api-clang++" ;; armeabi-v7a) echo "armv7a-linux-androideabi$api-clang++" ;;
  x86) echo "i686-linux-android$api-clang++" ;; x86_64) echo "x86_64-linux-android$api-clang++" ;; esac; }
triple_for() { case "$1" in
  arm64-v8a) echo aarch64-linux-android ;; armeabi-v7a) echo arm-linux-androideabi ;;
  x86) echo i686-linux-android ;; x86_64) echo x86_64-linux-android ;; esac; }
b2arch_for() { case "$1" in arm64-v8a|armeabi-v7a) echo arm ;; x86|x86_64) echo x86 ;; esac; }
b2bits_for() { case "$1" in arm64-v8a|x86_64) echo 64 ;; armeabi-v7a|x86) echo 32 ;; esac; }
b2abi_for()  { case "$1" in arm64-v8a|armeabi-v7a) echo aapcs ;; x86|x86_64) echo sysv ;; esac; }

log "building i2pd $(awk -F= '$1=="ref"{print $2}' "$version_file") for: ${abis[*]}"
for abi in "${abis[@]}"; do
  ssl_target="$(ssl_target_for "$abi")"; clang="$(clang_for "$abi")"; triple="$(triple_for "$abi")"
  openssl_out="$work/openssl-$abi"; boost_out="$work/boost-$abi"

  # OpenSSL
  if [[ ! -f "$openssl_out/lib/libcrypto.a" && ! -f "$openssl_out/lib64/libcrypto.a" ]]; then
    log "[$abi] OpenSSL"
    rm -rf "$work/openssl-src-$abi"; mkdir -p "$work/openssl-src-$abi"
    tar xf "$work/openssl.tgz" -C "$work/openssl-src-$abi" --strip-components=1
    ( cd "$work/openssl-src-$abi"
      ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" PATH="$toolbin:$PATH" \
        ./Configure "$ssl_target" -D__ANDROID_API__=$api no-shared no-tests no-apps --prefix="$openssl_out"
      PATH="$toolbin:$PATH" make -j"$jobs" build_libs >/dev/null
      PATH="$toolbin:$PATH" make install_dev >/dev/null )
  fi
  [[ -d "$openssl_out/lib64" ]] && ln -sfn lib64 "$openssl_out/lib" 2>/dev/null || true

  # Boost (filesystem + program_options) via b2
  if [[ ! -f "$boost_out/lib/libboost_filesystem.a" ]]; then
    log "[$abi] Boost"
    [[ -d "$work/$boost_us" ]] || tar xf "$work/$boost_us.tar.bz2" -C "$work"
    # b2 splits a hyphenated toolset version tag into subfeatures (clang-armeabi-v7a breaks), so the
    # tag must be alphanumeric only.
    b2tag="clang-ndk${abi//[-_]/}"
    ( cd "$work/$boost_us"
      [[ -x ./b2 ]] || ./bootstrap.sh --with-libraries=filesystem,program_options >/dev/null
      cat > "$work/user-config-$abi.jam" <<EOF
using clang : ndk${abi//[-_]/}
  : $toolbin/$clang
  : <archiver>$toolbin/llvm-ar <ranlib>$toolbin/llvm-ranlib <compileflags>-fPIC
  ;
EOF
      ./b2 -q -j"$jobs" --user-config="$work/user-config-$abi.jam" \
        --prefix="$boost_out" --build-dir="$work/boost-build-$abi" \
        toolset="$b2tag" target-os=android architecture="$(b2arch_for "$abi")" \
        address-model="$(b2bits_for "$abi")" abi="$(b2abi_for "$abi")" binary-format=elf \
        link=static runtime-link=shared threading=multi variant=release cxxstd=17 --layout=system \
        --with-filesystem --with-program_options install >/dev/null )
  fi

  # i2pd -> libi2pd.so
  log "[$abi] i2pd"
  build_dir="$repo_root/build/i2pd/$abi"; out_dir="$repo_root/app/src/main/jniLibs/$abi"
  rm -rf "$build_dir"; mkdir -p "$build_dir" "$out_dir"
  cmake -S "$i2pd_dir/build" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DWITH_STATIC=OFF -DWITH_UPNP=OFF -DWITH_LIBRARY=OFF -DWITH_BINARY=ON -DWITH_ADDRSANITIZER=OFF \
    -DCMAKE_FIND_PACKAGE_PREFER_CONFIG=TRUE -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DOPENSSL_ROOT_DIR="$openssl_out" -DOPENSSL_USE_STATIC_LIBS=ON \
    -DOpenSSL_DIR="$openssl_out/lib/cmake/OpenSSL" \
    -DBoost_DIR="$boost_out/lib/cmake/Boost-$boost_ver" -DBOOST_ROOT="$boost_out" \
    -DBoost_INCLUDE_DIR="$boost_out/include" -DBoost_USE_STATIC_LIBS=ON \
    -DZLIB_INCLUDE_DIR="$sysroot/usr/include" -DZLIB_LIBRARY="$sysroot/usr/lib/$triple/$api/libz.so"
  cmake --build "$build_dir" --target i2pd -j"$jobs"
  cp "$build_dir/i2pd" "$out_dir/libi2pd.so"
  "$toolbin/llvm-strip" "$out_dir/libi2pd.so" 2>/dev/null || true
  log "wrote $out_dir/libi2pd.so"
done

log "done — smoke-test an .i2p fetch over the local proxy on a device before trusting these binaries"
