#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$repo_root/scripts/native-deps.sh"

die() { printf '[docker-native] %s\n' "$*" >&2; exit 1; }
log() { printf '[docker-native] %s\n' "$*"; }
pin() { awk -F= -v key="$1" '$1 == key { print substr($0, length(key) + 2) }' "$repo_root/third_party/i2pd.version"; }

normalize_abis() {
  local input="$1" abi seen=" " result=""
  input="${input//,/ }"
  for abi in $input; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) die "unsupported Android ABI: $abi" ;; esac
    if [[ "$seen" != *" $abi "* ]]; then
      result="${result:+$result }$abi"
      seen+="$abi "
    fi
  done
  [[ -n "$result" ]] || die "at least one Android ABI is required"
  printf '%s\n' "$result"
}

check_path() {
  [[ "$1" != *','* && "$1" != *$'\n'* ]] || die "Docker mount paths must not contain commas or newlines"
  [[ ! -L "$1" ]] || die "output mount must not be a symlink: $1"
}

check_output() {
  check_path "$1"
  python3 - "$repo_root" "$1" <<'PY'
from pathlib import Path
import sys
root, output = map(Path, sys.argv[1:])
if output.resolve() != output or not output.is_relative_to(root):
    raise SystemExit('Native output must stay inside its real repository directory')
PY
}

prepare_i2pd_source() {
  local commit="$(pin commit)" url="$(pin repo)" base="/native/git/i2pd-$(pin commit)"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "invalid i2pd commit pin"
  [[ "$url" == https://github.com/PurpleI2P/i2pd ]] || die "unexpected i2pd source repository"
  if [[ ! -d "$base" ]]; then
    native_offline && die "offline cache lacks pinned i2pd Git metadata"
    mkdir -p /native/git
    git init --bare -q "$base"
  fi
  if ! git -C "$base" cat-file -e "$commit^{commit}" 2>/dev/null; then
    native_offline && die "offline cache lacks the pinned i2pd commit"
    git -C "$base" fetch --depth 1 "$url" "$commit"
    git -C "$base" update-ref refs/heads/pinned "$commit"
  fi
  git -C "$base" cat-file -e "$commit^{commit}" || die "cached i2pd commit is unavailable"
  I2PD_SOURCE_DIR="$(python3 - "$repo_root" "$base" "$commit" "$HOST_FILE_LIST" <<'PY'
from pathlib import Path, PurePosixPath
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

repo, base, commit, manifest = sys.argv[1:]
vendor = Path(repo) / 'third_party/i2pd'
paths = sorted(filter(None, Path(manifest).read_bytes().split(b'\0')))
if not paths:
    raise SystemExit('Empty host i2pd tracked source manifest')
patches = sorted((Path(repo) / 'scripts/patches').glob('i2pd-*.patch'))
digest = hashlib.sha256(commit.encode())
records = []
for encoded in paths:
    name = os.fsdecode(encoded)
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts or '.git' in relative.parts:
        raise SystemExit('Unsafe tracked source path')
    source = vendor / name
    if source.is_symlink():
        kind, data = b'link', os.fsencode(os.readlink(source))
        if not (source.parent / os.fsdecode(data)).resolve().is_relative_to(vendor.resolve()):
            raise SystemExit('Tracked source symlink escapes i2pd checkout')
        mode = 0
    elif source.is_file():
        kind, data = b'file', source.read_bytes()
        mode = source.stat().st_mode & 0o777
    elif not source.exists():
        kind, data = b'deleted', b''
        mode = 0
    else:
        raise SystemExit('Unsupported tracked source entry')
    digest.update(encoded + b'\0' + kind + b'\0' + str(mode).encode() + hashlib.sha256(data).digest())
    records.append((name, kind, data, mode))
for patch in patches:
    digest.update(patch.name.encode() + b'\0' + patch.read_bytes())
fingerprint = digest.hexdigest()
root = Path('/native/sources')
root.mkdir(parents=True, exist_ok=True)
output = root / ('i2pd-' + commit + '-' + fingerprint)
stamp = output / '.foxhole-source-snapshot'
content_manifest = output / '.foxhole-prepared-files.json'
def source_state(directory):
    result = {}
    for entry in directory.rglob('*'):
        relative = entry.relative_to(directory)
        if '.git' in relative.parts or relative.name.startswith('.foxhole-'):
            continue
        if entry.is_symlink():
            result[str(relative)] = ['link', os.readlink(entry)]
        elif entry.is_file():
            result[str(relative)] = ['file', hashlib.sha256(entry.read_bytes()).hexdigest(), entry.stat().st_mode & 0o777]
    return result
if stamp.exists():
    if stamp.read_text().strip() != fingerprint or not content_manifest.exists():
        raise SystemExit('Cached i2pd snapshot marker changed')
    if source_state(output) != json.loads(content_manifest.read_text()):
        raise SystemExit('Cached i2pd source changed after preparation')
else:
    staging = Path(tempfile.mkdtemp(prefix='i2pd-prepare-', dir=root))
    try:
        subprocess.run(['git', 'clone', '--shared', '--no-checkout', base, str(staging)], check=True, stdout=sys.stderr)
        subprocess.run(['git', '-C', str(staging), 'checkout', '--detach', commit], check=True, stdout=sys.stderr)
        for name, kind, data, mode in records:
            target = staging / name
            if target.is_symlink() or target.is_file():
                target.unlink()
            if kind == b'deleted':
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if kind == b'link':
                target.symlink_to(os.fsdecode(data))
            else:
                target.write_bytes(data)
                target.chmod(mode)
                if hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(data).digest():
                    raise SystemExit('Source snapshot copy mismatch')
        for patch in patches:
            reverse = subprocess.run(['git', '-C', str(staging), 'apply', '--reverse', '--check', str(patch)],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if reverse.returncode:
                subprocess.run(['git', '-C', str(staging), 'apply', '--check', str(patch)], check=True)
                subprocess.run(['git', '-C', str(staging), 'apply', str(patch)], check=True)
        actual = subprocess.check_output(['git', '-C', str(staging), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != commit:
            raise SystemExit('Prepared i2pd Git pin mismatch')
        (staging / '.foxhole-prepared-files.json').write_text(json.dumps(source_state(staging), sort_keys=True))
        stamp_data = fingerprint + '\n'
        (staging / '.foxhole-source-snapshot').write_text(stamp_data)
        staging.rename(output)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
print(output)
print('[docker-native] preserved host vendor snapshot=' + fingerprint, file=sys.stderr)
PY
)"
  export I2PD_SOURCE_DIR
  [[ "$(git -C "$I2PD_SOURCE_DIR" rev-parse HEAD)" == "$commit" ]] || die "i2pd clone pin mismatch"
  [[ "$(git -C "$I2PD_SOURCE_DIR" show -s --format=%ct HEAD)" == "$HOST_I2PD_EPOCH" ]] || die "i2pd source epoch mismatch"
  log "i2pd source=$I2PD_SOURCE_DIR commit=$commit epoch=$HOST_I2PD_EPOCH"
}

seed_archives() {
  local name hash
  mkdir -p "$i2pd_work_dir"
  for name in "openssl-$openssl_ver.tgz" "$boost_us.tar.bz2"; do
    case "$name" in openssl-*) hash="$openssl_sha256" ;; *) hash="$boost_sha256" ;; esac
    if [[ -f "/seed/i2pd-deps/$name" && ! -f "$i2pd_work_dir/$name" ]]; then
      verify_sha256 "/seed/i2pd-deps/$name" "$hash" "$name"
      cp "/seed/i2pd-deps/$name" "$i2pd_work_dir/$name"
    fi
  done
}

inside() {
  local mode="$1" expected_go="$2"
  [[ -f /.dockerenv && "$(uname -m)" == x86_64 ]] || die "native builds require the Linux AMD64 Docker image"
  mkdir -p /native/tmp
  [[ "$(awk -F'= ' '$1 == "Pkg.Revision " { print $2 }' /opt/android-ndk-r29/source.properties)" == 29.0.14206865 ]] || die "NDK pin mismatch"
  [[ "$($GO env GOVERSION)" == "$expected_go" && "$expected_go" == "$go_version" ]] || die "Go pin mismatch"
  [[ "$(cat /opt/foxhole/go/.foxhole-go-sha256)" == "$(go_tarball_sha256_for "$go_version-linux-amd64")" ]] || die "Go cached toolchain checksum marker mismatch"
  [[ "$($GO env GOHOSTOS)/$($GO env GOHOSTARCH)" == linux/amd64 ]] || die "Go host platform mismatch"
  mkdir -p /native/state
  exec 9>/native/state/native-build.lock
  flock -x 9
  HOST_FILE_LIST="/native/state/host-files-$$"
  cat > "$HOST_FILE_LIST"
  trap 'rm -f "$HOST_FILE_LIST"' EXIT
  seed_archives
  if [[ "$mode" != transports ]]; then prepare_i2pd_source; fi
  case "$mode" in
    check)
      "$GO" version
      /opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version
      command -v ninja
      ;;
    i2pd) bash "$repo_root/scripts/build-i2pd.sh" ;;
    transports)
      if ! native_offline; then bash "$repo_root/scripts/fetch-native-deps.sh" tor; fi
      bash "$repo_root/scripts/build-tor-transports.sh"
      ;;
    all)
      bash "$repo_root/scripts/build-i2pd.sh"
      if ! native_offline; then bash "$repo_root/scripts/fetch-native-deps.sh" tor; fi
      bash "$repo_root/scripts/build-tor-transports.sh"
      ;;
    *) die "invalid container action" ;;
  esac
}

if [[ "${1:-}" == __container ]]; then
  [[ $# == 3 ]] || die "invalid container invocation"
  inside "$2" "$3"
  exit
fi

[[ $# == 1 ]] || die "usage: scripts/docker-native.sh image|i2pd|transports|all"
action="$1"
case "$action" in image|i2pd|transports|all) ;; *) die "unknown action: $action" ;; esac
[[ "$go_version" =~ ^go[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid Go version pin"
go_sha="$(go_tarball_sha256_for "$go_version-linux-amd64")" || die "missing Linux AMD64 Go checksum pin"
[[ "$go_sha" =~ ^[0-9a-f]{64}$ ]] || die "invalid Go checksum pin"
cpus="${FOXHOLE_DOCKER_NATIVE_CPUS:-2}"
memory="${FOXHOLE_DOCKER_NATIVE_MEMORY:-2g}"
case "$cpus" in 1|2) ;; *) die "Docker native CPU limit must be 1 or 2" ;; esac
case "$memory" in 2g|3g) ;; *) die "Docker native RAM limit must be 2g or 3g" ;; esac
case "${FOXHOLE_NATIVE_OFFLINE:-0}" in 0|1) ;; *) die "FOXHOLE_NATIVE_OFFLINE must be 0 or 1" ;; esac
common_abis="${FOXHOLE_NATIVE_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
i2pd_abis="$(normalize_abis "${I2PD_ABIS:-$common_abis}")"
tor_abis="$(normalize_abis "${TOR_TRANSPORT_ABIS:-$common_abis}")"
api="${FOXHOLE_NATIVE_ANDROID_API:-26}"
[[ "$api" =~ ^[0-9]+$ && "$api" -ge 26 && "$api" -le 37 ]] || die "Android API must be 26..37"
image="foxhole/android-native-lab:1.97.1-ndk29-$go_version"
volume="foxhole-native-ndk29-$go_version"
check_path "$repo_root"
HOST_I2PD_HEAD="$(git -C "$repo_root/third_party/i2pd" rev-parse HEAD)"
HOST_I2PD_EPOCH="$(git -C "$repo_root/third_party/i2pd" show -s --format=%ct HEAD)"
[[ "$HOST_I2PD_HEAD" == "$(pin commit)" ]] || die "host i2pd checkout does not match its pin"
[[ "$HOST_I2PD_EPOCH" =~ ^[0-9]+$ ]] || die "invalid host i2pd commit epoch"

if [[ "$action" == image ]]; then
  docker image inspect foxhole/android-rust-lab:1.97.1-ndk29 >/dev/null || die "build the pinned Android Rust base image first"
  DOCKER_BUILDKIT=0 docker build --platform linux/amd64 --memory "$memory" \
    --cpu-period 100000 --cpu-quota "$((cpus * 100000))" \
    --build-arg "GO_VERSION=$go_version" --build-arg "GO_SHA256=$go_sha" \
    -t "$image" - < "$repo_root/docker/Dockerfile.native"
fi
docker image inspect "$image" >/dev/null || die "run scripts/docker-native.sh image first"
[[ "$(docker image inspect "$image" --format '{{index .Config.Labels "org.foxhole.native.go.sha256"}}')" == "$go_sha" ]] || die "Docker image Go checksum pin mismatch"

mounts=(
  --mount "type=bind,src=$repo_root,dst=/repo,readonly"
  --mount "type=volume,src=$volume,dst=/native"
  --mount "type=volume,src=foxhole-native-ndk29.0.14206865-amd64,dst=/opt/android-ndk-r29,readonly"
  --mount "type=volume,src=foxhole-native-$go_version-amd64,dst=/opt/foxhole/go,readonly"
)
if [[ -d "$native_deps_dir" ]]; then
  check_path "$native_deps_dir"
  mounts+=(--mount "type=bind,src=$native_deps_dir,dst=/seed,readonly")
fi
mode="$action"
if [[ "$action" == image ]]; then mode=check; fi
if [[ "$mode" == i2pd || "$mode" == all ]]; then
  jni="$repo_root/app/src/main/jniLibs"
  certificates="$repo_root/app/src/main/assets/i2pd/certificates"
  check_output "$jni"; check_output "$certificates"
  mkdir -p "$jni" "$certificates"
  mounts+=(--mount "type=bind,src=$jni,dst=/out/jni")
  mounts+=(--mount "type=bind,src=$certificates,dst=/repo/app/src/main/assets/i2pd/certificates")
fi
if [[ "$mode" == transports || "$mode" == all ]]; then
  for abi in $tor_abis; do
    output="$repo_root/app/src/main/assets/tor/$abi/tor/pluggable_transports"
    check_output "$output"
    mkdir -p "$output"
    mounts+=(--mount "type=bind,src=$output,dst=/repo/app/src/main/assets/tor/$abi/tor/pluggable_transports")
  done
fi

log "image=$image mode=$mode CPU=$cpus RAM=$memory cache=$volume"
git -C "$repo_root/third_party/i2pd" ls-files -z | \
  docker run --rm -i --platform linux/amd64 --cpus "$cpus" --memory "$memory" --pids-limit 512 \
    --cap-drop ALL --security-opt no-new-privileges \
    "${mounts[@]}" \
    -e "HOST_I2PD_HEAD=$HOST_I2PD_HEAD" -e "HOST_I2PD_EPOCH=$HOST_I2PD_EPOCH" \
    -e "I2PD_ABIS=$i2pd_abis" -e "TOR_TRANSPORT_ABIS=$tor_abis" \
    -e "I2PD_ANDROID_API=$api" -e "TOR_TRANSPORT_API=$api" \
    -e "I2PD_BUILD_JOBS=$cpus" -e "GOMAXPROCS=$cpus" -e "GOFLAGS=-p=$cpus" \
    -e "FOXHOLE_NATIVE_OFFLINE=${FOXHOLE_NATIVE_OFFLINE:-0}" -e I2PD_OUTPUT_ROOT=/out/jni \
    -e GOWORK=off -e GOTOOLCHAIN=local \
    "$image" bash /repo/scripts/docker-native.sh __container "$mode" "$go_version"
