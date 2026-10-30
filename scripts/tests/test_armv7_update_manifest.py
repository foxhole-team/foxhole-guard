import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('guard_armv7_update_manifest', Path(__file__).resolve().parents[1] / 'generate-armv7-update-manifest.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class Armv7UpdateManifestTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.apk = Path(temporary.name) / 'owned.apk'

    def payload(self, abi='armeabi-v7a', missing=None, wrong_class=False):
        header = b'\x7fELF' + bytes([2 if wrong_class else 1, 1, 1]) + b'\0' * 9 + struct.pack('<HH', 3, 40)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name in MODULE.REQUIRED - ({missing} if missing else set()):
                archive.writestr('lib/' + abi + '/' + name, header)

    def tools(self, package='com.foxhole.guard', permission=True, certificate=None):
        return [subprocess.CompletedProcess([], 0, 'Signer #1 certificate SHA-256 digest: ' + (certificate or MODULE.CERTIFICATE) + '\n', ''),
                subprocess.CompletedProcess([], 0, "package: name='" + package + "' versionCode='9' versionName='0.1.2'\n" +
                    ("uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n" if permission else ''), '')]

    def test_manifest_binds_signed_public_armv7_bytes(self):
        self.payload()
        with patch.object(MODULE.subprocess, 'run', side_effect=self.tools()):
            value = MODULE.generate(self.apk, 'guard-armeabi-v7a.apk', 'v0.1.2', 'apksigner', 'aapt2', MODULE.CERTIFICATE)
        self.assertEqual('guard-armeabi-v7a.apk', value['apkName'])
        self.assertEqual(MODULE.hashlib.sha256(self.apk.read_bytes()).hexdigest(), value['apkSha256'])
        self.assertEqual('update-manifest-armeabi-v7a.json', MODULE.MANIFEST_NAME)

    def test_foreign_channel_package_and_certificate_are_refused(self):
        self.payload()
        for tools in [self.tools(package='com.foxhole.guard.private'), self.tools(permission=False), self.tools(certificate='a' * 64)]:
            with patch.object(MODULE.subprocess, 'run', side_effect=tools), self.assertRaises(ValueError):
                MODULE.generate(self.apk, 'guard.apk', 'v0.1.2', 'apksigner', 'aapt2', MODULE.CERTIFICATE)

    def test_missing_modules_wrong_abi_and_wrong_elf_class_are_refused(self):
        for options in [{'missing': 'libi2pd.so'}, {'abi': 'arm64-v8a'}, {'wrong_class': True}]:
            self.payload(**options)
            with patch.object(MODULE.subprocess, 'run', side_effect=self.tools()), self.assertRaises(ValueError):
                MODULE.generate(self.apk, 'guard.apk', 'v0.1.2', 'apksigner', 'aapt2', MODULE.CERTIFICATE)

    def test_asset_traversal_is_refused_before_tools(self):
        self.payload()
        with patch.object(MODULE.subprocess, 'run') as run, self.assertRaises(ValueError):
            MODULE.generate(self.apk, '../guard.apk', 'v0.1.2', 'apksigner', 'aapt2', MODULE.CERTIFICATE)
        run.assert_not_called()

if __name__ == '__main__':
    unittest.main()
