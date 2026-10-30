import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(os.environ.get("FOXHOLE_RELEASE_TEST_ROOT", Path(__file__).resolve().parents[2]))
PACKAGER = Path(os.environ.get("FOXHOLE_RELEASE_TEST_PACKAGER", ROOT / "scripts/package-release-candidate.sh"))
BUILDER = Path(os.environ.get("FOXHOLE_RELEASE_TEST_BUILDER", ROOT / "scripts/build-release-variants.sh"))
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]
CORE = (ROOT / "config/foxcore-revision.txt").read_text().strip()


@unittest.skipUnless(shutil.which("jq"), "jq is required by the release matrix")
class FourAbiReleaseInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.work = Path(self.directory.name)

    def shell(self, body, *args):
        return subprocess.run(["bash", "-c", 'source "$1"; ' + body, "fixture", str(PACKAGER), *map(str, args)],
                              cwd=ROOT, text=True, capture_output=True)

    def test_primary_and_fdroid_matrix_require_all_four_abis_without_new_asset_names(self):
        result = self.shell('RELEASE_TAG=v1.0.0; release_matrix_names; matrix_artifacts')
        self.assertEqual(0, result.returncode, result.stderr)
        matrix = json.loads(result.stdout)
        self.assertEqual(["android4", "android-armv7", "android4"], [row["profile"] for row in matrix])
        self.assertEqual(["github", "github", "fdroid"], [row["updateChannel"] for row in matrix])
        self.assertEqual(["FoxHole-v1.0.0-arm64-v8a-release.apk", "FoxHole-v1.0.0-armeabi-v7a-release.apk",
                          "FoxHole-v1.0.0-universal-fdroid.apk"], [row["apk"] for row in matrix])

    def verify_apk(self, abis, certificate="0" * 64):
        apk = self.work / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("badging", "package: name='com.foxhole.guard' versionCode='174' versionName='1.0.0'\n"
                             + "native-code: " + " ".join("'" + abi + "'" for abi in abis))
            archive.writestr("certificate", certificate)
        signer = self.work / "apksigner"
        signer.write_text('''#!/usr/bin/env python3
import sys, zipfile
with zipfile.ZipFile(sys.argv[-1]) as archive:
    print("Verified using v2 scheme (APK Signature Scheme v2): true")
    print("Signer #1 certificate SHA-256 digest: " + archive.read("certificate").decode())
''')
        aapt = self.work / "aapt2"
        aapt.write_text('''#!/usr/bin/env python3
import sys, zipfile
if sys.argv[-2] == "permissions":
    print("uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'")
else:
    with zipfile.ZipFile(sys.argv[-1]) as archive:
        print(archive.read("badging").decode())
''')
        signer.chmod(0o755)
        aapt.chmod(0o755)
        # Crypto tool output is a fixture; real ABI, certificate comparison and channel gates execute.
        return self.shell('VERSION_CODE=174; VERSION_NAME=1.0.0; verify_fdroid_signing_blocks() { :; }; '
                          'verify_apk "$2" "$3" "' + "0" * 64 + '" "$4" "$5" github android4',
                          apk, self.work / "certs", signer, aapt)

    def test_exact_four_abi_apk_is_accepted(self):
        result = self.verify_apk(ABIS)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rehashed_or_renamed_three_abi_apk_cannot_omit_any_target(self):
        for absent in ABIS:
            with self.subTest(absent=absent):
                result = self.verify_apk([abi for abi in ABIS if abi != absent])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Native ABI set differs", result.stderr)

    def test_four_abi_profile_preserves_certificate_refusal(self):
        result = self.verify_apk(ABIS, certificate="1" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("certificate mismatch", result.stderr)

    def license_archive(self, omitted=None):
        names = ['FoxHole-Guard-GPL-3.0-or-later.txt', 'THIRD_PARTY_NOTICES.md',
                 'android/JNA-Apache-2.0.txt', 'android/JNA-LGPL-2.1.txt', 'android/JNA-LICENSE.txt',
                 'android/SQLCipher-BSD-3-Clause.txt', 'android/lazysodium-MPL-2.0.txt', 'android/libsodium-ISC.txt',
                 'flags/app-assets.sha256', 'flags/flag-icons-LICENSE.txt', 'fonts/JetBrainsMono-OFL.txt',
                 'fonts/Tiny5-OFL.txt', 'foxcore/FoxHole-Core-GPL-3.0-or-later.txt', 'foxcore/THIRD_PARTY_NOTICES.md',
                 'i2pd/Android-NDK-29-NOTICE.toolchain.txt', 'i2pd/Android-NDK-29-NOTICE.txt',
                 'i2pd/Boost-1.84.0-BSL-1.0.txt', 'i2pd/OpenSSL-3.5.8-Apache-2.0.txt', 'i2pd/i2pd-BSD-3-Clause.txt',
                 'icons/Tabler-MIT.txt', 'tor-config/PROVENANCE.txt', 'tor-config/assets.sha256',
                 'tor-config/upstream-members/README.CONJURE.md', 'tor-config/upstream-members/pt_config.json',
                 'tor-config/upstream-members/torrc-defaults.txt', 'tor/GO-MODULES.json',
                 'tor/Go-1.26.10-BSD-3-Clause.txt', 'tor/conjure-client-BSD-3-Clause.txt',
                 'tor/lyrebird-BSD-3-Clause.txt', 'tor/lyrebird-GPL-3.0-or-later.txt']
        files = {name: b"synthetic license body" for name in names}
        files.update({"foxcore/foxcore-" + target + ".cdx.json": b"{}" for target in TARGETS})
        if omitted:
            del files[omitted]
        manifest = {"schema": 1, "foxcoreRevision": CORE, "androidDeliveryProfile": "android4",
                    "abis": ABIS, "targets": TARGETS,
                    "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                              for name, data in sorted(files.items())]}
        files["MANIFEST.json"] = json.dumps(manifest).encode()
        files["SHA256SUMS"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n"
                                       for name, data in sorted(files.items())).encode()
        path = self.work / "licenses.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in files.items():
                entry = zipfile.ZipInfo(name)
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, data)
        return path

    def test_four_target_license_inventory_is_accepted(self):
        result = self.shell('CORE_REVISION="$3"; verify_license_notices_archive "$2" android4',
                            self.license_archive(), CORE)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_removing_x86_notice_is_refused_even_with_consistent_rewritten_hashes(self):
        name = "foxcore/foxcore-i686-linux-android.cdx.json"
        result = self.shell('CORE_REVISION="$3"; verify_license_notices_archive "$2" android4',
                            self.license_archive(omitted=name), CORE)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(name, result.stderr)


class FourAbiBuilderFixtureTest(unittest.TestCase):
    def setUp(self):
        # This isolated Git fixture is executed by Root, which owns Git operations.
        spec = importlib.util.spec_from_file_location("release_fixture", ROOT / "scripts/tests/test_release_variants.py")
        self.fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.fixture)
        self.fixture.ReleaseBuildTest.setUp(self)
        source = json.loads((self.root / "config/foxcore-source-manifest.json").read_text())
        shape = {"androidDeliveryProfile": "android4", "abis": ABIS, "targets": TARGETS}
        (self.root / "config/foxcore-source-manifest.json").write_text(json.dumps({**source, **shape}))
        (self.root / "config/foxcore-android-delivery.json").write_text(json.dumps({"schema": 1, **shape}))
        snapshot = self.root / "third_party/licenses/source-legal/android4/snapshot.json"
        snapshot.parent.mkdir(parents=True)
        snapshot.write_text(json.dumps({"coreCommit": source["commit"], **shape,
                                       "compiledInputs": {row["path"]: row["sha256"] for row in source["files"]}}))
        self.git(self.root, "add", ".")
        self.git(self.root, "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "four-ABI fixture")

    def git(self, root, *args):
        return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout

    def test_primary_fdroid_and_public_preflight_keep_all_four_abis(self):
        before = (self.root / "config/foxcore-source-manifest.json").read_bytes()
        result = subprocess.run(["bash", str(BUILDER), "172"], cwd=self.root,
                                env=self.environment, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        for lane, expected in [("github", "github/android4"), ("github-armv7", "github/android-armv7"), ("fdroid", "fdroid/android4")]:
            self.assertEqual(expected, (self.root / "build/release-variants" / lane / "app.apk").read_text())
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(4, len(calls))
        for index in [0, 2, 3]:
            self.assertEqual("android4", calls[index]["profile"])
            self.assertIn("-Pfoxhole.abis=arm64-v8a armeabi-v7a x86 x86_64", calls[index]["args"])
        self.assertTrue(all(not Path(call["cwd"]).exists() for call in calls))
        self.assertEqual(before, (self.root / "config/foxcore-source-manifest.json").read_bytes())
        self.assertEqual("", self.git(self.root, "diff", "--name-only", "HEAD"))


if __name__ == "__main__":
    unittest.main()
