import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

BASE = load('android3_core_fixtures', HERE / 'test_android64_delivery.py')
PROFILE = load('android3_profile', HERE.parent / 'android-delivery-profile.py')
SHAPE = {'androidDeliveryProfile': 'android3', 'abis': ['arm64-v8a', 'armeabi-v7a', 'x86_64'],
    'targets': ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android']}

class Android3DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BASE.Android64DeliveryTest()
        with patch.object(BASE, 'SHAPE', SHAPE):
            self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_three_target_graphs_preserve_source_and_each_native_binding(self):
        self.assertEqual(self.fixture.sbom.read_bytes(), self.fixture.inventory())
        self.assertEqual(SHAPE, PROFILE.profile_shape('android3'))
        with patch.object(BASE, 'SHAPE', SHAPE):
            fixture = self.fixture.delivery_fixture()
        document = fixture.aggregate()
        properties = {row['name']: row['value'] for row in document['metadata']['properties']}
        self.assertEqual('android3', properties['foxhole:androidDeliveryProfile'])
        self.assertEqual('arm64-v8a,armeabi-v7a,x86_64', properties['foxhole:androidAbis'])
        fixture.libraries['lib/armeabi-v7a/libfoxhole_sentinel_yara.so'] += b'tampered'
        fixture.write_inputs()
        with self.assertRaisesRegex(ValueError, 'Sentinel ELF differs'):
            fixture.aggregate()

    def test_missing_arm32_target_and_relabelled_64_source_are_refused(self):
        self.fixture.receipt['targets'].pop('armv7-linux-androideabi')
        self.fixture.save_receipt()
        with self.assertRaises(ValueError):
            self.fixture.inventory()
        for profile in ('android64', 'android-armv7', 'legacy4'):
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                PROFILE.require_fields(SHAPE, profile)

    def test_no_x86_missing_or_duplicate_native_authorities_are_admitted(self):
        for key, value in [('abis', ['arm64-v8a', 'x86_64']),
                ('abis', SHAPE['abis'] + ['x86']), ('abis', SHAPE['abis'] + ['armeabi-v7a']),
                ('targets', SHAPE['targets'] + ['i686-linux-android'])]:
            record = copy.deepcopy(SHAPE)
            record[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                PROFILE.require_fields(record, 'android3')

    def test_gradle_reserves_exact_three_abi_profile_for_fdroid(self):
        text = (HERE.parents[1] / 'app/build.gradle.kts').read_text()
        self.assertIn('shippedAndroidAbis.toSet() != setOf("arm64-v8a", "armeabi-v7a", "x86_64") || appUpdateChannel == "fdroid"', text)
        self.assertIn('setOf("arm64-v8a", "armeabi-v7a", "x86_64") -> "android3"', text)

if __name__ == '__main__':
    unittest.main()
