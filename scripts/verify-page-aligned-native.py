#!/usr/bin/env python3
"""Reject unbound native overrides and verify the bytes selected by AGP."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_component(repo, native, merged, abis, apk_directory, component):
    config = json.loads((repo / 'config/native/page-aligned-native.json').read_text())[component]
    provenance = native / 'provenance' / component
    inputs = provenance / 'producer-inputs.json'
    manifest = json.loads(inputs.read_text())
    receipt = json.loads((provenance / 'producer-receipt.json').read_text())
    expected_status = ('actual-graphics1.0.1-page-aligned-native-produced' if component == 'graphics'
                       else 'actual-sodium1.0.20-patched-page-aligned-native-produced')
    if (receipt.get('schema') != 1 or receipt.get('exitCode') != 0 or receipt.get('sourceMutations') != [] or
            receipt.get('status') != expected_status or
            receipt.get('producerInputManifestSha256') != sha(inputs) or
            receipt.get('ndk', {}).get('version') != config['ndk']):
        raise ValueError('Native producer/source/toolchain receipt is not bound')
    if component == 'graphics':
        if receipt.get('graphicsReleaseCommit') != config['commit']:
            raise ValueError('Graphics release commit differs')
        expected_sources = {name: item['sha256'] for name, item in config['sourceFiles'].items()}
        actual_sources = {name: item['sha256'] for name, item in receipt['graphicsOriginalSource'].items()}
        if actual_sources != expected_sources:
            raise ValueError('Derived graphics source differs from exact release source')
        original_inputs = {'graphics-path-1.0.1.aar': config['originalAar']['sha256'],
                           'graphics-path-1.0.1-sources.jar': config['originalSourceJar']['sha256'],
                           'graphics-path-1.0.1-native.tar': config['sourceArchive']['sha256']}
        library = 'libandroidx.graphics.path.so'
    else:
        inventory_path = repo / config['sourceInventory']['path']
        if sha(inventory_path) != config['sourceInventory']['sha256']:
            raise ValueError('Original/effective sodium inventory differs')
        inventory = json.loads(inventory_path.read_text())
        if receipt.get('upstreamSecurityFixCommit') != config['upstreamSecurityFixCommit'] or \
                receipt['originalSodiumSource'] != inventory['originalSourceInventory']:
            raise ValueError('Original or patched sodium source differs')
        expected_effective = inventory['originalSourceInventory'] | inventory['compiledSourcePatchFiles']
        actual_effective = receipt['effectivePatchedSodiumSource']
        if actual_effective != expected_effective and actual_effective != inventory['actualReferenceEffectiveInventory']:
            raise ValueError('Unexpected sodium effective source or patch artifacts')
        counts = receipt.get('upstreamGreenTestCounts', {})
        if (receipt.get('upstreamRedRegressionExpectedExit') != 2 or counts.get('TOTAL') != 80 or
                counts.get('PASS') != 80 or any(counts.get(name) != 0 for name in
                                               ['SKIP', 'XFAIL', 'FAIL', 'XPASS', 'ERROR'])):
            raise ValueError('Sodium upstream/security regression was incomplete')
        original_inputs = {'lazysodium-android-5.2.0.aar': config['originalAar']['sha256'],
                           'libsodium-1.0.20.tar.gz': config['sourceArchive']['sha256'],
                           'libsodium-CVE-2025-69277-upstream.patch': config['patch']['sha256']}
        library = 'libsodium.so'
    if any(manifest['files'].get(name) != expected for name, expected in original_inputs.items()):
        raise ValueError('Original Maven/source archive binding differs')
    expected_recipe = {'config/native/page-aligned-native.json', 'scripts/page_aligned_native_common.py',
                       'scripts/build-page-aligned-native.sh', 'scripts/prepare-page-aligned-native-inputs.py',
                       'scripts/verify-native-pages.py', 'scripts/verify-native-host-paths.sh',
                       'scripts/build-' + component + '-page-aligned.py'}
    if component == 'sodium':
        expected_recipe |= {config['patch']['path'], config['sourceInventory']['path']}
    recipe = manifest.get('repoInputHashes')
    if not isinstance(recipe, dict) or set(recipe) != expected_recipe:
        raise ValueError('Native producer recipe key set is incomplete or foreign')
    root = repo.resolve()
    for name, expected in recipe.items():
        relative = Path(name)
        path = repo / relative
        if relative.is_absolute() or '..' in relative.parts or path.is_symlink() or \
                not path.resolve().is_relative_to(root):
            raise ValueError('Native recipe input path escaped its repository')
        parent = path.parent
        while parent.resolve() != root:
            if parent.is_symlink():
                raise ValueError('Native recipe parent is a link')
            parent = parent.parent
        if sha(repo / name) != expected:
            raise ValueError('Native producer recipe differs: ' + name)
    rows = {item['abi']: item for item in receipt['libraries']}
    supported = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
    if set(rows) != supported or len(receipt['libraries']) != 4 or not set(abis).issubset(supported):
        raise ValueError('Native producer ABI inventory differs')
    spec = importlib.util.spec_from_file_location('pages', repo / 'scripts/verify-native-pages.py')
    pages = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pages)
    for abi in abis:
        row = rows[abi]
        if row['path'] != 'jniLibs/' + abi + '/' + library or row['api'] != config['api']:
            raise ValueError('Native producer path or API differs')
        if component == 'graphics' and (row.get('originalExportSymbols') != ['JNI_OnLoad@@LIBANDROIDX.GRAPHICS.PATH'] or
                                        row.get('derivedExportSymbols') != row['originalExportSymbols']):
            raise ValueError('Versioned JNI export surface differs')
        if component == 'sodium' and (not row.get('originalExportSymbols') or
                                     not set(row['originalExportSymbols']).issubset(row.get('derivedExportSymbols', []))):
            raise ValueError('Original sodium native API symbol is missing')
        expected = native / abi / library
        if expected.is_symlink() or sha(expected) != row['sha256'] or expected.stat().st_size != row['bytes']:
            raise ValueError('Derived native output differs from the actual producer')
        pages.verify(abi, expected)
        if merged is not None:
            selected = merged / 'lib' / abi / expected.name
            if selected.is_symlink() or sha(selected) != row['sha256'] or selected.stat().st_size != row['bytes']:
                raise ValueError('AGP selected original or foreign native bytes: ' + abi)
            pages.verify(abi, selected)
    if apk_directory is not None:
        apks = sorted(apk_directory.glob('*.apk'))
        if not apks:
            raise ValueError('Built APK output is missing')
        found = set()
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                names = archive.namelist()
                if len(names) != len(set(names)):
                    raise ValueError('Built APK contains duplicate entries')
                for abi in abis:
                    name = 'lib/' + abi + '/' + library
                    if name not in archive.namelist():
                        continue
                    data = archive.read(name)
                    if hashlib.sha256(data).hexdigest() != rows[abi]['sha256'] or len(data) != rows[abi]['bytes']:
                        raise ValueError('APK contains original, foreign or unexpectedly transformed native bytes')
                    found.add(abi)
        if found != set(abis):
            raise ValueError('Built APK native ABI inventory is incomplete')


def verify(repo, native, merged, abis, apk_directory=None):
    config = json.loads((repo / 'config/native/page-aligned-native.json').read_text())
    for component in ('graphics', 'sodium'):
        if component in config:
            verify_component(repo, native, merged, abis, apk_directory, component)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--merged', type=Path)
    parser.add_argument('--apk-directory', type=Path)
    parser.add_argument('--abis', nargs='+', required=True,
                        choices=['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'])
    args = parser.parse_args()
    verify(args.repo, args.native, args.merged, args.abis, args.apk_directory)


if __name__ == '__main__':
    main()
