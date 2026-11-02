#!/usr/bin/env python3
"""Synthetic contract fixtures for rejecting stale or foreign AGP native selections."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('binding', ROOT / 'scripts/verify-page-aligned-native.py')
BINDING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BINDING)


class NativeBindingTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='native-binding-contract-')
        self.root = Path(self.owned.name)
        self.repo = self.root / 'repo'
        self.native = self.root / 'native'
        self.merged = self.root / 'merged'
        (self.repo / 'config/native').mkdir(parents=True)
        (self.repo / 'scripts').mkdir()
        self.native.mkdir()
        self.provenance = self.native / "provenance/graphics"
        self.provenance.mkdir(parents=True)
        shutil.copyfile(ROOT / 'scripts/verify-native-pages.py', self.repo / 'scripts/verify-native-pages.py')
        self.config = {'graphics': {'commit': 'exact-release', 'ndk': '29.0.14206865', 'api': 26,
                                   'sourceFiles': {'native.cpp': {'sha256': 'source'}},
                                   'originalAar': {'sha256': 'original-aar'},
                                   'originalSourceJar': {'sha256': 'original-java'},
                                   'sourceArchive': {'sha256': 'original-source'}}}
        (self.repo / 'config/native/page-aligned-native.json').write_text(json.dumps(self.config))
        self.inputs = {'files': {'graphics-path-1.0.1.aar': 'original-aar',
                                'graphics-path-1.0.1-sources.jar': 'original-java',
                                'graphics-path-1.0.1-native.tar': 'original-source'},
                       'repoInputHashes': {}}
        recipe_names = ['config/native/page-aligned-native.json', 'scripts/page_aligned_native_common.py',
                        'scripts/build-page-aligned-native.sh', 'scripts/prepare-page-aligned-native-inputs.py',
                        'scripts/verify-native-pages.py', 'scripts/verify-native-host-paths.sh',
                        'scripts/build-graphics-page-aligned.py']
        for name in recipe_names:
            path = self.repo / name
            if not path.exists():
                path.write_text('synthetic recipe identity: ' + name)
            self.inputs['repoInputHashes'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
        input_path = self.provenance / 'producer-inputs.json'
        input_path.write_text(json.dumps(self.inputs))
        self.receipt = {'schema': 1, 'exitCode': 0, 'sourceMutations': [],
                        'status': 'actual-graphics1.0.1-page-aligned-native-produced',
                        'graphicsReleaseCommit': 'exact-release',
                        'producerInputManifestSha256': hashlib.sha256(input_path.read_bytes()).hexdigest(),
                        'ndk': {'version': '29.0.14206865'},
                        'graphicsOriginalSource': {'native.cpp': {'sha256': 'source'}}, 'libraries': []}
        header = b'\x7fELF\x02\x01\x01' + b'\0' * 9
        header += struct.pack('<HHIQQQIHHHHHH', 3, 183, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
        header += struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 176, 16384, 16384)
        header += struct.pack('<IIQQQQQQ', 0x6474E552, 4, 0, 0, 0, 176, 16384, 1)
        self.bytes = header
        for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
            header = self.native_header(abi)
            path = self.native / abi / 'libandroidx.graphics.path.so'
            path.parent.mkdir()
            path.write_bytes(header)
            self.receipt['libraries'].append({'abi': abi, 'path': 'jniLibs/' + abi + '/' + path.name,
                                             'api': 26, 'sha256': hashlib.sha256(header).hexdigest(),
                                             'bytes': len(header),
                                             'originalExportSymbols': ['JNI_OnLoad@@LIBANDROIDX.GRAPHICS.PATH'],
                                             'derivedExportSymbols': ['JNI_OnLoad@@LIBANDROIDX.GRAPHICS.PATH']})
        for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
            selected = self.merged / 'lib' / abi / 'libandroidx.graphics.path.so'
            selected.parent.mkdir(parents=True)
            selected.write_bytes(self.native_header(abi))
        self.write_receipt()

    def tearDown(self):
        self.owned.cleanup()

    def write_receipt(self):
        (self.provenance / 'producer-receipt.json').write_text(json.dumps(self.receipt))

    def verify(self):
        BINDING.verify(self.repo, self.native, self.merged, ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'])

    def test_exact_derived_bytes_are_admitted(self):
        self.verify()

    def test_original_or_foreign_merge_bytes_are_rejected(self):
        foreign = bytearray(self.bytes)
        foreign[-1] ^= 1
        (self.merged / 'lib/arm64-v8a/libandroidx.graphics.path.so').write_bytes(foreign)
        with self.assertRaisesRegex(ValueError, 'original or foreign'):
            self.verify()

    def test_output_tamper_is_rejected(self):
        (self.native / 'arm64-v8a/libandroidx.graphics.path.so').write_bytes(self.bytes + b'tamper')
        with self.assertRaisesRegex(ValueError, 'producer'):
            self.verify()

    def test_unrelated_upstream_source_is_rejected(self):
        self.receipt['graphicsOriginalSource']['native.cpp']['sha256'] = 'unrelated'
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'release source'):
            self.verify()

    def test_changed_versioned_export_is_rejected(self):
        self.receipt['libraries'][0]['derivedExportSymbols'] = ['JNI_OnLoad@@OTHER']
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'export surface'):
            self.verify()

    def test_incomplete_producer_is_rejected(self):
        self.receipt['exitCode'] = 1
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'receipt'):
            self.verify()

    def test_original_maven_input_relabel_is_rejected(self):
        self.inputs['files']['graphics-path-1.0.1.aar'] = 'relabeled'
        path = self.provenance / 'producer-inputs.json'
        path.write_text(json.dumps(self.inputs))
        self.receipt['producerInputManifestSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'Original Maven'):
            self.verify()

    def update_input_receipt(self):
        path = self.provenance / 'producer-inputs.json'
        path.write_text(json.dumps(self.inputs))
        self.receipt['producerInputManifestSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write_receipt()

    def test_empty_recipe_map_is_rejected(self):
        self.inputs['repoInputHashes'] = {}
        self.update_input_receipt()
        with self.assertRaisesRegex(ValueError, 'recipe key set'):
            self.verify()

    def test_unrequested_foreign_abi_is_rejected(self):
        self.receipt['libraries'][1]['abi'] = 'foreign'
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'ABI inventory'):
            self.verify()

    def test_recipe_parent_symlink_escape_is_rejected(self):
        outside = self.root / 'outside-scripts'
        (self.repo / 'scripts').rename(outside)
        (self.repo / 'scripts').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'escaped'):
            self.verify()

    def test_duplicate_apk_entry_is_rejected(self):
        directory = self.root / 'apk'
        directory.mkdir()
        with zipfile.ZipFile(directory / 'synthetic.apk', 'w') as archive:
            archive.writestr('duplicate', b'one')
            archive.writestr('duplicate', b'two')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            BINDING.verify(self.repo, self.native, None, ['arm64-v8a'], directory)


    @staticmethod
    def native_header(abi):
        bits, machine = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}[abi]
        header = b'\x7fELF' + bytes([bits, 1, 1]) + b'\0' * 9
        if bits == 2:
            header += struct.pack('<HHIQQQIHHHHHH', 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
            header += struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 176, 16384, 16384)
            return header + struct.pack('<IIQQQQQQ', 0x6474E552, 4, 0, 0, 0, 176, 16384, 1)
        header += struct.pack('<HHIIIIIHHHHHH', 3, machine, 1, 0, 52, 0, 0, 52, 32, 2, 0, 0, 0)
        header += struct.pack('<IIIIIIII', 1, 0, 0, 0, 116, 16384, 5, 16384)
        return header + struct.pack('<IIIIIIII', 0x6474E552, 0, 0, 0, 116, 16384, 4, 1)

    def apk(self, abis, extra=None, omitted=()):
        directory = self.root / 'apk'
        directory.mkdir(exist_ok=True)
        with zipfile.ZipFile(directory / 'synthetic.apk', 'w') as archive:
            for abi in abis:
                for path in (self.merged / 'lib' / abi).glob('*.so'):
                    name = 'lib/' + abi + '/' + path.name
                    if name not in omitted:
                        archive.writestr(name, path.read_bytes())
            for name, data in (extra or {}).items():
                archive.writestr(name, data)
        return directory

    def project(self, directory=None, delivery=('arm64-v8a',)):
        BINDING.verify(self.repo, self.native, self.merged,
                       ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], directory, delivery_abis=delivery)

    def test_arm64_delivery_retains_the_four_abi_native_corpus(self):
        self.project(self.apk(['arm64-v8a']))

    def test_non_delivered_native_tamper_is_still_rejected(self):
        path = self.native / 'x86/libandroidx.graphics.path.so'
        path.write_bytes(path.read_bytes() + b'tamper')
        with self.assertRaisesRegex(ValueError, 'producer'):
            self.project(self.apk(['arm64-v8a']))

    def test_delivery_must_be_nonempty_unique_known_native_subset(self):
        for delivery in ([], ['mips'], ['arm64-v8a', 'arm64-v8a']):
            with self.subTest(delivery=delivery), self.assertRaisesRegex(ValueError, 'delivery'):
                self.project(delivery=delivery)
        with self.assertRaisesRegex(ValueError, 'delivery'):
            BINDING.verify(self.repo, self.native, self.merged, ['arm64-v8a'], delivery_abis=['x86_64'])

    def test_extra_or_missing_apk_abi_is_rejected(self):
        directory = self.apk(['arm64-v8a'], {'lib/x86_64/libandroidx.graphics.path.so': self.native_header('x86_64')})
        with self.assertRaisesRegex(ValueError, 'ABI|delivery'):
            self.project(directory)
        directory = self.apk([])
        with self.assertRaisesRegex(ValueError, 'ABI|delivery'):
            self.project(directory)

    def test_extra_or_missing_apk_library_is_rejected(self):
        directory = self.apk(['arm64-v8a'], {'lib/arm64-v8a/libunexpected.so': self.bytes})
        with self.assertRaisesRegex(ValueError, 'library inventory'):
            self.project(directory)
        core = self.merged / 'lib/arm64-v8a/libfoxhole_native.so'
        core.write_bytes(self.bytes)
        directory = self.apk(['arm64-v8a'], omitted=['lib/arm64-v8a/libfoxhole_native.so'])
        with self.assertRaisesRegex(ValueError, 'library inventory'):
            self.project(directory)

    def test_non_delivered_merged_tamper_is_rejected(self):
        extra = self.merged / 'lib/x86_64/libandroidx.graphics.path.so'
        extra.write_bytes(self.native_header('x86_64') + b'tamper')
        with self.assertRaisesRegex(ValueError, 'foreign'):
            self.project()

    def test_apk_native_header_cannot_claim_another_abi(self):
        core = self.merged / 'lib/arm64-v8a/libfoxhole_native.so'
        core.write_bytes(self.bytes)
        directory = self.apk(['arm64-v8a'], omitted=['lib/arm64-v8a/libfoxhole_native.so'],
                             extra={'lib/arm64-v8a/libfoxhole_native.so': self.native_header('x86_64')})
        with self.assertRaisesRegex(ValueError, 'ELF|ABI'):
            self.project(directory)

    def test_default_full_profile_still_requires_and_accepts_all_four_abis(self):
        abis = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']
        for abi in abis[1:]:
            destination = self.merged / 'lib' / abi
            destination.mkdir(exist_ok=True)
            shutil.copyfile(self.native / abi / 'libandroidx.graphics.path.so', destination / 'libandroidx.graphics.path.so')
        directory = self.apk(abis)
        BINDING.verify(self.repo, self.native, self.merged, abis, directory)
        with self.assertRaisesRegex(ValueError, 'ABI|delivery'):
            BINDING.verify(self.repo, self.native, self.merged, abis, self.apk(['arm64-v8a']))


if __name__ == '__main__':
    unittest.main()
