#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
singbox_dir="${SINGBOX_SOURCE_DIR:-$repo_root/third_party/sing-box}"
version_file="$repo_root/third_party/sing-box.version"
default_repo="$(awk -F= '$1=="repo"{print $2}' "$version_file")"
default_ref="$(awk -F= '$1=="ref"{print $2}' "$version_file")"
default_commit="$(awk -F= '$1=="commit"{print $2}' "$version_file")"
singbox_repo="${SINGBOX_REPO:-$default_repo}"
singbox_ref="${SINGBOX_REF:-$default_ref}"
singbox_commit="${SINGBOX_COMMIT:-$default_commit}"

log() {
  printf '[build-libbox] %s\n' "$*"
}

if ! command -v go >/dev/null 2>&1; then
  echo "go is required to build libbox" >&2
  exit 1
fi

export PATH="$(go env GOPATH)/bin:$PATH"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "java_home must point to a java 17 installation" >&2
  exit 1
fi

if ! "$JAVA_HOME/bin/java" --version 2>&1 | head -n 1 | grep -q "17"; then
  brew_java17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  if [[ -x "$brew_java17/bin/java" ]] && "$brew_java17/bin/java" --version 2>&1 | head -n 1 | grep -q "17"; then
    export JAVA_HOME="$brew_java17"
  else
    echo "java_home must point to java 17 for gomobile/libbox builds" >&2
    exit 1
  fi
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" && -z "${ANDROID_HOME:-}" ]]; then
  echo "android_sdk_root or android_home must be set" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]] && [[ -f "/opt/homebrew/share/android-ndk/source.properties" ]]; then
  export ANDROID_NDK_HOME="/opt/homebrew/share/android-ndk"
fi

if [[ ! -d "$singbox_dir/.git" ]]; then
  log "cloning sing-box into $singbox_dir"
  mkdir -p "$(dirname "$singbox_dir")"
  git clone --filter=blob:none --no-checkout "$singbox_repo" "$singbox_dir"
fi

log "fetching sing-box commit $singbox_commit ($singbox_ref)"
git -C "$singbox_dir" fetch --depth 1 origin "$singbox_commit"
git -C "$singbox_dir" checkout --detach "$singbox_commit"

if [[ "$(git -C "$singbox_dir" rev-parse HEAD)" != "$singbox_commit" ]]; then
  echo "expected sing-box commit $singbox_commit from $singbox_ref" >&2
  exit 1
fi

(
  cd "$singbox_dir"
  log "running make lib_install"
  make lib_install
  log "initializing gomobile"
  "$(go env GOPATH)/bin/gomobile" init
  log "building Android libbox artifacts"
  make lib_android
)

mkdir -p "$repo_root/app/libs"
log "copying libbox artifacts into app/libs"
cp "$singbox_dir/libbox.aar" "$repo_root/app/libs/libbox.aar"
cp "$singbox_dir/libbox-legacy.aar" "$repo_root/app/libs/libbox-legacy.aar"

log "libbox artifacts copied into app/libs"
