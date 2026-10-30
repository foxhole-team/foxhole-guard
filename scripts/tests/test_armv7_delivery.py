import copy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

BASE = load('armv7_core_fixtures', HERE / 'test_android64_delivery.py')
PROFILE = load('armv7_profile', HERE.parent / 'android-delivery-profile.py')
ARM = {'androidDeliveryProfile': 'android-armv7', 'abis': ['armeabi-v7a'], 'targets': ['armv7-linux-androideabi']}

class Armv7DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BASE.Android64DeliveryTest()
        with patch.object(BASE, 'SHAPE', ARM):
            self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_selected_armv7_graph_keeps_current_source_and_native_binding(self):
        self.assertEqual(self.fixture.sbom.read_bytes(), self.fixture.inventory())
        self.assertEqual(ARM, PROFILE.profile_shape('android-armv7'))
        self.assertEqual('armv7-linux-androideabi', BASE.FORK_FIXTURE.selected.primary_target('android-armv7'))

    def test_generated_core_notices_use_only_the_armv7_primary_name(self):
        fixture = self.fixture
        blobs = {'Cargo.toml': b'[workspace.package]\nversion="1.0.0"\n', 'Cargo.lock': fixture.lock,
                 'LICENSE': b'Original Core license', 'THIRD_PARTY_NOTICES.md': b'Original Core notices'}
        generated = fixture.root / 'generated'
        fixture.args.foxcore_sbom = fixture.args.foxcore_sbom.resolve()
        fixture.args.foxcore_sbom_receipt = fixture.args.foxcore_sbom_receipt.resolve()
        with patch.object(BASE.GENERATOR, 'REPO_ROOT', fixture.app), \
             patch.object(BASE.GENERATOR, 'git_blob', side_effect=lambda root, revision, name: blobs[name]):
            BASE.GENERATOR.copy_core_material(generated, fixture.args, fixture.root, BASE.REVISION)
        self.assertEqual(fixture.sbom.read_bytes(), (generated / 'foxcore/foxcore-armv7-linux-androideabi.cdx.json').read_bytes())
        self.assertFalse((generated / 'foxcore/foxcore-aarch64-linux-android.cdx.json').exists())

    def test_armv7_cannot_use_the_committed_arm64_inventory_as_fallback(self):
        self.fixture.args.foxcore_sbom = None
        self.fixture.args.foxcore_sbom_receipt = None
        with self.assertRaisesRegex(ValueError, 'ARMv7 requires its selected'):
            self.fixture.inventory()

    def test_armv7_full_aggregate_binds_both_core_and_sentinel_native_bytes(self):
        with patch.object(BASE, 'SHAPE', ARM):
            fixture = self.fixture.delivery_fixture()
        document = fixture.aggregate()
        properties = {row['name']: row['value'] for row in document['metadata']['properties']}
        self.assertEqual('android-armv7', properties['foxhole:androidDeliveryProfile'])
        self.assertEqual('armeabi-v7a', properties['foxhole:androidAbis'])
        self.assertEqual('armv7-linux-androideabi', properties['foxhole:target'])
        fixture.libraries['lib/armeabi-v7a/libfoxhole_sentinel_yara.so'] += b'changed'
        fixture.write_inputs()
        with self.assertRaisesRegex(ValueError, 'Sentinel ELF differs'):
            fixture.aggregate()

    def test_armv7_never_falls_back_to_unconfigured_legacy_or_wrong_abis(self):
        config = self.fixture.app / 'config/foxcore-android-delivery.json'
        original = config.read_bytes()
        config.unlink()
        with self.assertRaises(ValueError):
            self.fixture.inventory()
        config.write_bytes(original)
        for abis in ('arm64-v8a', 'armeabi-v7a x86', 'armeabi-v7a arm64-v8a', 'armeabi-v7a armeabi-v7a'):
            self.fixture.args.android_abis = abis
            with self.subTest(abis=abis), self.assertRaises(ValueError):
                self.fixture.inventory()

    def test_armv7_rejects_missing_source_profile_and_additional_target(self):
        pins = self.fixture.app / 'config/foxcore-source-manifest.json'
        original = pins.read_bytes()
        record = json.loads(original)
        record.pop('androidDeliveryProfile')
        pins.write_bytes(BASE.encoded(record))
        with self.assertRaises(ValueError):
            self.fixture.inventory()
        pins.write_bytes(original)
        self.fixture.receipt['targets']['i686-linux-android'] = copy.deepcopy(self.fixture.receipt['targets']['armv7-linux-androideabi'])
        self.fixture.save_receipt()
        with self.assertRaises(ValueError):
            self.fixture.inventory()

    def test_armv7_rejects_64bit_authority_relabel_even_with_updated_container_hash(self):
        case = self.fixture.cases['armv7-linux-androideabi']
        producer = self.fixture.input_value(case, 'producerReceipt')
        producer.update(BASE.SHAPE)
        case.put('producerReceipt', producer)
        target_receipt = json.loads(case.receipt_path.read_bytes())
        target_receipt['inputs'] = case.inputs
        case.receipt_path.write_bytes(BASE.encoded(target_receipt))
        self.fixture.receipt['targets']['armv7-linux-androideabi']['receipt']['sha256'] = BASE.sha(case.receipt_path.read_bytes())
        self.fixture.receipt['inputs'] = case.inputs
        self.fixture.save_receipt()
        with self.assertRaises(ValueError):
            self.fixture.inventory()

    def test_existing_android64_and_legacy_shapes_remain_distinct(self):
        self.assertEqual(['arm64-v8a', 'x86_64'], PROFILE.profile_shape('android64')['abis'])
        self.assertEqual(['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], PROFILE.profile_shape('legacy4')['abis'])
        for profile in ('android64', 'legacy4'):
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                PROFILE.require_fields(ARM, profile)

if __name__ == '__main__':
    unittest.main()
