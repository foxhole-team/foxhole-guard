#!/usr/bin/env python3
"""Build only the exact graphics1.0.1 native source, preserving its Java API."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from page_aligned_native_common import extract, require_graphics_export_surface, run, sha, symbol_set, tree


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
            raise ValueError('Frozen source/AAR/tool input differs: ' + name)
    if 'Pkg.Revision = 29.0.14206865' not in (args.ndk / 'source.properties').read_text():
        raise ValueError('Expected exact NDK29')
    tools = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    tool_names = ['clang', 'clang++', 'ld.lld', 'llvm-ar', 'llvm-ranlib', 'llvm-strip', 'llvm-nm', 'llvm-readelf']
    source = extract(inputs / 'graphics-path-1.0.1-native.tar', out / 'original-graphics')
    source_before = tree(source)
    if {n: v['sha256'] for n, v in source_before.items()} != expected['graphicsSourceFiles']:
        raise ValueError('Graphics compile source differs from exact Git/Maven binding')
    env = os.environ.copy()
    for name in ['CC', 'CXX', 'CPP', 'AR', 'RANLIB', 'LD', 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS',
                 'LDFLAGS', 'LIBS', 'CPATH', 'LIBRARY_PATH', 'C_INCLUDE_PATH',
                 'CPLUS_INCLUDE_PATH', 'PKG_CONFIG_PATH', 'MAKEFLAGS']:
        env.pop(name, None)
    env['SOURCE_DATE_EPOCH'] = '0'
    env['ZERO_AR_DATE'] = '1'
    env['TMPDIR'] = str(out / 'tmp')
    (out / 'tmp').mkdir()
    spec = importlib.util.spec_from_file_location('pages', args.source_consumer / 'scripts/verify-native-pages.py')
    pages = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pages)
    libraries, steps = [], []
    prefix_maps = ' '.join(f'-f{kind}-prefix-map={old}={new}' for old, new in
                          [(out, '/usr/src/foxhole-native'), (args.ndk, '/usr/src/android-ndk')]
                          for kind in ['file', 'macro', 'debug'])
    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
        work, directory = out / ('build-' + abi), out / 'jniLibs' / abi
        directory.mkdir(parents=True)
        steps.append(run(['cmake', '-S', str(source), '-B', str(work), '-G', 'Ninja',
                          '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_ABI=' + abi,
                          '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
                          '-DCMAKE_TOOLCHAIN_FILE=' + str(args.ndk / 'build/cmake/android.toolchain.cmake'),
                          '-DCMAKE_CXX_FLAGS=-Os -g0 ' + prefix_maps,
                          '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384'],
                         out, out / (abi + '-configure.log'), env, limit=120))
        steps.append(run(['cmake', '--build', str(work), '--parallel', '4'], out,
                         out / (abi + '-build.log'), env, limit=180))
        target = directory / 'libandroidx.graphics.path.so'
        steps.append(run([str(tools / 'llvm-strip'), '--strip-unneeded', '-o', str(target),
                          str(work / target.name)], out, out / (abi + '-strip.log'), env, limit=30))
        page = pages.verify(abi, target)
        original = inputs / 'original-native' / abi / target.name
        old_symbols, symbols = symbol_set(tools / 'llvm-nm', original), symbol_set(tools / 'llvm-nm', target)
        require_graphics_export_surface(old_symbols, symbols)
        run(['bash', str(args.source_consumer / 'scripts/verify-native-host-paths.sh'), str(target)],
            out, out / (abi + '-hostpath.log'), env, limit=30)
        run([str(tools / 'llvm-readelf'), '-h', '-l', '-d', '-Ws', str(target)], out,
            out / (abi + '-readelf.log'), env, limit=30)
        libraries.append({'abi': abi, 'path': str(target.relative_to(out)), 'bytes': target.stat().st_size,
                          'sha256': sha(target), 'api': 26, 'pageGate': page,
                          'originalMavenNativeSha256': sha(original), 'originalExportSymbols': old_symbols,
                          'derivedExportSymbols': symbols})
    if tree(source) != source_before:
        raise ValueError('Original graphics compile source mutated')
    license_dir = out / 'license-inputs'
    license_dir.mkdir()
    shutil.copyfile(inputs / 'original-source-LICENSE.txt', license_dir / 'graphics-original-Apache-2.0.txt')
    for name in ['source.properties', 'NOTICE', 'NOTICE.toolchain']:
        shutil.copyfile(args.ndk / name, license_dir / ('NDK29-' + name))
    receipt = {'schema': 1, 'exitCode': 0, 'status': 'actual-graphics1.0.1-page-aligned-native-produced',
               'elapsedSeconds': round(time.time() - started, 3),
               'producerInputManifestSha256': sha(inputs / 'producer-inputs.json'),
               'graphicsReleaseCommit': '8a05a22af450d589ef911d772a001a49dcb05b71',
               'graphicsOriginalSource': source_before, 'sourceMutations': [],
               'ndk': {'version': '29.0.14206865', 'sourcePropertiesSha256': sha(args.ndk / 'source.properties'),
                       'toolsSha256': {name: sha(tools / name) for name in tool_names}},
               'steps': steps, 'libraries': libraries, 'originalMavenJavaUnchanged': True,
               'scope': 'Actual four-ABI API26 derived graphics native, exact original1.0.1 source/export/license '
                        'and LOAD+RELRO/hostpath checks. Original Maven Java/AAR remains separate. '
                        'No Android runtime/merged APK/16KiB device/publication acceptance.'}
    (out / 'producer-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'exitCode': 0, 'outputs': len(libraries), 'elapsedSeconds': receipt['elapsedSeconds']}))


if __name__ == '__main__':
    main()
