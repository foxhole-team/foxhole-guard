"""Source-built native attribution remains distinct from canonical reference data."""
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('source_legal_fixture', Path(__file__).with_name('test_source_legal_snapshot.py'))
BASE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(BASE)
M = BASE.M


class SourceBuildLegalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app, self.core, self.snapshot, self.revision, self.reference = BASE.fixture(Path(self.temp.name).resolve(), 'android4', 'android4')
        self.roots = {'core': self.app / 'app/build/generated/foxCoreNativeLibs',
                      'sentinel': self.app / 'app/build/generated/sentinelNative/jniLibs'}
        native = {}
        for kind, folder in self.roots.items():
            native[kind] = {}
            library = 'libfoxhole_native.so' if kind == 'core' else 'libfoxhole_sentinel_yara.so'
            for target, abi in M.SHAPES['android4'].items():
                path = folder / abi / library
                raw = path.read_bytes() + b'source-build'
                path.write_bytes(raw)
                native[kind][target] = {'abi': abi, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        tree = subprocess.check_output(['git', '-C', str(self.core), 'rev-parse', 'HEAD^{tree}']).decode().strip()
        checkout = patch.object(M, 'source_checkout', return_value={'sourceCommit': self.revision, 'sourceTree': tree})
        checkout.start()
        self.addCleanup(checkout.stop)
        compiled_digest = hashlib.sha256((self.app / 'config/foxcore-source-manifest.json').read_bytes()).hexdigest()
        (self.app / 'config/foxcore-source-checkout.json').write_text(json.dumps({
            'schema': 1, 'sourceCommit': self.revision, 'sourceTree': tree,
            'producerCommit': self.revision, 'compiledSourceManifestSha256': compiled_digest,
        }))
        self.record = {'schema': 1, 'kind': 'normal-fdroid-native-source-build', 'sourceCommit': self.revision,
                       'sourceTree': tree, 'referenceProducerCommit': self.revision,
                       'compiledSourceManifestSha256': compiled_digest,
                       'androidDeliveryProfile': 'android4', 'abis': list(M.SHAPES['android4'].values()),
                       'targets': list(M.SHAPES['android4']), 'nativeLibraries': native}
        self.manifest = self.app / 'app/build/reports/fdroid/native-source-build.json'
        self.save()

    def save(self):
        self.manifest.parent.mkdir(parents=True, exist_ok=True)
        self.manifest.write_text(json.dumps(self.record))

    def load(self, with_source=True):
        return M.load(self.snapshot, self.app, self.core, self.revision, 'android4', self.roots['core'], self.roots['sentinel'],
                      self.manifest if with_source else None)

    def test_default_canonical_mode_still_rejects_different_native(self):
        with self.assertRaises(ValueError):
            self.load(False)

    def test_explicit_source_mode_keeps_reference_and_actual_hashes_separate(self):
        result = self.load()
        self.assertEqual(result['referenceNativeLibraries'], self.reference['nativeLibraries'])
        self.assertEqual(result['nativeLibraries'], self.record['nativeLibraries'])
        self.assertEqual(result['sourceNativeProvenance']['sourceCommit'], self.revision)
        M.recheck(result, self.core)

    def test_wrong_source_identity_is_rejected(self):
        self.record['sourceCommit'] = 'f' * 40
        self.save()
        with self.assertRaises(ValueError):
            self.load()

    def test_wrong_compiled_source_digest_is_rejected(self):
        self.record['compiledSourceManifestSha256'] = '0' * 64
        self.save()
        with self.assertRaises(ValueError):
            self.load()

    def test_omitted_x86_is_rejected(self):
        del self.record['nativeLibraries']['core']['i686-linux-android']
        self.save()
        with self.assertRaises(ValueError):
            self.load()

    def test_mutated_binary_does_not_match_source_production_manifest(self):
        path = self.roots['core'] / 'armeabi-v7a/libfoxhole_native.so'
        path.write_bytes(path.read_bytes() + b'x')
        with self.assertRaises(ValueError):
            self.load()

    def test_provenance_is_rechecked_after_loading(self):
        result = self.load()
        self.record['sourceTree'] = 'e' * 40
        self.save()
        with self.assertRaises(ValueError):
            M.recheck(result, self.core)

    def stage_native(self):
        staged = {'core': self.app / 'app/build/fdroidNativeSource/core',
                  'sentinel': self.app / 'app/build/fdroidNativeSource/sentinel/jniLibs'}
        for kind, path in staged.items():
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.move(self.roots[kind], path)
        return staged

    def test_staging_is_explicit_and_uses_fixed_source_producer_roots(self):
        staged = self.stage_native()
        with self.assertRaises(ValueError):
            M.load(self.snapshot, self.app, self.core, self.revision, 'android4',
                   staged['core'], staged['sentinel'], self.manifest)
        material = M.load(self.snapshot, self.app, self.core, self.revision, 'android4',
                          staged['core'], staged['sentinel'], self.manifest, True)
        self.assertEqual(material['nativeLibraries'], self.record['nativeLibraries'])
        M.recheck(material, self.core)

    def test_staging_keeps_native_hash_checks(self):
        staged = self.stage_native()
        path = staged['core'] / 'armeabi-v7a/libfoxhole_native.so'
        path.write_bytes(path.read_bytes() + b'tampered')
        with self.assertRaises(ValueError):
            M.load(self.snapshot, self.app, self.core, self.revision, 'android4',
                   staged['core'], staged['sentinel'], self.manifest, True)


if __name__ == '__main__':
    unittest.main()
