#!/usr/bin/env bash
set -euo pipefail

# shellcheck source-path=SCRIPTDIR source=native-deps.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/native-deps.sh"

repo_root="$native_deps_repo_root"
i2pd_dir="${I2PD_SOURCE_DIR:-$repo_root/third_party/i2pd}"
version_file="$repo_root/third_party/i2pd.version"
read -r -a abis <<< "${I2PD_ABIS:-arm64-v8a armeabi-v7a x86_64}"
api="${I2PD_ANDROID_API:-26}"
work="$i2pd_work_dir"
build_root="${I2PD_BUILD_ROOT:-$repo_root/build/i2pd}"
output_root="${I2PD_OUTPUT_ROOT:-$repo_root/app/src/main/jniLibs}"
verify_host_paths="$repo_root/scripts/verify-native-host-paths.sh"
openssl_recipe="canonical-prefix-v1"
boost_recipe="path-remap-v1"
openssl_prefix="/usr/local/foxhole-openssl"

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

# F-Droid exports the app commit epoch; OpenSSL must use the pinned i2pd source epoch.
source_date_epoch="$(git -C "$i2pd_dir" show -s --format=%ct HEAD)"
export SOURCE_DATE_EPOCH="$source_date_epoch"
export ZERO_AR_DATE=1

remap_flags=(
  "-ffile-prefix-map=$i2pd_dir=/usr/src/i2pd"
  "-fmacro-prefix-map=$i2pd_dir=/usr/src/i2pd"
  "-fdebug-prefix-map=$i2pd_dir=/usr/src/i2pd"
  "-ffile-prefix-map=$work=/usr/src/i2pd-deps"
  "-fmacro-prefix-map=$work=/usr/src/i2pd-deps"
  "-fdebug-prefix-map=$work=/usr/src/i2pd-deps"
  "-ffile-prefix-map=$build_root=/usr/src/i2pd-build"
  "-fmacro-prefix-map=$build_root=/usr/src/i2pd-build"
  "-fdebug-prefix-map=$build_root=/usr/src/i2pd-build"
  "-ffile-prefix-map=$repo_root=/usr/src/foxhole-guard"
  "-fmacro-prefix-map=$repo_root=/usr/src/foxhole-guard"
  "-fdebug-prefix-map=$repo_root=/usr/src/foxhole-guard"
)
remap_cflags="${remap_flags[*]}"
boost_remap_flags=""
for remap_flag in "${remap_flags[@]}"; do
  boost_remap_flags+=" <compileflags>$remap_flag"
done

fetch_verified "$openssl_url" "$work/openssl.tgz" "$openssl_sha256" "OpenSSL $openssl_ver"
fetch_verified "$boost_url" "$work/$boost_us.tar.bz2" "$boost_sha256" "Boost $boost_ver"

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

cert_src="$i2pd_dir/contrib/certificates"
cert_dst="$repo_root/app/src/main/assets/i2pd/certificates"
if [[ -d "$cert_src" ]]; then
  mkdir -p "$cert_dst"
  cp -r "$cert_src/reseed" "$cert_src/family" "$cert_dst/"
  log "synced $(find "$cert_dst" -type f | wc -l | tr -d ' ') reseed/family certificates into assets"
fi

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

normalize_i2pd_build_id() {
  local binary="$1" scratch_dir="$2" without_id note digest build_id
  without_id="$scratch_dir/i2pd.without-build-id"
  note="$scratch_dir/i2pd.build-id.note"

  # LLD hashes input paths into its build ID even when the linked payload is byte-identical.
  cp "$binary" "$without_id"
  "$toolbin/llvm-objcopy" --remove-section=.note.gnu.build-id "$without_id"
  digest="$(sha256_file "$without_id")"
  build_id="${digest:0:40}"
  perl -e 'my $hex = shift; print pack("V3a4H*", 4, 20, 3, "GNU\0", $hex)' "$build_id" > "$note"
  "$toolbin/llvm-objcopy" --update-section ".note.gnu.build-id=$note" "$binary"
  rm -f "$without_id" "$note"
}

log "building i2pd $(awk -F= '$1=="ref"{print $2}' "$version_file") for: ${abis[*]}"
for abi in "${abis[@]}"; do
  ssl_target="$(ssl_target_for "$abi")"; clang="$(clang_for "$abi")"; triple="$(triple_for "$abi")"
  openssl_stage="$work/openssl-$openssl_recipe-$source_date_epoch-$abi"
  openssl_out="$openssl_stage$openssl_prefix"
  boost_out="$work/boost-$boost_recipe-$abi"

  if [[ ! -f "$openssl_out/lib/libcrypto.a" && ! -f "$openssl_out/lib64/libcrypto.a" ]]; then
    log "[$abi] OpenSSL"
    openssl_source="$work/openssl-src-$openssl_recipe-$abi"
    rm -rf "$openssl_source" "$openssl_stage"; mkdir -p "$openssl_source"
    tar xf "$work/openssl.tgz" -C "$openssl_source" --strip-components=1
    ( cd "$openssl_source"
      ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" PATH="$toolbin:$PATH" \
        ./Configure "$ssl_target" -D__ANDROID_API__="$api" no-shared no-tests no-apps \
          --prefix="$openssl_prefix" --openssldir=/etc/ssl --libdir=lib
      PATH="$toolbin:$PATH" make -j"$jobs" build_libs >/dev/null
      PATH="$toolbin:$PATH" make DESTDIR="$openssl_stage" install_dev >/dev/null )
  fi
  [[ -d "$openssl_out/lib64" ]] && ln -sfn lib64 "$openssl_out/lib" 2>/dev/null || true

  if [[ ! -f "$boost_out/lib/libboost_filesystem.a" ]]; then
    log "[$abi] Boost"
    [[ -d "$work/$boost_us" ]] || tar xf "$work/$boost_us.tar.bz2" -C "$work"
    b2tag="clang-ndk${abi//[-_]/}"
    ( cd "$work/$boost_us"
      [[ -x ./b2 ]] || ./bootstrap.sh --with-libraries=filesystem,program_options >/dev/null
      cat > "$work/user-config-$abi.jam" <<EOF
using clang : ndk${abi//[-_]/}
  : $toolbin/$clang
  : <archiver>$toolbin/llvm-ar <ranlib>$toolbin/llvm-ranlib <compileflags>-fPIC$boost_remap_flags
  ;
EOF
      ./b2 -q -j"$jobs" --user-config="$work/user-config-$abi.jam" \
        --prefix="$boost_out" --build-dir="$work/boost-build-$boost_recipe-$abi" \
        toolset="$b2tag" target-os=android architecture="$(b2arch_for "$abi")" \
        address-model="$(b2bits_for "$abi")" abi="$(b2abi_for "$abi")" binary-format=elf \
        link=static runtime-link=shared threading=multi variant=release cxxstd=17 --layout=system \
        --with-filesystem --with-program_options install >/dev/null )
  fi

  log "[$abi] i2pd"
  build_dir="$build_root/$abi"; out_dir="$output_root/$abi"
  rm -rf "$build_dir"; mkdir -p "$build_dir" "$out_dir"
  cmake -S "$i2pd_dir/build" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$remap_cflags" -DCMAKE_CXX_FLAGS="$remap_cflags" \
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
  normalize_i2pd_build_id "$out_dir/libi2pd.so" "$build_dir"
  "$verify_host_paths" "$out_dir/libi2pd.so"
  log "wrote $out_dir/libi2pd.so"
done

log "done — smoke-test an .i2p fetch over the local proxy on a device before trusting these binaries"
