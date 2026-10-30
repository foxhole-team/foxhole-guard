#!/usr/bin/env python3
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('delivery', ROOT / 'scripts/aggregate-delivery-sbom.py')
DELIVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DELIVERY)
COPY_SPEC = importlib.util.spec_from_file_location('canonical_derived_evidence', ROOT / 'scripts/copy-derived-native-licenses.py')
COPY = importlib.util.module_from_spec(COPY_SPEC)
COPY_SPEC.loader.exec_module(COPY)


class DerivedNativeSbomTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='derived-sbom-contract-')
        self.root = Path(self.owned.name)
        (self.root / 'config/native').mkdir(parents=True)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(ROOT / 'scripts/copy-derived-native-licenses.py', self.root / 'scripts/copy-derived-native-licenses.py')
        self.data = b'synthetic derived ELF payload'
        self.inputs = {'files': {'graphics-path-1.0.1.aar': 'original-maven'}}
        self.input_bytes = json.dumps(self.inputs).encode()
        original_producer = {'schema': 1, 'exitCode': 0, 'sourceMutations': [],
                         'status': 'actual-graphics1.0.1-page-aligned-native-produced',
                         'elapsedSeconds': 0.25,
                         'producerInputManifestSha256': hashlib.sha256(self.input_bytes).hexdigest(),
                         'graphicsReleaseCommit': 'exact-release',
                         'graphicsOriginalSource': {'native.cpp': {'sha256': 'source'}},
                         'libraries': [{'abi': 'arm64-v8a', 'path': 'jniLibs/arm64-v8a/libandroidx.graphics.path.so',
                                        'bytes': len(self.data), 'sha256': hashlib.sha256(self.data).hexdigest()}],
                         'ndk': {'version': '29.0.14206865', 'sourcePropertiesSha256': 'a' * 64,
                                 'toolsSha256': {'llvm-strip': 'b' * 64}},
                         'steps': [{'argv': ['cmake', '-S', '/synthetic/output/original-graphics', '-B',
                                             '/synthetic/output/build-arm64-v8a', '-G', 'Ninja'],
                                    'exitCode': 0, 'logSha256': 'c' * 64},
                                   {'argv': ['/synthetic/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip',
                                             '--strip-unneeded', '-o', '/synthetic/output/jniLibs/arm64-v8a/libandroidx.graphics.path.so'],
                                    'exitCode': 0, 'logSha256': 'd' * 64}],
                         'originalMavenJavaUnchanged': True, 'scope': 'Synthetic derived SBOM metadata fixture.'}
        self.producer = COPY.canonical_evidence('graphics', original_producer)
        self.config = {'graphics': {'commit': 'exact-release', 'sourceFiles': {'native.cpp': {'sha256': 'source'}},
                                    'originalAar': {'sha256': 'original-maven'}}}
        (self.root / 'config/native/page-aligned-native.json').write_text(json.dumps(self.config))

    def tearDown(self):
        self.owned.cleanup()

    def component(self):
        def notice(_archive, _notices, name):
            return {'derived-native/graphics/producer-inputs.json': self.input_bytes,
                    'derived-native/graphics/producer-evidence.json': COPY.encoded(self.producer)}[name]
        with patch.object(DELIVERY, 'verified_notice', side_effect=notice):
            return DELIVERY.derived_native_component('libandroidx.graphics.path.so',
                'lib/arm64-v8a/libandroidx.graphics.path.so', self.data, None, None, self.root)

    def test_native_component_is_distinct_from_original_maven(self):
        result = self.component()
        self.assertEqual(result['name'], 'foxhole-derived-graphics-native')
        self.assertTrue(result['purl'].startswith('pkg:generic/'))
        self.assertEqual(result['version'], '1.0.1+foxhole16k')
        properties = {row['name']: row['value'] for row in result['properties']}
        self.assertIn('foxhole:producerEvidenceSha256', properties)
        self.assertNotIn('foxhole:producerReceiptSha256', properties)

    def test_foreign_native_bytes_are_rejected(self):
        self.producer['libraries'][0]['sha256'] = 'foreign'
        with self.assertRaisesRegex(ValueError, 'APK bytes'):
            self.component()

    def test_missing_actual_producer_success_is_rejected(self):
        self.producer['exitCode'] = 1
        with self.assertRaisesRegex(ValueError, 'producer'):
            self.component()

    def test_unrelated_source_commit_is_rejected(self):
        self.producer['graphicsReleaseCommit'] = 'unrelated'
        with self.assertRaisesRegex(ValueError, 'release source'):
            self.component()

    def test_maven_java_input_cannot_be_relabeled(self):
        self.inputs['files']['graphics-path-1.0.1.aar'] = 'relabeled'
        self.input_bytes = json.dumps(self.inputs).encode()
        self.producer['producerInputManifestSha256'] = hashlib.sha256(self.input_bytes).hexdigest()
        with self.assertRaisesRegex(ValueError, 'Maven Java'):
            self.component()


if __name__ == '__main__':
    unittest.main()
