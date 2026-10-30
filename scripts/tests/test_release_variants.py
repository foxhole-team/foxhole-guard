import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import urllib.parse
import zipfile
from pathlib import Path


ROOT = Path(os.environ.get("FOXHOLE_RELEASE_TEST_ROOT", Path(__file__).resolve().parents[2]))
PACKAGER = Path(os.environ.get("FOXHOLE_RELEASE_TEST_PACKAGER", ROOT / "scripts/package-release-candidate.sh"))
BUILDER = Path(os.environ.get("FOXHOLE_RELEASE_TEST_BUILDER", ROOT / "scripts/build-release-variants.sh"))
COMMIT = "a" * 40
TREE = "b" * 40
CORE = (ROOT / "config/foxcore-revision.txt").read_text().strip()
GH_APK = "FoxHole-v1.0.0-arm64-v8a-release.apk"
ARMV7_APK = "FoxHole-v1.0.0-armeabi-v7a-release.apk"
FDROID_APK = "FoxHole-v1.0.0-universal-fdroid.apk"
GH_SBOM = "FoxHole-v1.0.0-sbom.cdx.json"
ARMV7_SBOM = "FoxHole-v1.0.0-armeabi-v7a-sbom.cdx.json"
FDROID_SBOM = "FoxHole-v1.0.0-fdroid-sbom.cdx.json"
LICENSES = "FoxHole-v1.0.0-license-notices.zip"
ARMV7_LICENSES = "FoxHole-v1.0.0-armeabi-v7a-license-notices.zip"
FDROID_LICENSES = "FoxHole-v1.0.0-fdroid-license-notices.zip"
INSTALL_PERMISSION = "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'"


def executable(path, source):
    path.write_text(source)
    path.chmod(0o755)


class ReleaseBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "app"
        self.root.mkdir()
        core = self.base / "core"
        core.mkdir()
        (core / "Cargo.toml").write_text('synthetic source fixture')
        self.git(core, "init", "-q")
        self.git(core, "add", ".")
        self.git(core, "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        pin = self.git(core, "rev-parse", "HEAD").strip()
        (self.root / "config").mkdir()
        (self.root / "config/foxcore-revision.txt").write_text(pin)
        files = [{"path": f"src/{n}.rs", "sha256": hashlib.sha256(str(n).encode()).hexdigest()} for n in range(522)]
        shapes = {"android4": (["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]),
                  "android64": (["arm64-v8a", "x86_64"], ["aarch64-linux-android", "x86_64-linux-android"]),
                  "android-armv7": (["armeabi-v7a"], ["armv7-linux-androideabi"]),
                  "android3": (["arm64-v8a", "armeabi-v7a", "x86_64"], ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"])}
        for profile, (abis, targets) in shapes.items():
            snapshot = self.root / "third_party/licenses/source-legal" / profile / "snapshot.json"
            snapshot.parent.mkdir(parents=True)
            snapshot.write_text(json.dumps({"coreCommit": pin, "androidDeliveryProfile": profile, "abis": abis, "targets": targets,
                                           "compiledInputs": {row["path"]: row["sha256"] for row in files}}))
        shape = {"androidDeliveryProfile": "android4", "abis": shapes["android4"][0], "targets": shapes["android4"][1]}
        (self.root / "config/foxcore-android-delivery.json").write_text(json.dumps({"schema": 1, **shape}))
        (self.root / "config/foxcore-source-manifest.json").write_text(json.dumps({"commit": pin, "files": files, **shape}))
        executable(self.root / "gradlew", '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
channel = next(a.split("=", 1)[1] for a in args if a.startswith("-Pfoxhole.updateChannel="))
profile = json.loads(Path("config/foxcore-android-delivery.json").read_text())["androidDeliveryProfile"]
properties = next(a.split("=", 1)[1] for a in args if a.startswith("-Pfoxhole.releaseSigningProperties="))
record = {"args": args, "profile": profile, "cwd": str(Path.cwd()), "core": os.environ["FOXCORE_SOURCE_ROOT"],
          "privatePropsCopied": Path("private-signing.properties").exists(), "signingPropertiesAbsolute": Path(properties).is_absolute(),
          "keyAliasInherited": os.environ.get("FOXHOLE_RELEASE_KEY_ALIAS") == "fixture-alias"}
with open(os.environ["CALL_LOG"], "a") as output:
    output.write(json.dumps(record) + "\\n")
if os.environ.get("FAIL_CHANNEL") == channel:
    sys.exit(7)
if ":app:assembleRelease" in args:
    for name in ["app/build/outputs/apk/release/app-release.apk", "app/build/outputs/mapping/release/mapping.txt",
                 "app/build/generated/licenseAssets/SHA256SUMS"]:
        path = Path(name); path.parent.mkdir(parents=True, exist_ok=True); path.write_text(channel + "/" + profile)
if ":app:publicReleasePreflight" in args:
    path = Path("build/reports/cyclonedx/bom.json"); path.parent.mkdir(parents=True, exist_ok=True); path.write_text("{}")
''')
        self.git(self.root, "init", "-q")
        self.git(self.root, "add", ".")
        self.git(self.root, "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        (self.base / "private-signing.properties").write_text("synthetic external fixture")
        self.log = self.base / "calls.jsonl"
        self.environment = {**os.environ, "CALL_LOG": str(self.log), "FOXCORE_SOURCE_ROOT": "../core",
                            "FOXHOLE_RELEASE_SIGNING_PROPERTIES": "../private-signing.properties", "FOXHOLE_RELEASE_KEY_ALIAS": "fixture-alias"}

    def git(self, root, *args):
        return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout

    def test_three_profiles_retain_own_outputs_and_preserve_owner_source(self):
        before = (self.root / "config/foxcore-source-manifest.json").read_bytes()
        result = subprocess.run(["bash", str(BUILDER), "172"], cwd=self.root, env=self.environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        for lane, identity in [("github", "github/android4"), ("github-armv7", "github/android-armv7"), ("fdroid", "fdroid/android4")]:
            self.assertEqual(identity, (self.root / "build/release-variants" / lane / "app.apk").read_text())
            self.assertEqual(identity, (self.root / "build/release-variants" / lane / "licenseAssets/SHA256SUMS").read_text())
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(4, len(calls))
        for call in calls:
            self.assertIn("-Pfoxhole.lastUploadedVersionCode=172", call["args"])
            self.assertIn("-Pfoxhole.splitApks=false", call["args"])
            self.assertIn(":app:validateReleaseSigningInputs", call["args"])
            self.assertFalse(call["privatePropsCopied"])
            self.assertTrue(call["signingPropertiesAbsolute"] and call["keyAliasInherited"])
            self.assertEqual(str(self.base / "core"), call["core"])
            self.assertFalse(Path(call["cwd"]).exists(), "Owned clone must be removed")
        self.assertIn("-Pfoxhole.abis=arm64-v8a armeabi-v7a x86 x86_64", calls[0]["args"])
        self.assertIn("-Pfoxhole.abis=armeabi-v7a", calls[1]["args"])
        self.assertIn("-Pfoxhole.abis=arm64-v8a armeabi-v7a x86 x86_64", calls[2]["args"])
        self.assertIn(":app:publicReleasePreflight", calls[3]["args"])
        self.assertEqual(before, (self.root / "config/foxcore-source-manifest.json").read_bytes())
        self.assertEqual("", self.git(self.root, "diff", "--name-only", "HEAD"))
        self.assertTrue((self.base / "private-signing.properties").exists())

    def test_failed_profile_never_reaches_public_preflight(self):
        result = subprocess.run(["bash", str(BUILDER), "172"], cwd=self.root,
                                env={**self.environment, "FAIL_CHANNEL": "fdroid"}, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(3, len(calls))
        self.assertTrue(all(":app:publicReleasePreflight" not in call["args"] for call in calls))
        self.assertTrue(all(not Path(call["cwd"]).exists() for call in calls))


@unittest.skipUnless(shutil.which("jq"), "jq is required by the release packager")
class CandidateChannelsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidate = self.root / "candidate"
        self.candidate.mkdir()
        self.aapt = self.root / "aapt2"
        executable(self.aapt, '''#!/usr/bin/env python3
import sys, zipfile
with zipfile.ZipFile(sys.argv[-1]) as apk:
    print(apk.read("test-permissions.txt" if sys.argv[-2] == "permissions" else "test-badging.txt").decode())
''')
        self.signer = self.root / "apksigner"
        executable(self.signer, '''#!/usr/bin/env python3
import sys, zipfile
with zipfile.ZipFile(sys.argv[-1]) as apk:
    certificate = apk.read("test-cert.txt").decode()
print("Verified using v2 scheme (APK Signature Scheme v2): true")
print("Signer #1 certificate SHA-256 digest: " + certificate)
''')
        self.write_apk(GH_APK, INSTALL_PERMISSION)
        self.write_apk(ARMV7_APK, INSTALL_PERMISSION)
        self.write_apk(FDROID_APK, "")
        for name, apk in ((GH_SBOM, GH_APK), (ARMV7_SBOM, ARMV7_APK), (FDROID_SBOM, FDROID_APK)):
            self.write_json(name, {"metadata": {"properties": [
                {"name": "foxhole:apkSha256", "value": self.digest(apk)}]}})
        for name in [LICENSES, ARMV7_LICENSES, FDROID_LICENSES]:
            (self.candidate / name).write_bytes(b"license fixture")
        (self.candidate / "release-certs.txt").write_text("certificate fixture")
        for manifest, apk in [("update-manifest.json", GH_APK), ("update-manifest-armeabi-v7a.json", ARMV7_APK)]:
            self.write_json(manifest, {"versionCode": 174, "versionName": "1.0.0", "apkName": apk, "apkSha256": self.digest(apk)})
        self.write_json("CANDIDATE.json", {
            "schema": 2, "artifactMatrixVersion": 1, "sourceCommit": COMMIT, "sourceTree": TREE, "versionName": "1.0.0",
            "versionCode": 174, "tag": "v1.0.0", "coreRevision": CORE,
            "arm64Apk": GH_APK, "fdroidArm64Apk": FDROID_APK, "fdroidUniversalApk": FDROID_APK,
            "sbom": GH_SBOM, "fdroidSbom": FDROID_SBOM, "licenseNotices": LICENSES,
            "armv7Apk": ARMV7_APK, "armv7Sbom": ARMV7_SBOM, "armv7LicenseNotices": ARMV7_LICENSES,
            "fdroidLicenseNotices": FDROID_LICENSES, "updateChannel": "github",
            "artifacts": [
                {"profile": "android4", "updateChannel": "github", "apk": GH_APK, "sbom": GH_SBOM, "licenseNotices": LICENSES},
                {"profile": "android-armv7", "updateChannel": "github", "apk": ARMV7_APK, "sbom": ARMV7_SBOM, "licenseNotices": ARMV7_LICENSES},
                {"profile": "android4", "updateChannel": "fdroid", "apk": FDROID_APK, "sbom": FDROID_SBOM, "licenseNotices": FDROID_LICENSES}],
            "updaterManifests": {"android4": "update-manifest.json", "android-armv7": "update-manifest-armeabi-v7a.json"}})

    def write_apk(self, name, permissions, commit=COMMIT, abis=None, certificate="0" * 64):
        with zipfile.ZipFile(self.candidate / name, "w") as apk:
            apk.writestr("test-permissions.txt", permissions)
            if abis is None:
                abis = ["armeabi-v7a"] if name == ARMV7_APK else ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
            apk.writestr("test-badging.txt", "package: name='com.foxhole.guard' versionCode='174' versionName='1.0.0'\n" +
                         "native-code: " + " ".join("'" + abi + "'" for abi in abis))
            apk.writestr("test-cert.txt", certificate)
            apk.writestr("META-INF/version-control-info.textproto", f'''repositories {{
  system: GIT
  local_root_path: "$PROJECT_DIR"
  revision: "{commit}"
}}
''')

    def digest(self, name):
        return hashlib.sha256((self.candidate / name).read_bytes()).hexdigest()

    def write_json(self, name, value):
        (self.candidate / name).write_text(json.dumps(value))

    def verify(self):
        files = sorted(p for p in self.candidate.iterdir() if p.name != "SHA256SUMS")
        (self.candidate / "SHA256SUMS").write_text("".join(
            f"{self.digest(p.name)}  {p.name}\n" for p in files))
        # Synthetic APK fixtures exercise routing, inventory, digests and provenance.
        # Cryptographic tool and legal bodies are fixture stubs; all matrix checks run unchanged.
        script = '''source "$1"
normalized_expected_cert() { printf '%064d\\n' 0; }
find_build_tool() { if [[ "$1" == apksigner ]]; then printf '%s\\n' "$TEST_SIGNER"; else printf '%s\\n' "$TEST_AAPT"; fi; }
verify_android_sbom_inventory() { :; }
verify_license_notices_archive() { :; }
verify_fdroid_signing_blocks() { :; }
verify_candidate "$2" "$3" "$4"
'''
        return subprocess.run(["bash", "-c", script, "test", str(PACKAGER), str(self.candidate), COMMIT, TREE, CORE],
                              cwd=ROOT, env={**os.environ, "TEST_AAPT": str(self.aapt), "TEST_SIGNER": str(self.signer)},
                              capture_output=True, text=True)

    def test_all_three_profiles_are_required_and_github_manifests_stay_separate(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        (self.candidate / FDROID_APK).unlink()
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected file set", result.stderr)

    def test_github_apk_cannot_be_published_as_the_fdroid_variant(self):
        self.write_apk(FDROID_APK, INSTALL_PERMISSION)
        self.write_json(FDROID_SBOM, {"metadata": {"properties": [
            {"name": "foxhole:apkSha256", "value": self.digest(FDROID_APK)}]}})
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("F-Droid APK must not request", result.stderr)

    def test_fdroid_apk_must_have_the_same_source_commit(self):
        self.write_apk(FDROID_APK, "", commit="d" * 40)
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source commit does not match", result.stderr)

    def test_fdroid_sbom_cannot_be_replaced_with_the_github_sbom(self):
        shutil.copyfile(self.candidate / GH_SBOM, self.candidate / FDROID_SBOM)
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SBOM does not describe the expected APK", result.stderr)

    def test_in_app_manifest_cannot_switch_users_to_the_fdroid_variant(self):
        self.write_json("update-manifest.json", {
            "versionCode": 174, "versionName": "1.0.0", "apkName": FDROID_APK,
            "apkSha256": self.digest(FDROID_APK)})
        self.assertNotEqual(self.verify().returncode, 0)


    def test_compatibility_aliases_cannot_change_the_profile(self):
        metadata = json.loads((self.candidate / "CANDIDATE.json").read_text())
        metadata["arm64Apk"] = ARMV7_APK
        self.write_json("CANDIDATE.json", metadata)
        self.assertNotEqual(self.verify().returncode, 0)

    def test_armv7_apk_is_required(self):
        (self.candidate / ARMV7_APK).unlink()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_each_profile_rejects_another_native_abi_set(self):
        cases = [(GH_APK, INSTALL_PERMISSION, ["arm64-v8a"]),
                 (ARMV7_APK, INSTALL_PERMISSION, ["armeabi-v7a", "arm64-v8a"]),
                 (FDROID_APK, "", ["arm64-v8a", "armeabi-v7a", "x86_64"])]
        for name, permissions, abis in cases:
            with self.subTest(name=name):
                self.write_apk(name, permissions, abis=abis)
                sbom = {GH_APK: GH_SBOM, ARMV7_APK: ARMV7_SBOM, FDROID_APK: FDROID_SBOM}[name]
                self.write_json(sbom, {"metadata": {"properties": [{"name": "foxhole:apkSha256", "value": self.digest(name)}]}})
                for manifest, target in [("update-manifest.json", GH_APK), ("update-manifest-armeabi-v7a.json", ARMV7_APK)]:
                    self.write_json(manifest, {"versionCode": 174, "versionName": "1.0.0", "apkName": target, "apkSha256": self.digest(target)})
                result = self.verify()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("native ABI inventory", result.stderr)
                self.write_apk(name, permissions)
                self.write_json(sbom, {"metadata": {"properties": [{"name": "foxhole:apkSha256", "value": self.digest(name)}]}})

    def test_armv7_manifest_cannot_select_the_github64_apk(self):
        self.write_json("update-manifest-armeabi-v7a.json", {"versionCode": 174, "versionName": "1.0.0", "apkName": GH_APK, "apkSha256": self.digest(GH_APK)})
        self.assertNotEqual(self.verify().returncode, 0)

    def test_armv7_must_keep_the_github_install_permission(self):
        self.write_apk(ARMV7_APK, "")
        self.write_json(ARMV7_SBOM, {"metadata": {"properties": [{"name": "foxhole:apkSha256", "value": self.digest(ARMV7_APK)}]}})
        self.write_json("update-manifest-armeabi-v7a.json", {"versionCode": 174, "versionName": "1.0.0", "apkName": ARMV7_APK, "apkSha256": self.digest(ARMV7_APK)})
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GitHub APK is missing", result.stderr)

    def test_certificate_mismatch_is_not_a_profile_exception(self):
        self.write_apk(ARMV7_APK, INSTALL_PERMISSION, certificate="1" * 64)
        self.write_json(ARMV7_SBOM, {"metadata": {"properties": [{"name": "foxhole:apkSha256", "value": self.digest(ARMV7_APK)}]}})
        self.write_json("update-manifest-armeabi-v7a.json", {"versionCode": 174, "versionName": "1.0.0", "apkName": ARMV7_APK, "apkSha256": self.digest(ARMV7_APK)})
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("certificate mismatch", result.stderr)


class ReleaseCoordinatesTest(unittest.TestCase):
    def coordinates(self, cwd):
        return subprocess.run(["bash", "-c", 'source "$1"; read_release_coordinates; printf "%s %s %s\\n" "$VERSION_CODE" "$VERSION_NAME" "$CORE_REVISION"',
                               "test", str(PACKAGER)], cwd=cwd, capture_output=True, text=True)

    def test_actual_current_gradle_coordinates_without_function_override(self):
        result = self.coordinates(ROOT)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(f"174 1.0.0 {CORE}", result.stdout.strip())

    def test_unknown_or_ambiguous_base_expression_is_rejected(self):
        for expression in ["baseAppVersionCode + 1", "localBuildNumber ?: baseAppVersionCode + 1"]:
            with self.subTest(expression=expression), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "app").mkdir()
                (root / "config").mkdir()
                (root / "config/foxcore-revision.txt").write_text(CORE)
                (root / "build.gradle.kts").write_text('version = "1.0.0"\n')
                (root / "app/build.gradle.kts").write_text('val baseAppVersionCode = 174\nversionCode = ' + expression + '\n')
                self.assertNotEqual(self.coordinates(root).returncode, 0)


class AggregateProfileProjectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "config").mkdir()
        (self.root / "scripts").mkdir()
        self.files = [{"path": f"src/input{n:03}.rs", "sha256": hashlib.sha256(str(n).encode()).hexdigest()} for n in range(522)]
        self.shape = {"androidDeliveryProfile": "android64", "abis": ["arm64-v8a", "x86_64"],
                      "targets": ["aarch64-linux-android", "x86_64-linux-android"]}
        (self.root / "config/foxcore-android-delivery.json").write_text(json.dumps({"schema": 1, **self.shape}))
        (self.root / "config/foxcore-source-manifest.json").write_text(json.dumps({"commit": CORE, "files": self.files, **self.shape}))
        (self.root / "config/foxcore-revision.txt").write_text(CORE)
        self.snapshot = self.root / "third_party/licenses/source-legal/android3/snapshot.json"
        self.snapshot.parent.mkdir(parents=True)
        self.snapshot.write_text(json.dumps({"coreCommit": CORE, "androidDeliveryProfile": "android3",
            "abis": ["arm64-v8a", "armeabi-v7a", "x86_64"],
            "targets": ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"],
            "compiledInputs": {row["path"]: row["sha256"] for row in self.files}}))
        (self.root / "scripts/aggregate-delivery-sbom.py").write_text('''import json

def aggregate(bom, notices, apk, root):
    assert not (root / "private-untracked.txt").exists()
    source = json.loads((root / "config/foxcore-source-manifest.json").read_text())
    config = json.loads((root / "config/foxcore-android-delivery.json").read_text())
    assert source["androidDeliveryProfile"] == config["androidDeliveryProfile"] == "android3"
    return {"profile": config["androidDeliveryProfile"], "files": source["files"]}
''')
        self.git("init", "-q")
        self.git("add", ".")
        (self.root / "maven.json").write_text('{}')
        (self.root / "private-untracked.txt").write_text("synthetic private sentinel")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def aggregate(self):
        return subprocess.run(["bash", "-c", 'source "$1"; aggregate_profile_sbom maven.json unused-notices unused.apk aggregate.json android3',
                               "test", str(PACKAGER)], cwd=self.root, capture_output=True, text=True)

    def test_only_the_two_declared_profile_headers_are_projected(self):
        before = (self.root / "config/foxcore-source-manifest.json").read_bytes()
        result = self.aggregate()
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads((self.root / "aggregate.json").read_text())
        self.assertEqual("android3", actual["profile"])
        self.assertEqual(self.files, actual["files"])
        self.assertEqual(before, (self.root / "config/foxcore-source-manifest.json").read_bytes())
        self.assertTrue((self.root / "private-untracked.txt").exists())

    def test_canonical_profile_cannot_change_compiled_source_pins(self):
        snapshot = json.loads(self.snapshot.read_text())
        snapshot["compiledInputs"][self.files[0]["path"]] = "0" * 64
        self.snapshot.write_text(json.dumps(snapshot))
        self.git("add", str(self.snapshot.relative_to(self.root)))
        result = self.aggregate()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source binding differs", result.stderr)


class SelectedLicenseInventoryTest(unittest.TestCase):
    def test_secondary_target_cdx_is_required_after_rewriting_all_manifest_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "licenses.zip"
            files = {name: b"synthetic license-inventory fixture" for name in ['FoxHole-Guard-GPL-3.0-or-later.txt', 'THIRD_PARTY_NOTICES.md', 'android/JNA-Apache-2.0.txt', 'android/JNA-LGPL-2.1.txt', 'android/JNA-LICENSE.txt', 'android/SQLCipher-BSD-3-Clause.txt', 'android/lazysodium-MPL-2.0.txt', 'android/libsodium-ISC.txt', 'flags/app-assets.sha256', 'flags/flag-icons-LICENSE.txt', 'fonts/JetBrainsMono-OFL.txt', 'fonts/Tiny5-OFL.txt', 'foxcore/FoxHole-Core-GPL-3.0-or-later.txt', 'foxcore/THIRD_PARTY_NOTICES.md', 'i2pd/Android-NDK-29-NOTICE.toolchain.txt', 'i2pd/Android-NDK-29-NOTICE.txt', 'i2pd/Boost-1.84.0-BSL-1.0.txt', 'i2pd/OpenSSL-3.5.8-Apache-2.0.txt', 'i2pd/i2pd-BSD-3-Clause.txt', 'icons/Tabler-MIT.txt', 'tor-config/PROVENANCE.txt', 'tor-config/assets.sha256', 'tor-config/upstream-members/README.CONJURE.md', 'tor-config/upstream-members/pt_config.json', 'tor-config/upstream-members/torrc-defaults.txt', 'tor/GO-MODULES.json', 'tor/Go-1.26.10-BSD-3-Clause.txt', 'tor/conjure-client-BSD-3-Clause.txt', 'tor/lyrebird-BSD-3-Clause.txt', 'tor/lyrebird-GPL-3.0-or-later.txt']}
            files.update({"foxcore/foxcore-aarch64-linux-android.cdx.json": b"{}",
                          "foxcore/foxcore-x86_64-linux-android.cdx.json": b"{}"})
            def write():
                payload = dict(files)
                manifest = {"schema": 1, "foxcoreRevision": CORE, "androidDeliveryProfile": "android64",
                            "abis": ["arm64-v8a", "x86_64"], "targets": ["aarch64-linux-android", "x86_64-linux-android"],
                            "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                                      for name, data in sorted(payload.items())]}
                payload["MANIFEST.json"] = json.dumps(manifest).encode()
                payload["SHA256SUMS"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(payload.items())).encode()
                with zipfile.ZipFile(archive, "w") as stream:
                    for name, data in payload.items():
                        info = zipfile.ZipInfo(name)
                        info.external_attr = 0o100644 << 16
                        stream.writestr(info, data)
            def verify():
                return subprocess.run(["bash", "-c", 'source "$1"; read_release_coordinates; verify_license_notices_archive "$2" android64',
                                       "test", str(PACKAGER), str(archive)], cwd=ROOT, capture_output=True, text=True)
            write()
            valid = verify()
            self.assertEqual(valid.returncode, 0, valid.stderr)
            del files["foxcore/foxcore-x86_64-linux-android.cdx.json"]
            write()
            invalid = verify()
            self.assertNotEqual(invalid.returncode, 0)
            self.assertIn("foxcore-x86_64-linux-android.cdx.json", invalid.stderr)


class ObtainiumChannelTest(unittest.TestCase):
    def test_both_readme_links_select_only_the_github_apk(self):
        for readme in ("README.md", "docs/README.ru.md"):
            with self.subTest(readme=readme):
                text = (ROOT / readme).read_text()
                encoded = re.search(r"r=obtainium://app/([^\"]+)", text).group(1)
                payload = json.loads(urllib.parse.unquote(encoded))
                pattern = json.loads(payload["additionalSettings"])["apkFilterRegEx"]
                self.assertIsNotNone(re.search(pattern, GH_APK))
                self.assertIsNone(re.search(pattern, FDROID_APK))


if __name__ == "__main__":
    unittest.main()
