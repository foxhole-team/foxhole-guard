import hashlib
import importlib.util
import json
import copy
import re
import shutil
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('maven_legal', ROOT / 'scripts/copy-maven-runtime-licenses.py')
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class MavenRuntimeLicenseTests(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='maven-legal-contract-')
        self.root = Path(self.owned.name).resolve()
        self.repo = self.root / 'repo'
        self.bundle = self.root / 'bundle'
        self.repo.mkdir()
        shutil.copytree(ROOT / 'third_party/maven-runtime', self.bundle)
        self.capture_raw = (self.bundle / 'runtime-inventory.json').read_bytes()
        self.report_raw = (self.bundle / 'maven-notice-report.json').read_bytes()
        self.projection_raw = (self.bundle / 'PORTABLE-PROVENANCE.json').read_bytes()
        self.capture = json.loads(self.capture_raw)
        self.report = json.loads(self.report_raw)
        self.projection = json.loads(self.projection_raw)
        self.consumer = json.loads((self.bundle / 'consumer-runtime-inventory.json').read_bytes())
        self.proof = json.loads((self.bundle / 'CONSUMER-BINDING.json').read_bytes())
        for name in self.proof['sourceInputs']:
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        self.actual_capture = HELPER.ACTUAL_CAPTURE_SHA256
        self.actual_report = HELPER.ACTUAL_COLLECTOR_REPORT_SHA256
        self.expected_files = {row['path'] for row in json.loads((self.bundle / 'MANIFEST.json').read_bytes())['files']}
        self.load_actual_projection_fixture()

    def tearDown(self):
        self.owned.cleanup()

    @staticmethod
    def preserved_bytes(value, original):
        return original if value == json.loads(original) else json.dumps(value).encode()

    def write_manifest(self):
        rows = [{'path': p.relative_to(self.bundle).as_posix(), 'bytes': p.stat().st_size,
                 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                for p in sorted(self.bundle.rglob('*')) if p.is_file() and p.name != 'MANIFEST.json']
        (self.bundle / 'MANIFEST.json').write_text(json.dumps({'schema': 1,
            'scope': 'actual-guard-release-runtime-notices', 'files': rows}))

    def write_bundle(self):
        capture = self.preserved_bytes(self.capture, self.capture_raw)
        self.report['capture_sha256'] = hashlib.sha256(capture).hexdigest()
        report_bytes = self.preserved_bytes(self.report, self.report_raw)
        projection = copy.deepcopy(self.projection)
        projection['portableCaptureSha256'] = hashlib.sha256(capture).hexdigest()
        projection['derivedCollectorReportSha256'] = hashlib.sha256(report_bytes).hexdigest()
        identity = {key: self.capture[key] for key in ['components', 'edges', 'artifacts', 'configuration', 'source_sha256']}
        projection['artifactIdentityAndHashProjectionSha256'] = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        (self.bundle / 'runtime-inventory.json').write_bytes(capture)
        (self.bundle / 'maven-notice-report.json').write_bytes(report_bytes)
        (self.bundle / 'PORTABLE-PROVENANCE.json').write_bytes(self.preserved_bytes(projection, self.projection_raw))
        self.write_manifest()

    def bind_consumer_source(self, name, data):
        # Only the synthetic current consumer changes; original producer bytes stay frozen.
        self.consumer['source_sha256'][name] = hashlib.sha256(data).hexdigest()
        raw = json.dumps(self.consumer).encode()
        self.proof['portableConsumerCaptureSha256'] = hashlib.sha256(raw).hexdigest()
        self.proof['sourceInputs'] = copy.deepcopy(self.consumer['source_sha256'])
        (self.bundle / 'consumer-runtime-inventory.json').write_bytes(raw)
        (self.bundle / 'CONSUMER-BINDING.json').write_text(json.dumps(self.proof))
        self.write_manifest()

    def test_bound_capture_and_exact_projection_are_accepted(self):
        self.assertEqual(set(self.load_actual_projection_fixture()), self.expected_files)

    def test_identity_capture_does_not_remove_the_declared_input_hook(self):
        full = (self.repo / 'app/build.gradle.kts').read_bytes()
        self.assertEqual(self.proof['sourceInputs']['app/build.gradle.kts'], hashlib.sha256(full).hexdigest())
        self.assertEqual(set(self.load_actual_projection_fixture()), self.expected_files)

    def test_duplicate_input_hook_is_rejected(self):
        path = self.repo / 'app/build.gradle.kts'
        data = path.read_bytes() + HELPER.HOOK.encode()
        path.write_bytes(data)
        self.bind_consumer_source('app/build.gradle.kts', data)
        with self.assertRaisesRegex(ValueError, 'projection hook differs'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / 'MANIFEST.json').read_bytes()).hexdigest())

    def test_version_or_dependency_delta_is_rejected_without_recapture(self):
        (self.repo / 'app/build.gradle.kts').write_text('changed version or dependency\n' + HELPER.HOOK)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / 'MANIFEST.json').read_bytes()).hexdigest())

    def test_foreign_manifest_cannot_replace_the_captured_bundle(self):
        manifest = self.bundle / 'MANIFEST.json'
        manifest.write_bytes(manifest.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'manifest is not'):
            HELPER.load(self.repo, self.bundle)

    def test_changed_file_bytes_are_rejected(self):
        (self.bundle / 'runtime-inventory.json').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'captured file (changed|size differs)'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_stale_source_graph_is_rejected(self):
        (self.repo / 'app/build.gradle.kts').write_text('other\n' + HELPER.HOOK)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_missing_file_is_rejected(self):
        (self.bundle / 'runtime-inventory.json').unlink()
        with self.assertRaisesRegex(ValueError, 'missing'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_duplicate_manifest_path_is_rejected(self):
        p = self.bundle / 'MANIFEST.json'
        v = json.loads(p.read_text())
        v['files'].append(v['files'][0])
        p.write_text(json.dumps(v))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_parent_escape_is_rejected(self):
        p = self.bundle / 'MANIFEST.json'
        v = json.loads(p.read_text())
        v['files'][0]['path'] = '../outside'
        p.write_text(json.dumps(v))
        with self.assertRaisesRegex(ValueError, 'escaped'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_oversized_file_is_rejected_before_reading(self):
        with (self.bundle / 'runtime-inventory.json').open('wb') as stream:
            stream.truncate(9 * 1024 * 1024)
        with self.assertRaisesRegex(ValueError, 'size differs'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_oversized_manifest_is_rejected_before_reading(self):
        with (self.bundle / 'MANIFEST.json').open('wb') as stream:
            stream.truncate(1024 * 1024 + 1)
        with self.assertRaisesRegex(ValueError, 'manifest size'):
            HELPER.load(self.repo, self.bundle)

    def test_bundle_root_symlink_is_rejected(self):
        link = self.root / 'bundle-link'
        link.symlink_to(self.bundle, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'root is a link'):
            HELPER.load(self.repo, link)

    def test_repository_root_symlink_is_rejected(self):
        link = self.root / 'repo-link'
        link.symlink_to(self.repo, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'root is a link'):
            HELPER.load(link, self.bundle)

    def test_private_artifact_path_cannot_enter_the_portable_view(self):
        self.capture['artifacts'][0]['path'] = '/example/private-cache'
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, 'whitelisted'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_false_actual_collector_identity_is_rejected(self):
        self.report['derived_from_actual_report_sha256'] = 'foreign'
        self.write_bundle()
        path = self.bundle / 'PORTABLE-PROVENANCE.json'
        projection = json.loads(path.read_text())
        projection['actualCollectorReportSha256'] = 'foreign'
        path.write_text(json.dumps(projection))
        rows = json.loads((self.bundle / 'MANIFEST.json').read_text())
        for row in rows['files']:
            data = (self.bundle / row['path']).read_bytes()
            row['bytes'] = len(data)
            row['sha256'] = hashlib.sha256(data).hexdigest()
        (self.bundle / 'MANIFEST.json').write_text(json.dumps(rows))
        with self.assertRaisesRegex(ValueError, 'provenance chain'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())

    def test_unreported_legal_gap_is_rejected(self):
        self.report['missing'] = ['missing original']
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            HELPER.load(self.repo, self.bundle, hashlib.sha256((self.bundle / "MANIFEST.json").read_bytes()).hexdigest())


    def use_actual_current_gradle(self, data=None):
        current = (ROOT / 'app/build.gradle.kts').read_bytes()
        self.assertEqual(hashlib.sha256(current).hexdigest(),
                         self.proof['sourceInputs']['app/build.gradle.kts'])
        (self.repo / 'app/build.gradle.kts').write_bytes(current if data is None else data)
        return current

    def load_actual_projection_fixture(self):
        return HELPER.load(self.repo, self.bundle,
                           hashlib.sha256((self.bundle / 'MANIFEST.json').read_bytes()).hexdigest())

    def test_current_metadata_rebind_preserves_original_capture_bytes(self):
        current = self.use_actual_current_gradle()
        original_capture = (self.bundle / 'runtime-inventory.json').read_bytes()
        original_manifest = (self.bundle / 'MANIFEST.json').read_bytes()
        checked = self.load_actual_projection_fixture()
        self.assertEqual(checked['runtime-inventory.json'], original_capture)
        self.assertEqual((self.bundle / 'MANIFEST.json').read_bytes(), original_manifest)
        self.assertEqual((self.repo / 'app/build.gradle.kts').read_bytes(), current)
        self.assertEqual(json.loads(checked['runtime-inventory.json'])['source_sha256'],
                         self.capture['source_sha256'])

    def test_previous_version_cannot_borrow_the_current_capture(self):
        current = self.use_actual_current_gradle()
        code = int(re.search(rb'val baseAppVersionCode = ([0-9]+)', current).group(1))
        previous = current.replace(f'val baseAppVersionCode = {code}\n'.encode(),
                                   f'val baseAppVersionCode = {code - 1}\n'.encode(), 1)
        self.assertNotEqual(previous, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(previous)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_dependency_change_cannot_borrow_current_source_identity(self):
        current = self.use_actual_current_gradle()
        changed = current.replace(b'implementation(libs.androidx.core.ktx)',
                                  b'implementation("org.example:foreign:1.0")', 1)
        self.assertNotEqual(changed, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_changed_native_task_behavior_is_not_normalized_away(self):
        current = self.use_actual_current_gradle()
        changed = current.replace(b'outputs.doNotCacheIf("Selected Sentinel legal inputs require validation") { true }',
                                  b'outputs.doNotCacheIf("Selected Sentinel legal inputs require validation") { false }', 1)
        self.assertNotEqual(changed, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_partial_native_task_plus_review_code_is_rejected(self):
        current = self.use_actual_current_gradle()
        changed = current.replace(b'        rootProject.file("scripts/selected-sentinel-sbom.py"),\n', b'', 1)
        self.assertNotEqual(changed, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_unreviewed_version_change_cannot_borrow_current_source_identity(self):
        current = self.use_actual_current_gradle()
        code = int(re.search(rb'val baseAppVersionCode = ([0-9]+)', current).group(1))
        changed = current.replace(f'val baseAppVersionCode = {code}\n'.encode(),
                                  f'val baseAppVersionCode = {code + 1}\n'.encode(), 1)
        self.assertNotEqual(changed, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_current_source_projection_does_not_relabel_portable_provenance(self):
        self.use_actual_current_gradle()
        path = self.bundle / 'PORTABLE-PROVENANCE.json'
        value = json.loads(path.read_bytes())
        value['sourceInputProjections']['app/build.gradle.kts']['mode'] = 'unverified-skip'
        path.write_text(json.dumps(value))
        manifest_path = self.bundle / 'MANIFEST.json'
        manifest = json.loads(manifest_path.read_bytes())
        for row in manifest['files']:
            data = (self.bundle / row['path']).read_bytes()
            row.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'current source identity projection differs'):
            self.load_actual_projection_fixture()

    def test_bound_source_cannot_claim_original_identity_policy(self):
        self.use_actual_current_gradle()
        raw_hash = self.capture['source_sha256']['app/build.gradle.kts']
        self.assertNotEqual(raw_hash, self.proof['sourceInputs']['app/build.gradle.kts'])
        self.consumer['source_sha256']['app/build.gradle.kts'] = raw_hash
        raw = json.dumps(self.consumer).encode()
        self.proof['sourceInputs'] = copy.deepcopy(self.consumer['source_sha256'])
        self.proof['portableConsumerCaptureSha256'] = hashlib.sha256(raw).hexdigest()
        (self.bundle / 'consumer-runtime-inventory.json').write_bytes(raw)
        (self.bundle / 'CONSUMER-BINDING.json').write_text(json.dumps(self.proof))
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()

    def test_projected_source_symlink_is_still_rejected(self):
        self.use_actual_current_gradle()
        path = self.repo / 'app/build.gradle.kts'
        target = self.root / 'actual-gradle.kts'
        path.replace(target)
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'regular input is missing'):
            self.load_actual_projection_fixture()


    def test_previous_bound_hash_cannot_borrow_updated_projection(self):
        with patch.object(HELPER, 'digest', return_value='41f22b62565e33fe47432d7aaa9908cc770b64802b7c5f6ec0574d67bc573ee2'):
            self.assertFalse(HELPER.matches_captured_gradle(b'unused',
                '31d354e6572649d9e5f47cd6cf0110ff8a766d63331fc819d3b80c949345a06b'))

    def test_original_capture_hash_cannot_claim_current_source_projection(self):
        with patch.object(HELPER, 'digest', return_value='31d354e6572649d9e5f47cd6cf0110ff8a766d63331fc819d3b80c949345a06b'):
            self.assertFalse(HELPER.matches_captured_gradle(b'unused',
                '31d354e6572649d9e5f47cd6cf0110ff8a766d63331fc819d3b80c949345a06b'))

    def test_source_native_channel_policy_change_is_not_normalized_away(self):
        current = self.use_actual_current_gradle()
        changed = current.replace(b'require(sourceNativeManifest == null || appUpdateChannel == "fdroid")',
                                  b'require(true)', 1)
        self.assertNotEqual(changed, current)
        (self.repo / 'app/build.gradle.kts').write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'current Gradle source changed'):
            self.load_actual_projection_fixture()


class ActualMavenRuntimeBundleTests(unittest.TestCase):
    def test_actual_source_projection_loads_without_recapturing_graph_or_notices(self):
        bundle = ROOT / 'third_party/maven-runtime'
        capture_before = (bundle / 'runtime-inventory.json').read_bytes()
        report_before = (bundle / 'maven-notice-report.json').read_bytes()
        checked = HELPER.load(ROOT, bundle)
        self.assertEqual(checked['runtime-inventory.json'], capture_before)
        self.assertEqual(checked['maven-notice-report.json'], report_before)
        capture = json.loads(capture_before)
        projection = json.loads((bundle / 'PORTABLE-PROVENANCE.json').read_bytes())
        self.assertEqual(capture['source_sha256']['app/build.gradle.kts'],
                         projection['sourceInputProjections']['app/build.gradle.kts']['capturedSha256'])
        self.assertEqual(len(capture['components']), 174)
        self.assertEqual(len(capture['artifacts']), 122)


if __name__ == '__main__':
    unittest.main()
