#!/usr/bin/env python3
"""Produce patched libsodium1.0.20 from its authenticated point source."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from page_aligned_native_common import extract, run, sha, symbol_set, tree


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--source-consumer', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    started = time.time()
    inputs, out = args.inputs, args.output
    expected = json.loads((inputs / 'producer-inputs.json').read_text())
    for name, digest in expected['files'].items():
        if sha(inputs / name) != digest:
            raise ValueError('Frozen input differs: ' + name)
    properties = (args.ndk / 'source.properties').read_text()
    if 'Pkg.Revision = 29.0.14206865' not in properties:
        raise ValueError('Expected exact NDK29 toolchain')
    tools = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    tool_names = ['clang', 'clang++', 'ld.lld', 'llvm-ar', 'llvm-ranlib', 'llvm-strip', 'llvm-nm']
    tool_hashes = {name: sha(tools / name) for name in tool_names}
    original_sodium = extract(inputs / 'libsodium-1.0.20.tar.gz', out / 'raw-sodium', 'libsodium-1.0.20')
    sodium_before = tree(original_sodium)
    prepared = out / 'prepared-sodium'
    shutil.copytree(original_sodium, prepared)
    steps = []
    steps.append(run(['patch', '--batch', '--forward', '--no-backup-if-mismatch', '--fuzz=0', '-p1', '-i',
                      str(inputs / 'libsodium-CVE-2025-69277-upstream.patch')], prepared,
                     out / 'security-patch.log', limit=30))
    prepared_state = tree(prepared)
    changed = {name for name in sodium_before if prepared_state[name] != sodium_before[name]}
    if changed != set(expected['sodiumPatchedFiles']):
        raise ValueError('Unexpected modified source outside the approved upstream backport')
    for name, digest in expected['sodiumPatchedFiles'].items():
        if prepared_state[name]['sha256'] != digest:
            raise ValueError('Effective patched source differs')
    env = os.environ.copy()
    for name in ['CC', 'CXX', 'CPP', 'AR', 'RANLIB', 'LD', 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS',
                 'LDFLAGS', 'LIBS', 'CPATH', 'LIBRARY_PATH', 'C_INCLUDE_PATH',
                 'CPLUS_INCLUDE_PATH', 'PKG_CONFIG_PATH', 'MAKEFLAGS']:
        env.pop(name, None)
    env['SOURCE_DATE_EPOCH'] = '1716639743'
    env['ZERO_AR_DATE'] = '1'
    env['TMPDIR'] = str(out / 'tmp')
    (out / 'tmp').mkdir()

    # Run the new upstream assertion against the unchanged vulnerable source first.
    red_source = out / 'host-red-source'
    shutil.copytree(original_sodium, red_source)
    shutil.copyfile(prepared / 'test/default/core_ed25519.c', red_source / 'test/default/core_ed25519.c')
    host_red, host_green = out / 'host-red', out / 'host-green'
    host_red.mkdir()
    host_green.mkdir()
    for label, source, work in [('red', red_source, host_red), ('green', prepared, host_green)]:
        steps.append(run([str(source / 'configure'), '--disable-shared', '--enable-static',
                          '--disable-dependency-tracking'], work, out / f'host-{label}-configure.log', env))
        steps.append(run(['make', '-j4'], work, out / f'host-{label}-build.log', env, limit=600))
    steps.append(run(['make', '-j1', 'check', 'TESTS=core_ed25519'], host_red / 'test/default',
                     out / 'host-red-regression.log', env, expected=2, limit=120))
    red_log = (host_red / 'test/default/core_ed25519.log').read_text()
    if 'crypto_core_ed25519_is_valid_point(p) == 0' not in red_log:
        raise ValueError('Red fixture failed outside the actual upstream CVE assertion')
    steps.append(run(['make', '-j4', 'check'], host_green, out / 'host-green-upstream.log', env, limit=600))
    test_summary = (host_green / 'test/default/test-suite.log').read_text()
    counts = {name: int(value) for name, value in
              re.findall(r'^# (TOTAL|PASS|SKIP|XFAIL|FAIL|XPASS|ERROR):\s*(\d+)', test_summary, re.M)}
    if not counts.get('TOTAL') or counts.get('PASS') != counts['TOTAL'] or any(
            counts.get(name, -1) != 0 for name in ['SKIP', 'XFAIL', 'FAIL', 'XPASS', 'ERROR']):
        raise ValueError('Upstream sodium suite is incomplete or skipped')

    pages_path = args.source_consumer / 'scripts/verify-native-pages.py'
    spec = importlib.util.spec_from_file_location('pages', pages_path)
    pages = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pages)
    libraries = []
    layouts = [('arm64-v8a', 'aarch64-linux-android'), ('armeabi-v7a', 'armv7a-linux-androideabi'),
               ('x86', 'i686-linux-android'), ('x86_64', 'x86_64-linux-android')]
    for abi, compiler in layouts:
        directory = out / 'jniLibs' / abi
        directory.mkdir(parents=True)
        sodium_work = out / ('sodium-' + abi)
        sodium_work.mkdir()
        prefix_maps = ' '.join(f'-f{kind}-prefix-map={old}={new}' for old, new in
                              [(out, '/usr/src/foxhole-native'), (args.ndk, '/usr/src/android-ndk')]
                              for kind in ['file', 'macro', 'debug'])
        linker_flags = '-Wl,-z,max-page-size=16384,-z,common-page-size=16384'
        cross = env.copy()
        cross.update({'CC': str(tools / (compiler + '26-clang')),
                      'CXX': str(tools / (compiler + '26-clang++')),
                      'AR': str(tools / 'llvm-ar'), 'RANLIB': str(tools / 'llvm-ranlib'),
                      'CFLAGS': '-Os -g0 -fPIC ' + prefix_maps, 'LDFLAGS': linker_flags})
        host = 'arm-linux-androideabi' if abi == 'armeabi-v7a' else compiler
        steps.append(run([str(prepared / 'configure'), '--host=' + host, '--disable-soname-versions',
                          '--disable-pie', '--disable-dependency-tracking', '--enable-shared', '--disable-static'],
                         sodium_work, out / (abi + '-sodium-configure.log'), cross))
        steps.append(run(['make', '-j4'], sodium_work, out / (abi + '-sodium-build.log'), cross, limit=600))
        for library, original in [('libsodium.so', sodium_work / 'src/libsodium/.libs/libsodium.so')]:
            target = directory / library
            steps.append(run([str(tools / 'llvm-strip'), '--strip-unneeded', '-o', str(target), str(original)],
                             out, out / (abi + '-' + library + '-strip.log'), env, limit=30))
            page = pages.verify(abi, target)
            old = inputs / 'original-native' / abi / library
            symbols = symbol_set(tools / 'llvm-nm', target)
            original_symbols = symbol_set(tools / 'llvm-nm', old)
            if not set(original_symbols).issubset(symbols):
                raise ValueError('Published native API symbol missing: ' + abi + '/' + library)
            hostpaths = subprocess.run(['bash', str(args.source_consumer / 'scripts/verify-native-host-paths.sh'),
                                        str(target)], capture_output=True, text=True, timeout=30)
            if hostpaths.returncode:
                raise ValueError('Host path escaped into a native output')
            libraries.append({'abi': abi, 'path': str(target.relative_to(out)), 'bytes': target.stat().st_size,
                              'sha256': sha(target), 'api': 26, 'pageGate': page,
                              'originalMavenNativeSha256': sha(old), 'originalExportSymbols': original_symbols,
                              'derivedExportSymbols': symbols})
    if tree(original_sodium) != sodium_before or tree(prepared) != prepared_state:
        raise ValueError('Original/effective compile sources changed during production')
    license_dir = out / 'license-inputs'
    license_dir.mkdir()
    shutil.copyfile(original_sodium / 'LICENSE', license_dir / 'libsodium-original-ISC.txt')
    for name in ['source.properties', 'NOTICE', 'NOTICE.toolchain']:
        shutil.copyfile(args.ndk / name, license_dir / ('NDK29-' + name))
    receipt = {'schema': 1, 'exitCode': 0, 'status': 'actual-sodium1.0.20-patched-page-aligned-native-produced',
               'elapsedSeconds': round(time.time() - started, 3), 'producerInputManifestSha256': sha(inputs / 'producer-inputs.json'),
               'originalSodiumSource': sodium_before,
               'effectivePatchedSodiumSource': prepared_state, 'sourceMutations': [],
               'upstreamSecurityFixCommit': 'ad3004ec8731730e93fcfbbc824e67eadc1c1bae',
               'ndk': {'version': '29.0.14206865', 'sourcePropertiesSha256': sha(args.ndk / 'source.properties'),
                       'toolsSha256': tool_hashes}, 'steps': steps, 'libraries': libraries,
               'upstreamRedRegressionExpectedExit': 2, 'upstreamGreenSuiteExit': 0,
               'upstreamGreenTestCounts': counts,
               'scope': 'Actual host upstream CVE red/green and four API26 Android native ELF/export/page/hostpath producers. '
                        'Original Maven Java/AAR provenance separate. No Android runtime, storage, APK or publication acceptance.'}
    (out / 'producer-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'exitCode': 0, 'outputs': len(libraries), 'elapsedSeconds': receipt['elapsedSeconds']}))


if __name__ == '__main__':
    main()
