#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($# > 0)); then
  libraries=("$@")
else
  libraries=("$repo_root"/app/src/main/jniLibs/*/libi2pd.so)
fi

command -v strings >/dev/null 2>&1 || {
  echo "strings is required to verify native host-path hygiene" >&2
  exit 1
}

host_path_pattern='/Users/|/home/|/private/tmp/|/private/var/folders/|/builds/|/workspace/|/workspaces/|/__w/|/tmp/[^[:space:]]|foxhole-native-deps|foxhole_guard(_dev|_final_build)?'
failed=0

for library in "${libraries[@]}"; do
  [[ -f "$library" ]] || {
    echo "native library is missing: $library" >&2
    failed=1
    continue
  }
  leaks="$(LC_ALL=C strings -a "$library" | grep -E "$host_path_pattern" || true)"
  if [[ -n "$leaks" ]]; then
    echo "native library embeds host-specific paths: $library" >&2
    printf '%s\n' "$leaks" | sed -n '1,20p' >&2
    abi="$(basename "$(dirname "$library")")"
    printf "remediation: I2PD_ABIS='%s' scripts/build-i2pd.sh\n" "$abi" >&2
    failed=1
  fi
done

((failed == 0)) || exit 1
printf '[native-host-paths] verified %d librar%s\n' \
  "${#libraries[@]}" "$([[ ${#libraries[@]} -eq 1 ]] && echo y || echo ies)"
