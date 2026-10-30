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


class SentinelAndroid3DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = LEGACY.SelectedSentinelSbomTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.four_delivery()
        self.module = LEGACY.MODULE
        self.convert_to_current()

    def convert_to_current(self):
        f, m = self.fixture, self.module
        f.receipt['targets'] = {k: v for k, v in f.receipt['targets'].items() if k in m.delivery_targets('android3')}
        shared = next(iter(f.receipt['targets'].values()))
        original = m.read_json((f.root / shared['receipt']['path']).read_bytes())
        for name in ('producerReceipt', 'sourceManifest', 'captureReceipt'):
            record = original['inputs'][name]
            value = m.read_json((f.root / record['path']).read_bytes())
            value.update(androidDeliveryProfile='android3', abis=['arm64-v8a', 'armeabi-v7a', 'x86_64'], targets=list(m.delivery_targets('android3')))
            if name == 'producerReceipt':
                value['libraries'] = [r for r in value['libraries'] if r['path'].split('/')[0] in ('arm64-v8a', 'armeabi-v7a', 'x86_64')]
            if name == 'captureReceipt':
                value['producerReceiptSha256'] = f.inputs['producerReceipt']['sha256']
                value['steps'] = [r for r in value['steps'] if r['label'] in {'sbom-' + t for t in m.delivery_targets('android3')}]
            f.put(name, value)
        for row in f.receipt['targets'].values():
            path = f.root / row['receipt']['path']
            value = m.read_json(path.read_bytes())
            for name in ('producerReceipt', 'sourceManifest', 'captureReceipt'):
                value['inputs'][name] = copy.deepcopy(f.inputs[name])
            path.write_bytes(m.encoded(value))
            row['receipt']['sha256'] = f.hash(path.read_bytes())
        f.receipt.update(androidDeliveryProfile='android3', abis=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
        primary = m.read_json((f.root / f.receipt['targets'][m.PRIMARY_TARGET]['receipt']['path']).read_bytes())
        f.receipt['inputs'] = primary['inputs']
        self.rebuild_union()

    def rebuild_union(self):
        f, m = self.fixture, self.module
        targets = {target: {'data': (f.root / row['sbom']['path']).read_bytes(),
                            'receipt': f.root / row['receipt']['path']}
                   for target, row in f.receipt['targets'].items()}
        self.material = m.combined_evidence(targets, f.revision, 'android3')
        for key, raw in (('abiCoverage', self.material['coverage']),
                         ('unionNoticeManifest', self.material['notices'][0][1])):
            (f.root / f.receipt[key]['path']).write_bytes(raw)
            f.receipt[key]['sha256'] = f.hash(raw)
        f.receipt_path.write_bytes(m.encoded(f.receipt))

    def validate(self):
        f = self.fixture
        return self.module.load_selected_delivery(f.sbom, f.receipt_path, f.revision, f.lock, f.manifests, 'android3')

    def test_three_target_selected_and_packaged_round_trip(self):
        material = self.validate()
        self.assertEqual({'aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android'}, set(material['targets']))
        notices = {'sentinel/ABI-COVERAGE.json': material['coverage']}
        for target, raw in material['targets'].items():
            notices['sentinel/selected-' + target + '.cdx.json'] = raw
        for name, raw in material['notices']:
            notices['sentinel/cargo-notices/' + name] = raw
        self.module.packaged_delivery(notices.__getitem__, self.fixture.revision, 'android3')
        with self.assertRaises(ValueError):
            self.module.packaged_android64(notices.__getitem__, self.fixture.revision)

    def test_three_target_inventory_never_accepts_a_missing_arm32_target(self):
        self.fixture.receipt['targets'].pop('armv7-linux-androideabi')
        self.fixture.receipt_path.write_bytes(self.module.encoded(self.fixture.receipt))
        with self.assertRaises(ValueError):
            self.validate()

if __name__ == '__main__':
    unittest.main()
