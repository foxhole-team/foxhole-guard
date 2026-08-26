#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
manifest="$repo_root/third_party/flags/app-assets.sha256"
output_dir="$repo_root/app/src/main/assets/flags"

flag_icons_commit="086f7e97d657358203916dbe84f61c2bccaa81eb"
flag_icons_archive_sha256="eb5b814c794cda735155e2874e288ad9302b6f7a5728e5e50d3ef0333b6c20f4"
resvg_version="2.6.2"
resvg_archive_sha256="ff51acbb5ee0074601b75c3bea9226a18d346752af787f6d2d3adcdd98493d71"

for required_command in curl node tar; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "Missing required command: $required_command" >&2
        exit 1
    fi
done

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

download_and_verify() {
    local url=$1
    local destination=$2
    local expected_sha256=$3
    local label=$4

    curl --fail --location --silent --show-error "$url" --output "$destination"
    local actual_sha256
    actual_sha256=$(sha256_file "$destination")
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        echo "$label archive SHA-256 mismatch: expected $expected_sha256, got $actual_sha256" >&2
        exit 1
    fi
}

flag_work_dir=$(mktemp -d "${TMPDIR:-/tmp}/foxhole-app-flags.XXXXXX")
cleanup() {
    rm -rf -- "$flag_work_dir"
}
trap cleanup EXIT HUP INT TERM

source_archive="$flag_work_dir/flag-icons.tar.gz"
renderer_archive="$flag_work_dir/resvg-wasm.tgz"
source_dir="$flag_work_dir/flag-icons"
renderer_dir="$flag_work_dir/resvg-wasm"
staging_dir="$flag_work_dir/output"
mkdir -p "$source_dir" "$renderer_dir" "$staging_dir"

download_and_verify \
    "https://codeload.github.com/lipis/flag-icons/tar.gz/$flag_icons_commit" \
    "$source_archive" \
    "$flag_icons_archive_sha256" \
    "flag-icons"
download_and_verify \
    "https://registry.npmjs.org/@resvg/resvg-wasm/-/resvg-wasm-$resvg_version.tgz" \
    "$renderer_archive" \
    "$resvg_archive_sha256" \
    "resvg-wasm"

tar -xzf "$source_archive" -C "$source_dir" --strip-components=1
tar -xzf "$renderer_archive" -C "$renderer_dir" --strip-components=1

node - "$source_dir" "$renderer_dir" "$manifest" "$output_dir" "$staging_dir" <<'NODE'
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')

const [sourceRoot, rendererRoot, manifestPath, outputDir, stagingDir] = process.argv.slice(2)
const { initWasm, Resvg } = require(rendererRoot)

function fail(message) {
  throw new Error(message)
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex')
}

function sortedPngNames(directory) {
  return fs.readdirSync(directory).filter((name) => name.endsWith('.png')).sort()
}

async function main() {
  const entries = fs
    .readFileSync(manifestPath, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => {
      const match = /^([0-9a-f]{64})  ([a-z0-9-]+\.png)$/.exec(line)
      if (!match) fail(`Invalid manifest line: ${line}`)
      return { sha256: match[1], name: match[2] }
    })

  if (entries.length !== 270) fail(`Expected 270 manifest entries, found ${entries.length}`)
  if (new Set(entries.map((entry) => entry.name)).size !== entries.length) {
    fail('Manifest contains duplicate names')
  }

  const expectedNames = entries.map((entry) => entry.name).sort()
  const existingNames = sortedPngNames(outputDir)
  if (JSON.stringify(existingNames) !== JSON.stringify(expectedNames)) {
    fail('Application flag inventory differs from the pinned manifest')
  }

  const sourceFlagsDir = path.join(sourceRoot, 'flags', '4x3')
  const selectedSourceNames = new Set(expectedNames.map((name) => name.replace(/\.png$/, '.svg')))
  const upstreamNames = fs.readdirSync(sourceFlagsDir).filter((name) => name.endsWith('.svg')).sort()
  const unshippedSources = upstreamNames.filter((name) => !selectedSourceNames.has(name))
  if (upstreamNames.length !== 271 || JSON.stringify(unshippedSources) !== JSON.stringify(['sh-ac.svg'])) {
    fail(`Unexpected upstream inventory: ${upstreamNames.length} SVGs, unshipped ${unshippedSources.join(', ')}`)
  }

  await initWasm(fs.readFileSync(path.join(rendererRoot, 'index_bg.wasm')))

  for (const entry of entries) {
    const sourceName = entry.name.replace(/\.png$/, '.svg')
    const sourcePath = path.join(sourceFlagsDir, sourceName)
    if (!fs.existsSync(sourcePath)) fail(`Missing pinned source: ${sourceName}`)

    const renderer = new Resvg(fs.readFileSync(sourcePath), {
      fitTo: { mode: 'width', value: 256 },
    })
    const image = renderer.render()
    if (image.width !== 256 || image.height !== 192) {
      fail(`${entry.name}: expected 256x192, got ${image.width}x${image.height}`)
    }
    const png = Buffer.from(image.asPng())
    image.free()
    renderer.free()

    const actualSha256 = sha256(png)
    if (actualSha256 !== entry.sha256) {
      fail(`${entry.name}: expected SHA-256 ${entry.sha256}, got ${actualSha256}`)
    }
    fs.writeFileSync(path.join(stagingDir, entry.name), png)
  }

  for (const entry of entries) {
    const destination = path.join(outputDir, entry.name)
    if (sha256(fs.readFileSync(destination)) !== entry.sha256) {
      fs.copyFileSync(path.join(stagingDir, entry.name), destination)
    }
  }
  console.log(`Verified and regenerated ${entries.length} flag assets from the pinned upstream source`)
}

main().catch((error) => {
  console.error(error.message)
  process.exitCode = 1
})
NODE
