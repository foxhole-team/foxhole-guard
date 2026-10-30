import copy
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

BASE = load('armv7_sentinel_fixture', HERE / 'test_sentinel_android64_delivery.py')
ARM = {'androidDeliveryProfile': 'android-armv7', 'abis': ['armeabi-v7a'], 'targets': ['armv7-linux-androideabi']}
TARGET = 'armv7-linux-androideabi'

class SentinelArmv7DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BASE.LEGACY.SelectedSentinelSbomTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.four_delivery()
        self.module = BASE.LEGACY.MODULE
        fixture, module = self.fixture, self.module
        fixture.receipt['targets'] = {TARGET: fixture.receipt['targets'][TARGET]}
        row = fixture.receipt['targets'][TARGET]
        target_path = fixture.root / row['receipt']['path']
        target = module.read_json(target_path.read_bytes())
        for name in ('producerReceipt', 'sourceManifest', 'captureReceipt'):
            record = target['inputs'][name]
            value = module.read_json((fixture.root / record['path']).read_bytes())
            value.update(ARM)
            if name == 'producerReceipt':
                value['libraries'] = [library for library in value['libraries'] if library['path'].startswith('armeabi-v7a/')]
            if name == 'captureReceipt':
                value['producerReceiptSha256'] = fixture.inputs['producerReceipt']['sha256']
                value['steps'] = [step for step in value['steps'] if step['label'] == 'sbom-' + TARGET]
            fixture.put(name, value)
            target['inputs'][name] = copy.deepcopy(fixture.inputs[name])
        target_path.write_bytes(module.encoded(target))
        row['receipt']['sha256'] = fixture.hash(target_path.read_bytes())
        fixture.sbom.write_bytes((fixture.root / row['sbom']['path']).read_bytes())
        fixture.receipt.update(androidDeliveryProfile='android-armv7', abis=['armeabi-v7a'], target=TARGET,
                               inputs=target['inputs'], sbomSha256=fixture.hash(fixture.sbom.read_bytes()))
        self.rebuild()

    def rebuild(self):
        fixture, module = self.fixture, self.module
        targets = {target: {'data': (fixture.root / row['sbom']['path']).read_bytes(),
                            'receipt': fixture.root / row['receipt']['path']}
                   for target, row in fixture.receipt['targets'].items()}
        self.material = module.combined_evidence(targets, fixture.revision, 'android-armv7')
        for key, raw in (('abiCoverage', self.material['coverage']), ('unionNoticeManifest', self.material['notices'][0][1])):
            (fixture.root / fixture.receipt[key]['path']).write_bytes(raw)
            fixture.receipt[key]['sha256'] = fixture.hash(raw)
        fixture.receipt_path.write_bytes(module.encoded(fixture.receipt))

    def validate(self):
        fixture = self.fixture
        return self.module.load_selected_delivery(fixture.sbom, fixture.receipt_path, fixture.revision,
                                                 fixture.lock, fixture.manifests, 'android-armv7')

    def notices(self):
        result = {'sentinel/ABI-COVERAGE.json': self.material['coverage']}
        for target, raw in self.material['targets'].items():
            result['sentinel/selected-' + target + '.cdx.json'] = raw
        for name, raw in self.material['notices']:
            result['sentinel/cargo-notices/' + name] = raw
        return result

    def test_exact_armv7_selected_graph_and_packaged_notices_round_trip(self):
        self.validate()
        data = self.notices()
        document, digest = self.module.packaged_delivery(data.__getitem__, self.fixture.revision, 'android-armv7')
        self.assertEqual('foxhole-sentinel-yara', document['metadata']['component']['name'])
        self.assertEqual(self.fixture.hash(self.material['coverage']), digest)

    def test_armv7_packaged_profile_refuses_android64_or_foreign_target(self):
        data = self.notices()
        with self.assertRaises(ValueError):
            self.module.packaged_android64(data.__getitem__, self.fixture.revision)
        coverage = self.module.read_json(data['sentinel/ABI-COVERAGE.json'])
        coverage['targets']['i686-linux-android'] = coverage['targets'][TARGET]
        data['sentinel/ABI-COVERAGE.json'] = self.module.encoded(coverage)
        with self.assertRaises(ValueError):
            self.module.packaged_delivery(data.__getitem__, self.fixture.revision, 'android-armv7')

    def test_armv7_coverage_cannot_change_the_primary_target_or_notice_body(self):
        data = self.notices()
        coverage = self.module.read_json(data['sentinel/ABI-COVERAGE.json'])
        coverage['primaryTarget'] = 'aarch64-linux-android'
        data['sentinel/ABI-COVERAGE.json'] = self.module.encoded(coverage)
        with self.assertRaises(ValueError):
            self.module.packaged_delivery(data.__getitem__, self.fixture.revision, 'android-armv7')
        data = self.notices()
        name = next(name for name in data if name.startswith('sentinel/cargo-notices/') and not name.endswith('MANIFEST.json'))
        data[name] += b'changed'
        with self.assertRaises(ValueError):
            self.module.packaged_delivery(data.__getitem__, self.fixture.revision, 'android-armv7')

if __name__ == '__main__':
    unittest.main()
