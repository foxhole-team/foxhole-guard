#!/usr/bin/env python3
"""Keep the SQLCipher delivery graph and original publisher notice coherent."""
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


class SqlCipherDeliveryContractTest(unittest.TestCase):
    def test_selected_version_and_native_admission_are_coherent(self):
        GENERATOR.require_android_pins()
        for name in ['app/build.gradle.kts', 'scripts/package-release-candidate.sh']:
            text = (ROOT / name).read_text()
            self.assertIn('pkg:maven/net.zetetic/sqlcipher-android@4.19.1', text)
            self.assertNotIn('pkg:maven/net.zetetic/sqlcipher-android@4.17.0', text)

    def test_previous_pin_cannot_silently_use_new_license_inventory(self):
        with tempfile.TemporaryDirectory() as owned:
            fixture = Path(owned)
            (fixture / 'gradle').mkdir()
            (fixture / 'gradle/libs.versions.toml').write_text(
                'sqlcipher = "4.17.0"\nlazysodium = "5.2.0"\njna = "5.19.1"\n'
            )
            with patch.object(GENERATOR, 'REPO_ROOT', fixture):
                with self.assertRaisesRegex(SystemExit, 'Android dependency pins changed'):
                    GENERATOR.require_android_pins()

    def test_new_artifact_bytes_are_strict_and_historical_entries_remain(self):
        root = ET.parse(ROOT / 'gradle/verification-metadata.xml').getroot()
        ns = {'v': root.tag.partition('}')[0].lstrip('{')}
        components = root.findall('v:components/v:component', ns)
        selected = next(item for item in components if item.attrib == {
            'group': 'net.zetetic', 'name': 'sqlcipher-android', 'version': '4.19.1'
        })
        aar = next(item for item in selected if item.attrib['name'] == 'sqlcipher-android-4.19.1.aar')
        self.assertEqual(aar[0].attrib['value'], '43fc10cae8f1f1059f0933e8942a1eb328989d13426378597cd4add1b34a34fd')
        self.assertTrue(any(item.attrib == {
            'group': 'net.zetetic', 'name': 'sqlcipher-android', 'version': '4.17.0'
        } for item in components))
        self.assertTrue(any(item.attrib == {
            'group': 'androidx.sqlite', 'name': 'sqlite-android', 'version': '2.7.0'
        } for item in components))

    def test_original_publisher_license_is_preserved(self):
        data = (ROOT / 'third_party/licenses/SQLCipher-BSD-3-Clause.txt').read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), '09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14')
        self.assertIn('tag `v4.19.1`', (ROOT / 'third_party/licenses/README.md').read_text())


if __name__ == '__main__':
    unittest.main()
