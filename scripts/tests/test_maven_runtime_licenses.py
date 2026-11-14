"""Current producer, source and legal payload boundaries; no legacy projection hooks."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('current_maven_legal', ROOT / 'scripts/copy-maven-runtime-licenses.py')
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class MavenRuntimeLicenseTests(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='current-maven-legal-')
        self.addCleanup(self.owned.cleanup)
        self.root = Path(self.owned.name).resolve()
        self.repo, self.bundle = self.root / 'repo', self.root / 'bundle'
        self.repo.mkdir()
        shutil.copytree(ROOT / 'third_party/maven-runtime', self.bundle)
        self.manifest = json.loads((self.bundle / 'MANIFEST.json').read_bytes())
        self.producer = json.loads((self.bundle / 'PRODUCER.json').read_bytes())
        for name in self.producer['sourceInputs']:
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)

    def load(self):
        return HELPER.load(self.repo, self.bundle)

    def rehashed_manifest(self):
        # Only the synthetic manifest is rebound; the independent producer pin stays fixed.
        raw = json.dumps(self.manifest).encode()
        (self.bundle / 'MANIFEST.json').write_bytes(raw)
        return HELPER.contract.load_bundle(self.repo, self.bundle, hashlib.sha256(raw).hexdigest(),
            expected_producer=HELPER.EXPECTED_PRODUCER_SHA256)[0]

    def update_member_hash(self, name):
        raw = (self.bundle / name).read_bytes()
        row = next(r for r in self.manifest['files'] if r['path'] == name)
        row.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())

    def test_current_exact_capture_and_all_legal_bytes_are_accepted_without_mutation(self):
        expected = {r['path']: (self.bundle / r['path']).read_bytes() for r in self.manifest['files']}
        self.assertEqual(expected, self.load())
        self.assertEqual(expected, {name: (self.bundle / name).read_bytes() for name in expected})
        capture = json.loads(expected['runtime-inventory.json'])
        self.assertEqual(176, len(capture['components']))
        self.assertEqual(124, len(capture['artifacts']))

    def test_every_captured_source_input_is_required_byte_for_byte(self):
        for name in self.producer['sourceInputs']:
            path = self.repo / name
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            with self.subTest(source=name), self.assertRaises(ValueError):
                self.load()
            path.write_bytes(original)

    def test_previous_and_future_versions_cannot_borrow_the_current_capture(self):
        path = self.repo / 'app/build.gradle.kts'
        original = path.read_bytes()
        code = int(re.search(rb'val baseAppVersionCode = ([0-9]+)', original).group(1))
        for value in [code - 1, code + 1]:
            path.write_bytes(original.replace(f'val baseAppVersionCode = {code}'.encode(),
                f'val baseAppVersionCode = {value}'.encode(), 1))
            with self.subTest(version=value), self.assertRaises(ValueError):
                self.load()

    def test_dependency_native_task_and_source_channel_changes_are_not_normalized(self):
        path = self.repo / 'app/build.gradle.kts'
        original = path.read_bytes()
        changes = [
            (b'implementation(libs.androidx.core.ktx)', b'implementation("org.example:foreign:1.0")'),
            (b'outputs.doNotCacheIf("Selected Sentinel legal inputs require validation") { true }',
             b'outputs.doNotCacheIf("Selected Sentinel legal inputs require validation") { false }'),
            (b'require(sourceNativeManifest == null || appUpdateChannel == "fdroid")', b'require(true)'),
        ]
        for before, after in changes:
            changed = original.replace(before, after, 1)
            self.assertNotEqual(original, changed)
            path.write_bytes(changed)
            with self.subTest(change=before.decode()), self.assertRaises(ValueError):
                self.load()

    def test_source_symlinks_and_missing_source_inputs_are_rejected(self):
        path = self.repo / 'app/build.gradle.kts'
        held = self.root / 'held-gradle.kts'
        path.replace(held)
        with self.assertRaises(FileNotFoundError):
            self.load()
        path.symlink_to(held)
        with self.assertRaises(ValueError):
            self.load()

    def test_foreign_manifest_and_relabelled_producer_are_rejected(self):
        path = self.bundle / 'MANIFEST.json'
        path.write_bytes(path.read_bytes() + b'\n')
        with self.assertRaises(ValueError):
            self.load()
        self.producer['versionCode'] += 1
        (self.bundle / 'PRODUCER.json').write_text(json.dumps(self.producer))
        self.update_member_hash('PRODUCER.json')
        with self.assertRaises(ValueError):
            self.rehashed_manifest()

    def test_missing_changed_and_extra_payloads_are_rejected(self):
        path = self.bundle / 'runtime-inventory.json'
        original = path.read_bytes()
        for value in [None, b'tampered']:
            if value is None:
                path.unlink()
            else:
                path.write_bytes(value)
            expected_error = FileNotFoundError if value is None else ValueError
            with self.subTest(value=value), self.assertRaises(expected_error):
                self.load()
        path.write_bytes(original)
        (self.bundle / 'foreign.txt').write_text('unlisted')
        with self.assertRaises(ValueError):
            self.load()

    def test_duplicate_paths_and_parent_escape_fail_before_external_reads(self):
        original = copy.deepcopy(self.manifest)
        self.manifest['files'].append(copy.deepcopy(self.manifest['files'][0]))
        with self.assertRaises(ValueError):
            self.rehashed_manifest()
        self.manifest = original
        self.manifest['files'][0]['path'] = '../outside'
        with self.assertRaises(ValueError):
            self.rehashed_manifest()

    def test_oversized_manifest_and_payload_are_rejected(self):
        path = self.bundle / 'MANIFEST.json'
        original = path.read_bytes()
        with path.open('wb') as stream:
            stream.truncate(HELPER.contract.MAX_FILE + 1)
        with self.assertRaises(ValueError):
            self.load()
        path.write_bytes(original)
        with (self.bundle / 'runtime-inventory.json').open('wb') as stream:
            stream.truncate(HELPER.contract.MAX_FILE + 1)
        with self.assertRaises(ValueError):
            self.load()

    def test_bundle_and_repository_root_links_are_rejected(self):
        for target, repo, bundle in [(self.repo, True, False), (self.bundle, False, True)]:
            link = self.root / ('repo-link' if repo else 'bundle-link')
            link.symlink_to(target, target_is_directory=True)
            with self.subTest(target=target.name), self.assertRaises(ValueError):
                HELPER.load(link if repo else self.repo, link if bundle else self.bundle)

    def test_payload_symlinks_and_hardlinks_are_rejected(self):
        path = self.bundle / 'runtime-inventory.json'
        held = self.root / 'held-capture.json'
        path.replace(held)
        path.symlink_to(held)
        with self.assertRaises(ValueError):
            self.load()
        path.unlink()
        os.link(held, path)
        with self.assertRaises(ValueError):
            self.load()

    def test_a_changed_original_license_cannot_be_hidden_by_a_new_manifest(self):
        name = next(r['path'] for r in self.manifest['files'] if r['path'].startswith('files/'))
        path = self.bundle / name
        path.write_bytes(path.read_bytes() + b'altered')
        self.update_member_hash(name)
        with self.assertRaises(ValueError):
            self.rehashed_manifest()

    def test_incomplete_legal_report_and_false_collector_identity_are_rejected(self):
        path = self.bundle / 'maven-notice-report.json'
        original = json.loads(path.read_bytes())
        for key, value in [('missing', ['missing original']), ('derived_from_actual_report_sha256', 'd' * 64)]:
            report = copy.deepcopy(original)
            report[key] = value
            path.write_text(json.dumps(report))
            self.update_member_hash(path.name)
            with self.subTest(field=key), self.assertRaises(ValueError):
                self.rehashed_manifest()

    def test_private_artifact_paths_cannot_enter_a_rebound_capture(self):
        path = self.bundle / 'runtime-inventory.json'
        capture = json.loads(path.read_bytes())
        capture['artifacts'][0]['path'] = '/example/private-cache'
        path.write_text(json.dumps(capture))
        self.update_member_hash(path.name)
        with self.assertRaises(ValueError):
            self.rehashed_manifest()

    def test_fdroid_projection_requires_the_exact_actual_stock_signing_removal(self):
        expected = self.load()
        with self.assertRaises(ValueError):
            HELPER.load(self.repo, self.bundle, allow_fdroid_unsigned_projection=True)
        path = self.repo / 'app/build.gradle.kts'
        fixture = {
            '633f3b0609e2b4acffac2921c71d2a3aaeb0cbf1f93b36ad87e80f6023a0e605': 'guard181-unsigned-app.gradle.kts',
            'bb3fc5290b204bb9dad942787ba64c7949cf19cf0e46876749238e6ba9cbfd31': 'guard181-delivery-arm64-unsigned-app.gradle.kts',
        }[self.producer['sourceInputs']['app/build.gradle.kts']]
        unsigned = (ROOT / 'scripts/tests/fixtures/fdroid' / fixture).read_bytes()
        path.write_bytes(unsigned)
        self.assertEqual(expected, HELPER.load(self.repo, self.bundle, allow_fdroid_unsigned_projection=True))
        with self.assertRaises(ValueError):
            self.load()
        path.write_bytes(unsigned + b'\n')
        with self.assertRaises(ValueError):
            HELPER.load(self.repo, self.bundle, allow_fdroid_unsigned_projection=True)


if __name__ == '__main__':
    unittest.main()
