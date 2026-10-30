"""Exercise native receipt -> selected targets -> notices -> real aggregate tail."""
import copy
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import shutil
import unittest
from unittest.mock import patch

TESTS = Path(__file__).resolve().parent
SCRIPTS = Path(os.environ.get("FOXHOLE_SENTINEL_TEST_SCRIPTS", str(TESTS.parent))).resolve(strict=True)


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


LEGACY = load('sentinel_current_existing_fixtures', TESTS / 'test_selected_sentinel_sbom.py')
BASE = load('sentinel_current_aggregate_fixtures', TESTS / 'test_delivery_sbom.py')
# The unchanged public fixtures supply data; every verifier/generator call uses
# the explicitly selected edition scripts, allowing the private delta to use
# the same lifecycle cases without copying a second fixture framework.
LEGACY.SPEC = importlib.util.spec_from_file_location('selected_current_edition', SCRIPTS / 'selected-sentinel-sbom.py')
LEGACY.MODULE = importlib.util.module_from_spec(LEGACY.SPEC)
LEGACY.GENERATOR = load('license_current_edition', SCRIPTS / 'generate-license-assets.py')
BASE.MODULE = load('aggregate_current_edition', SCRIPTS / 'aggregate-delivery-sbom.py')


class SentinelAndroid64DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = LEGACY.SelectedSentinelSbomTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.four_delivery()
        self.module = LEGACY.MODULE
        self.convert_to_current()

    def convert_to_current(self):
        f, m = self.fixture, self.module
        f.receipt['targets'] = {k: v for k, v in f.receipt['targets'].items() if k in m.CURRENT_TARGET_ABIS}
        shared = next(iter(f.receipt['targets'].values()))
        original = m.read_json((f.root / shared['receipt']['path']).read_bytes())
        for name in ('producerReceipt', 'sourceManifest', 'captureReceipt'):
            record = original['inputs'][name]
            value = m.read_json((f.root / record['path']).read_bytes())
            value.update(androidDeliveryProfile='android64', abis=['arm64-v8a', 'x86_64'], targets=list(m.CURRENT_TARGET_ABIS))
            if name == 'producerReceipt':
                value['libraries'] = [r for r in value['libraries'] if r['path'].split('/')[0] in ('arm64-v8a', 'x86_64')]
            if name == 'captureReceipt':
                value['producerReceiptSha256'] = f.inputs['producerReceipt']['sha256']
                value['steps'] = [r for r in value['steps'] if r['label'] in {'sbom-' + t for t in m.CURRENT_TARGET_ABIS}]
            f.put(name, value)
        for row in f.receipt['targets'].values():
            path = f.root / row['receipt']['path']
            value = m.read_json(path.read_bytes())
            for name in ('producerReceipt', 'sourceManifest', 'captureReceipt'):
                value['inputs'][name] = copy.deepcopy(f.inputs[name])
            path.write_bytes(m.encoded(value))
            row['receipt']['sha256'] = f.hash(path.read_bytes())
        f.receipt.update(androidDeliveryProfile='android64', abis=['arm64-v8a', 'x86_64'])
        primary = m.read_json((f.root / f.receipt['targets'][m.PRIMARY_TARGET]['receipt']['path']).read_bytes())
        f.receipt['inputs'] = primary['inputs']
        self.rebuild_union()

    def rebuild_union(self):
        f, m = self.fixture, self.module
        targets = {target: {'data': (f.root / row['sbom']['path']).read_bytes(),
                            'receipt': f.root / row['receipt']['path']}
                   for target, row in f.receipt['targets'].items()}
        self.material = m.combined_evidence(targets, f.revision, 'android64')
        for key, raw in (('abiCoverage', self.material['coverage']),
                         ('unionNoticeManifest', self.material['notices'][0][1])):
            (f.root / f.receipt[key]['path']).write_bytes(raw)
            f.receipt[key]['sha256'] = f.hash(raw)
        f.receipt_path.write_bytes(m.encoded(f.receipt))

    def validate(self):
        f = self.fixture
        return self.module.load_selected_delivery(f.sbom, f.receipt_path, f.revision, f.lock, f.manifests, 'android64')

    def test_two_native_targets_reach_real_aggregate_with_current_source_identity(self):
        f, m = self.fixture, self.module
        material = self.validate()
        self.assertEqual({'aarch64-linux-android', 'x86_64-linux-android'}, set(material['targets']))
        profile = {'schema': 1, 'androidDeliveryProfile': 'android64', 'abis': ['arm64-v8a', 'x86_64'],
                   'targets': ['aarch64-linux-android', 'x86_64-linux-android']}
        (f.root / 'config').mkdir()
        (f.root / 'scripts').mkdir()
        for name in ('selected-sentinel-sbom.py', 'android-delivery-profile.py'):
            shutil.copy2(SCRIPTS / name, f.root / 'scripts' / name)
        (f.root / 'config/foxcore-android-delivery.json').write_text(json.dumps(profile))
        (f.root / 'config/foxcore-source-manifest.json').write_text(json.dumps(profile))
        blobs = {'native/sentinel-yara/Cargo.lock': f.lock,
                 'native/sentinel-yara/LICENSE': b'Original project license',
                 'native/sentinel-yara/README.md': b'Original scope',
                 'native/sentinel-yara/vendor/PROVENANCE.json': b'Original source provenance'}
        for name, path in {'foxhole-sentinel-yara': 'native/sentinel-yara/Cargo.toml',
                           'yara-x': 'native/sentinel-yara/vendor/yara-x/Cargo.toml',
                           'yara-x-parser': 'native/sentinel-yara/vendor/yara-x-parser/Cargo.toml'}.items():
            blobs[path] = f.manifests[name]
        generated = f.root / 'generated'
        args = SimpleNamespace(sentinel_sbom=f.sbom, sentinel_sbom_receipt=f.receipt_path,
                               core_delivery_profile='android64', android_abis='arm64-v8a x86_64')
        with patch.object(LEGACY.GENERATOR, 'REPO_ROOT', f.root), \
             patch.object(LEGACY.GENERATOR, 'git_blob', side_effect=lambda root, revision, name: blobs[name]), \
             patch.object(LEGACY.GENERATOR.subprocess, 'run', return_value=SimpleNamespace(stdout='')):
            produced_revision = LEGACY.GENERATOR.copy_sentinel_material(generated, args, f.root, f.revision)
        self.assertEqual(f.revision, produced_revision)
        self.assertEqual(2, len(list((generated / 'sentinel').glob('selected-*.cdx.json'))))
        self.assertEqual(material['coverage'], (generated / 'sentinel/ABI-COVERAGE.json').read_bytes())
        delivery = BASE.DeliverySbomTest()
        delivery.setUp()
        self.addCleanup(delivery.doCleanups)
        delivery.revision = f.revision
        (delivery.root / 'config/foxcore-revision.txt').write_text(f.revision + '\n')
        profile = {'schema': 1, 'androidDeliveryProfile': 'android64', 'abis': ['arm64-v8a', 'x86_64'],
                   'targets': ['aarch64-linux-android', 'x86_64-linux-android']}
        (delivery.root / 'config/foxcore-android-delivery.json').write_text(json.dumps(profile))
        delivery.inventory['MANIFEST.json'] = json.dumps({'foxcoreRevision': f.revision, **profile}).encode()
        delivery.inventory[BASE.SENTINEL_REVISION] = (f.revision + '\n').encode()
        del delivery.inventory[BASE.SENTINEL_BOM]
        for path in generated.rglob('*'):
            if path.is_file():
                delivery.inventory[path.relative_to(generated).as_posix()] = path.read_bytes()
        shutil.copy2(SCRIPTS / 'selected-sentinel-sbom.py', delivery.root / 'scripts/selected-sentinel-sbom.py')
        for name, body in list(delivery.libraries.items()):
            delivery.libraries[name.replace('arm64-v8a', 'x86_64')] = body
        delivery.write_inputs()
        core = json.loads(delivery.inventory['foxcore/foxcore-aarch64-linux-android.cdx.json'])
        # Core union has its own existing input-qualified fixtures. Exercise the
        # Sentinel path and the real aggregate API without repeating Core work.
        with patch.object(BASE.MODULE, 'android64_core_union', return_value=(copy.deepcopy(core), b'{}')):
            result = delivery.aggregate()
        sentinel = next(row for row in result['components'] if row['bom-ref'] == 'sentinel:root')
        self.assertEqual('pkg:generic/foxhole/foxhole-sentinel-yara@0.1.0?core_commit=' + f.revision, sentinel['purl'])
        props = {row['name']: row['value'] for row in result['metadata']['properties']}
        self.assertEqual(f.revision, props['foxhole:sentinelRevision'])
        self.assertIn('foxhole:sentinelBomSha256', props)
        self.assertNotIn('foxhole:sentinelSelectedUnionSha256', props)

    def test_extra_32_native_library_is_rejected_even_with_refreshed_receipt_hashes(self):
        f, m = self.fixture, self.module
        value = m.read_json((f.root / f.inputs['producerReceipt']['path']).read_bytes())
        value['libraries'].append({'path': 'x86/libfoxhole_sentinel_yara.so', 'bytes': 5, 'sha256': 'f' * 64})
        f.put('producerReceipt', value)
        for row in f.receipt['targets'].values():
            path = f.root / row['receipt']['path']; record = m.read_json(path.read_bytes())
            record['inputs']['producerReceipt'] = copy.deepcopy(f.inputs['producerReceipt'])
            path.write_bytes(m.encoded(record)); row['receipt']['sha256'] = f.hash(path.read_bytes())
        f.receipt['inputs'] = m.read_json((f.root / f.receipt['targets'][m.PRIMARY_TARGET]['receipt']['path']).read_bytes())['inputs']
        f.receipt_path.write_bytes(m.encoded(f.receipt))
        with self.assertRaisesRegex(ValueError, 'producer for delivery profile'):
            self.validate()

    def test_missing_target_cannot_be_called_complete(self):
        self.fixture.receipt['targets'].pop('x86_64-linux-android')
        self.fixture.receipt_path.write_bytes(self.module.encoded(self.fixture.receipt))
        with self.assertRaisesRegex(ValueError, 'target map|target receipts'):
            self.validate()

    def test_historical_four_is_a_distinct_profile(self):
        f = LEGACY.SelectedSentinelSbomTest(); f.setUp(); self.addCleanup(f.doCleanups)
        f.four_delivery()
        self.assertEqual(4, len(f.whole_validate()['targets']))
        with self.assertRaisesRegex(ValueError, 'android64 profile'):
            LEGACY.MODULE.load_selected_delivery(f.sbom, f.receipt_path, f.revision, f.lock, f.manifests, 'android64')

    def test_wrong_source_and_lock_cannot_reuse_current_native_evidence(self):
        f, m = self.fixture, self.module
        with self.assertRaisesRegex(ValueError, 'Four-target Sentinel delivery receipt required'):
            m.load_selected_delivery(f.sbom, f.receipt_path, "b" * 40, f.lock, f.manifests, 'android64')
        with self.assertRaisesRegex(ValueError, 'pinned Cargo.lock'):
            m.load_selected_delivery(f.sbom, f.receipt_path, f.revision, b"other locked graph", f.manifests, 'android64')

    def test_profile_mismatch_in_native_producer_is_rejected_after_rehash(self):
        f, m = self.fixture, self.module
        value = m.read_json((f.root / f.inputs['producerReceipt']['path']).read_bytes())
        value['androidDeliveryProfile'] = 'legacy4'
        f.put('producerReceipt', value)
        for row in f.receipt['targets'].values():
            path = f.root / row['receipt']['path']; record = m.read_json(path.read_bytes())
            record['inputs']['producerReceipt'] = copy.deepcopy(f.inputs['producerReceipt'])
            path.write_bytes(m.encoded(record)); row['receipt']['sha256'] = f.hash(path.read_bytes())
        f.receipt['inputs'] = m.read_json((f.root / f.receipt['targets'][m.PRIMARY_TARGET]['receipt']['path']).read_bytes())['inputs']
        f.receipt_path.write_bytes(m.encoded(f.receipt))
        with self.assertRaisesRegex(ValueError, 'android64 profile'):
            self.validate()

    def test_packaged_notice_mutation_is_rejected(self):
        material = self.validate()
        data = {'sentinel/ABI-COVERAGE.json': material['coverage']}
        data.update({'sentinel/selected-' + k + '.cdx.json': v for k, v in material['targets'].items()})
        data.update({'sentinel/cargo-notices/' + k: v for k, v in material['notices']})
        name = next(k for k in data if k.startswith('sentinel/cargo-notices/') and not k.endswith('MANIFEST.json'))
        data[name] += b'changed'
        with self.assertRaisesRegex(ValueError, 'notice bytes'):
            self.module.packaged_android64(data.__getitem__, self.fixture.revision)


if __name__ == '__main__':
    unittest.main()
