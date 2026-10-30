#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output=${1:?output directory}
work=${2:?owned build work directory}
ndk=${3:?exact NDK root}
python3 - "$repo" "$output" "$work" <<'PY'
from pathlib import Path
import sys
repo, output, work = [Path(value).resolve() for value in sys.argv[1:]]
if output != repo / 'app/build/generated/pageAlignedNative' or work != repo / 'app/build/pageAlignedNativeWork':
    raise ValueError('Native output/work must be the exact owned generated directories')
PY
mkdir -p "$work"
components=$(python3 - "$repo/config/native/page-aligned-native.json" <<'PY'
import json
import sys
with open(sys.argv[1]) as stream:
    config = json.load(stream)
print(" ".join(component for component in ("graphics", "sodium") if component in config))
PY
)
for component in $components; do
operation=$(mktemp -d "$work/operation-XXXXXXXX")
inputs="$operation/inputs"
produced="$operation/produced"
mkdir "$produced"
offline=()
if [[ "${FOXHOLE_NATIVE_OFFLINE:-0}" == 1 ]]; then offline=(--offline); fi
python3 "$repo/scripts/prepare-page-aligned-native-inputs.py" --repo "$repo" --destination "$inputs" \
  --cache "$work/publisher-cache" --component "$component" "${offline[@]}"
python3 "$inputs/build-$component-page-aligned.py" --inputs "$inputs" --ndk "$ndk" \
  --source-consumer "$inputs/consumer" --output "$produced"
python3 - "$repo" "$inputs" "$produced" "$output" "$component" <<'PY'
from pathlib import Path
import hashlib,json,shutil,sys
repo,inputs,produced,output=map(Path,sys.argv[1:5])
component=sys.argv[5]
manifest=json.loads((inputs/'producer-inputs.json').read_text())
for name,expected in manifest['repoInputHashes'].items():
    with (repo/name).open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=expected:
            raise ValueError('Native producer repository input changed: '+name)
receipt=json.loads((produced/'producer-receipt.json').read_text())
if receipt['exitCode']!=0 or len(receipt['libraries'])!=4 or receipt['sourceMutations']:
    raise ValueError('Actual native producer was incomplete')
output.mkdir(parents=True,exist_ok=True)
for row in receipt['libraries']:
    original=produced/row['path']
    with original.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=row['sha256']:
            raise ValueError('Actual producer native output differs')
    target=output/row['path'].removeprefix('jniLibs/')
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(original,target)
provenance=output/'provenance'/component
provenance.mkdir(parents=True,exist_ok=True)
shutil.copyfile(produced/'producer-receipt.json',provenance/'producer-receipt.json')
shutil.copyfile(inputs/'producer-inputs.json',provenance/'producer-inputs.json')
shutil.copytree(produced/'license-inputs',provenance/'license-inputs',dirs_exist_ok=True)
PY
done
