#!/usr/bin/env python3
"""Bundle separately labeled modified-native provenance and original notices."""
import argparse
import copy
import importlib.util
import json
import math
from pathlib import Path, PurePosixPath
import re
import shutil

COMMON = {'schema', 'exitCode', 'status', 'elapsedSeconds', 'producerInputManifestSha256',
          'sourceMutations', 'ndk', 'steps', 'libraries', 'scope'}
COMPONENT = {
    'graphics': {'graphicsReleaseCommit', 'graphicsOriginalSource', 'originalMavenJavaUnchanged'},
    'sodium': {'originalSodiumSource', 'effectivePatchedSodiumSource', 'upstreamSecurityFixCommit',
               'upstreamRedRegressionExpectedExit', 'upstreamGreenSuiteExit', 'upstreamGreenTestCounts'},
}
MARKERS = {'kind', 'projectionVersion', 'rawProducerSchema', 'rawProducerReceiptPackaged',
           'excludedOperationalFields', 'commandPathRoles'}
KIND = 'canonical-page-native-producer-evidence'
EXCLUDED = ['elapsedSeconds', 'steps[].logSha256']
ROLES = ['$PRODUCER_OUTPUT', '$ANDROID_NDK']
ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def path_root(value, suffix):
    require(isinstance(value, str) and value.endswith(suffix), 'Unknown producer command path')
    root = value[:-len(suffix)]
    path = PurePosixPath(root)
    require(path.is_absolute() and str(path) == root and root != '/' and
            '..' not in path.parts and not any(c.isspace() for c in root), 'Invalid producer path root')
    return root


def producer_roots(component, steps):
    # These shapes come from the fully verified, hash-bound producer recipe, not the consumer checkout.
    ndks = set()
    for step in steps:
        if step['argv'][0].endswith('/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'):
            ndks.add(path_root(step['argv'][0], '/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'))
    require(len(ndks) == 1, 'One bound producer NDK command root is required')
    if component == 'graphics':
        first = steps[0]['argv']
        require(len(first) > 5 and first[:2] == ['cmake', '-S'] and first[3] == '-B',
                'Unknown graphics configure command')
        output = path_root(first[4], '/build-arm64-v8a')
        require(first[2] == output + '/original-graphics', 'Graphics source/build command roots differ')
        roots = [(output, '$PRODUCER_OUTPUT'), (next(iter(ndks)), '$ANDROID_NDK')]
    else:
        require(len(steps) > 1 and steps[0]['argv'][:1] == ['patch'] and '-i' in steps[0]['argv'],
                'Unknown sodium patch command')
        first = steps[0]['argv']; require(first.count('-i') == 1, 'Ambiguous sodium patch path')
        inputs = path_root(first[first.index('-i') + 1], '/libsodium-CVE-2025-69277-upstream.patch')
        output = path_root(steps[1]['argv'][0], '/host-red-source/configure')
        roots = [(output, '$PRODUCER_OUTPUT'), (next(iter(ndks)), '$ANDROID_NDK'),
                 (inputs, '$PRODUCER_INPUTS')]
    require(len({root for root, _ in roots}) == len(roots), 'Ambiguous producer path roles')
    return sorted(roots, key=lambda item: len(item[0]), reverse=True)


def normalized_arg(value, roots):
    for root, role in roots:
        value = re.sub(re.escape(root) + r'(?=/|[=\s,]|$)', lambda _: role, value)
    # Stable compiler remap destinations describe the output contract; all builder roots must be named.
    for path in re.findall(r'(?<![A-Za-z0-9_$}])/(?:[^\s=,;]+)', value):
        require(path in ('/usr/src/foxhole-native', '/usr/src/android-ndk'),
                'Unrecognized absolute producer command path')
    return value


def validate_steps(component, steps, projected=False):
    require(isinstance(steps, list) and 0 < len(steps) <= 128, 'Invalid producer command inventory')
    for step in steps:
        require(isinstance(step, dict) and set(step) ==
                ({'argv', 'exitCode'} if projected else {'argv', 'exitCode', 'logSha256'}),
                'Unknown producer command fields')
        argv = step['argv']
        require(isinstance(argv, list) and 0 < len(argv) <= 128 and
                all(isinstance(arg, str) and 0 < len(arg) <= 65536 and '\0' not in arg for arg in argv),
                'Invalid producer command arguments')
        expected = 2 if component == 'sodium' and argv == ['make', '-j1', 'check', 'TESTS=core_ed25519'] else 0
        require(type(step['exitCode']) is int and step['exitCode'] == expected,
                'Producer command exit differs from its security regression contract')
        if not projected:
            require(isinstance(step['logSha256'], str) and re.fullmatch('[0-9a-f]{64}', step['logSha256']),
                    'Invalid original producer log hash')
    if component == 'sodium':
        require(sum(step['argv'] == ['make', '-j1', 'check', 'TESTS=core_ed25519'] for step in steps) == 1,
                'One original sodium red regression command is required')


def validate_projection(component, receipt):
    require(component in COMPONENT and isinstance(receipt, dict) and
            set(receipt) == (COMMON | COMPONENT[component] | MARKERS) - {'elapsedSeconds'},
            'Unknown canonical producer evidence fields')
    require(receipt['schema'] == 2 and type(receipt['schema']) is int and receipt['kind'] == KIND and
            type(receipt['projectionVersion']) is int and receipt['projectionVersion'] == 1 and
            type(receipt['rawProducerSchema']) is int and receipt['rawProducerSchema'] == 1 and
            receipt['rawProducerReceiptPackaged'] is False and receipt['excludedOperationalFields'] == EXCLUDED and
            receipt['commandPathRoles'] == ROLES + (['$PRODUCER_INPUTS'] if component == 'sodium' else []),
            'Canonical producer evidence identity differs')
    require(type(receipt['exitCode']) is int and receipt['exitCode'] == 0 and
            receipt['sourceMutations'] == [] and receipt['status'] ==
            ('actual-graphics1.0.1-page-aligned-native-produced' if component == 'graphics' else
             'actual-sodium1.0.20-patched-page-aligned-native-produced'),
            'Canonical producer status differs')
    validate_steps(component, receipt['steps'], projected=True)
    for step in receipt['steps']:
        for arg in step['argv']:
            require(normalized_arg(arg, []) == arg, 'Noncanonical producer command path')
            require(all(role in receipt['commandPathRoles'] for role in
                        re.findall(r'\$[A-Za-z_][A-Za-z0-9_]*', arg)), 'Unknown producer command path role')
    return receipt


def canonical_evidence(component, receipt):
    require(component in COMPONENT and isinstance(receipt, dict) and
            set(receipt) == COMMON | COMPONENT[component], 'Unknown original producer receipt fields')
    require(type(receipt['schema']) is int and receipt['schema'] == 1 and
            type(receipt['exitCode']) is int and receipt['exitCode'] == 0,
            'An actual successful original producer receipt is required')
    duration = receipt['elapsedSeconds']
    require(type(duration) in (int, float) and math.isfinite(duration) and duration >= 0,
            'Invalid original producer duration')
    validate_steps(component, receipt['steps'])
    roots = producer_roots(component, receipt['steps'])
    value = copy.deepcopy({key: item for key, item in receipt.items() if key != 'elapsedSeconds'})
    value.update(schema=2, kind=KIND, projectionVersion=1, rawProducerSchema=1,
                 rawProducerReceiptPackaged=False, excludedOperationalFields=list(EXCLUDED),
                 commandPathRoles=ROLES + (['$PRODUCER_INPUTS'] if component == 'sodium' else []),
                 steps=[{'argv': [normalized_arg(arg, roots) for arg in step['argv']],
                         'exitCode': step['exitCode']} for step in receipt['steps']])
    return validate_projection(component, value)


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abis', nargs='+', required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('binding', args.repo / 'scripts/verify-page-aligned-native.py')
    binding = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(binding)
    binding.verify(args.repo, args.native, None, args.abis)
    config = json.loads((args.repo / 'config/native/page-aligned-native.json').read_text())
    target = args.output / 'derived-native'
    target.mkdir()
    shutil.copyfile(args.repo / 'config/native/page-aligned-native.json', target / 'PINNED-SOURCES.json')
    for component in ['graphics', 'sodium']:
        if component not in config:
            continue
        source = args.native / 'provenance' / component
        component_target = target / component
        component_target.mkdir()
        raw = json.loads((source / 'producer-receipt.json').read_text())
        (component_target / 'producer-evidence.json').write_bytes(encoded(canonical_evidence(component, raw)))
        shutil.copyfile(source / 'producer-inputs.json', component_target / 'producer-inputs.json')
        shutil.copytree(source / 'license-inputs', component_target / 'publisher-toolchain-notices')
        if component == 'sodium':
            for key in ['patch', 'sourceInventory']:
                path = args.repo / config['sodium'][key]['path']
                shutil.copyfile(path, component_target / path.name)
    (target / 'README.txt').write_text(
        'Original Maven AAR and Java remain separate verified inputs. The native libraries here '
        'are rebuilt from exact publisher sources with NDK29 and 16KiB linker flags. '
        'Sodium1.0.20 includes the explicitly recorded upstream CVE-2025-69277 backport. '
        'Canonical producer evidence preserves source, tools, outputs, commands and test exit codes. '
        'Known producer input/output/NDK command roots use logical path roles. Original timings '
        'and log hashes remain in separate raw producer receipts and are not packaged. This does not imply '
        'Android runtime, encrypted database migration, or release qualification.\n'
    )


if __name__ == '__main__':
    main()
