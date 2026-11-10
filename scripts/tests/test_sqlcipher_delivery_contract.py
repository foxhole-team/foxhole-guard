#!/usr/bin/env python3
"""Keep SQLCipher delivery identities, strict publisher bytes and notices coherent."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('license_generator', ROOT / 'scripts/generate-license-assets.py')
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

SQLCIPHER_420_ARTIFACTS = {
    'sqlcipher-android-4.20.0.aar': '1e57857adfd29e2f5ddb4686e1e5ec6659920220d4bf182fdb5ef085b5577100',
    'sqlcipher-android-4.20.0.module': '92a4483933d8406e4de4698e28091c58845579895f897fc522d3ff05df6b37f8',
    'sqlcipher-android-4.20.0.pom': '9d882f462c6fb4063b1c3bfb3df913c5929bf8cf73d7f6787f341672f23c1549',
    'sqlcipher-android-4.20.0-sources.jar': 'c6ed30b1c34df6a8538eb58b58301687322a8bd079a359dc5734fbad37159c51',
}


class SqlCipherDeliveryContractTest(unittest.TestCase):
    def test_selected_version_and_native_admission_are_coherent(self):
        GENERATOR.require_android_pins()
        for name in ['app/build.gradle.kts', 'scripts/package-release-candidate.sh']:
            text = (ROOT / name).read_text()
            self.assertTrue('pkg:maven/net.zetetic/sqlcipher-android@4.20.0' in text,
                            f'{name}: missing current SQLCipher delivery identity')
            for old in ['4.17.0', '4.19.1']:
                self.assertFalse(f'pkg:maven/net.zetetic/sqlcipher-android@{old}' in text,
                                 f'{name}: stale SQLCipher delivery identity')

    def test_previous_pin_cannot_silently_use_new_license_inventory(self):
        for version in ['4.17.0', '4.19.1', '4.20.1']:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as owned:
                fixture = Path(owned)
                (fixture / 'gradle').mkdir()
                (fixture / 'gradle/libs.versions.toml').write_text(
                    f'sqlcipher = "{version}"\nlazysodium = "5.2.0"\njna = "5.19.1"\n'
                )
                with patch.object(GENERATOR, 'REPO_ROOT', fixture):
                    with self.assertRaisesRegex(SystemExit, 'Android dependency pins changed'):
                        GENERATOR.require_android_pins()

    def test_new_artifact_bytes_are_strict_and_historical_entries_remain(self):
        root = ET.parse(ROOT / 'gradle/verification-metadata.xml').getroot()
        ns = {'v': root.tag.partition('}')[0].lstrip('{')}
        components = root.findall('v:components/v:component', ns)
        sqlcipher = {item.attrib['version']: item for item in components if
                     item.attrib.get('group') == 'net.zetetic' and
                     item.attrib.get('name') == 'sqlcipher-android'}
        selected = sqlcipher['4.20.0']
        self.assertEqual({item.attrib['name'] for item in selected}, set(SQLCIPHER_420_ARTIFACTS))
        for artifact in selected:
            self.assertEqual(len(artifact), 1)
            digest = artifact[0]
            self.assertEqual(digest.tag, f'{{{ns["v"]}}}sha256')
            self.assertEqual(digest.attrib['value'], SQLCIPHER_420_ARTIFACTS[artifact.attrib['name']])
            self.assertEqual(digest.attrib['origin'], 'Verified Maven Central publisher SHA-512')
        previous = sqlcipher['4.19.1']
        self.assertEqual(len(previous), 4)
        aar = next(item for item in previous if item.attrib['name'] == 'sqlcipher-android-4.19.1.aar')
        self.assertEqual(aar[0].attrib['value'], '43fc10cae8f1f1059f0933e8942a1eb328989d13426378597cd4add1b34a34fd')
        self.assertIn('4.17.0', sqlcipher)
        self.assertTrue(any(item.attrib == {
            'group': 'androidx.sqlite', 'name': 'sqlite-android', 'version': '2.7.0'
        } for item in components))

    def test_original_publisher_license_is_preserved(self):
        data = (ROOT / 'third_party/licenses/SQLCipher-BSD-3-Clause.txt').read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), '09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14')
        readme = (ROOT / 'third_party/licenses/README.md').read_text()
        self.assertTrue('tag `v4.20.0`' in readme, 'current publisher license tag')
        self.assertTrue('94bdf2589942b11a53b7d9a005d5c1e66920d4e8' in readme, 'exact current publisher source')
        self.assertTrue('7167addca133ce72d9cb791929d2e168d9204bc2' in readme, 'actual publisher LICENSE Git blob')

    def test_human_inventory_matches_current_catalog(self):
        inventory = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text()
        self.assertTrue('SQLCipher Android 4.20.0' in inventory)
        self.assertFalse('SQLCipher Android 4.19.1' in inventory)


if __name__ == '__main__':
    unittest.main()
