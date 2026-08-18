#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB_ROOT="${1:-$ROOT/app/build/generated/foxCoreNativeLibs}"
CORE_ROOT="${2:-${FOXCORE_SOURCE_ROOT:-$ROOT/../foxhole-core}}"
JAVA_SOURCE="$ROOT/core/runtime/src/main/java/com/foxhole/core/runtime/FoxholeNativeEngine.java"
PREFIX="Java_com_foxhole_core_runtime_FoxholeNativeEngine_"
NDK_VERSION="29.0.14206865"

find_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return
  fi
  for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk"; do
    if [[ -n "$sdk" && -d "$sdk/ndk/$NDK_VERSION" ]]; then
      printf '%s\n' "$sdk/ndk/$NDK_VERSION"
      return
    fi
  done
  return 1
}

NDK="$(find_ndk)" || {
  echo "Android NDK $NDK_VERSION was not found." >&2
  exit 1
}
READELF=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
  if [[ -x "$candidate" ]]; then
    READELF="$candidate"
    break
  fi
done
[[ -n "$READELF" ]] || {
  echo "llvm-readelf was not found under $NDK." >&2
  exit 1
}

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/foxhole-jni-seam.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

sed -nE \
  's/.*public static native[[:space:]]+[^[:space:]]+[[:space:]]+(native[A-Za-z0-9_]+)\(.*/\1/p' \
  "$JAVA_SOURCE" | sort -u > "$TMP_ROOT/java"

find "$ROOT/core/runtime/src/main/kotlin" "$ROOT/app/src/main/kotlin" \
  -type f -name '*.kt' -exec \
  grep -hEo 'FoxholeNativeEngine(\.|::)native[A-Za-z0-9_]+' {} + | \
  sed -E 's/.*(\.|::)(native[A-Za-z0-9_]+)/\2/' | sort -u > "$TMP_ROOT/kotlin"

printf '%s\n' \
  nativeConfirmContinuity \
  nativeImportLink \
  nativeImportSubscription | sort -u > "$TMP_ROOT/compatibility-only"
comm -23 "$TMP_ROOT/java" "$TMP_ROOT/kotlin" > "$TMP_ROOT/java-only"
comm -13 "$TMP_ROOT/java" "$TMP_ROOT/kotlin" > "$TMP_ROOT/kotlin-only"
if ! diff -u "$TMP_ROOT/compatibility-only" "$TMP_ROOT/java-only"; then
  echo "Unexpected Java declarations have no production Kotlin boundary reference." >&2
  exit 1
fi
if [[ -s "$TMP_ROOT/kotlin-only" ]]; then
  echo "Production Kotlin references JNI methods missing from the Java facade:" >&2
  sed 's/^/  /' "$TMP_ROOT/kotlin-only" >&2
  exit 1
fi

printf '%s\n' nativeStart > "$TMP_ROOT/allowed-rust-only"
checked=0
for lib in "$LIB_ROOT"/*/libfoxhole_native.so; do
  [[ -f "$lib" ]] || continue
  checked=$((checked + 1))
  abi="$(basename "$(dirname "$lib")")"
  "$READELF" -Ws "$lib" | \
    sed -nE "s/.*[[:space:]]${PREFIX}(native[A-Za-z0-9_]+)$/\1/p" | \
    sort -u > "$TMP_ROOT/exports-$abi"
  comm -23 "$TMP_ROOT/exports-$abi" "$TMP_ROOT/java" > "$TMP_ROOT/rust-only-$abi"
  comm -13 "$TMP_ROOT/exports-$abi" "$TMP_ROOT/java" > "$TMP_ROOT/java-only-$abi"
  if ! diff -u "$TMP_ROOT/allowed-rust-only" "$TMP_ROOT/rust-only-$abi"; then
    echo "$abi: unexpected Rust-only FoxholeNativeEngine surface." >&2
    exit 1
  fi
  if [[ -s "$TMP_ROOT/java-only-$abi" ]]; then
    echo "$abi: Java declares JNI methods missing from the shipped ELF:" >&2
    sed 's/^/  /' "$TMP_ROOT/java-only-$abi" >&2
    exit 1
  fi
  exports="$(wc -l < "$TMP_ROOT/exports-$abi" | tr -d ' ')"
  declarations="$(wc -l < "$TMP_ROOT/java" | tr -d ' ')"
  references="$(wc -l < "$TMP_ROOT/kotlin" | tr -d ' ')"
  echo "$abi: JNI seam PASS (elf_exports=$exports, java_declarations=$declarations, kotlin_references=$references, compatibility_only=3, rust_only=nativeStart)"
done

if [[ "$checked" -eq 0 ]]; then
  echo "No libfoxhole_native.so files found under $LIB_ROOT." >&2
  exit 1
fi

[[ -f "$CORE_ROOT/crates/foxcore-android/src/lib.rs" ]] || {
  echo "FoxCore source root is missing: $CORE_ROOT" >&2
  exit 1
}
