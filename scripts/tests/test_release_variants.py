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


ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / "scripts/package-release-candidate.sh"
BUILDER = ROOT / "scripts/build-release-variants.sh"
COMMIT = "a" * 40
TREE = "b" * 40
CORE = "c" * 40
GH_APK = "FoxHole-v1.2.3-arm64-v8a-release.apk"
FDROID_APK = "FoxHole-v1.2.3-arm64-v8a-fdroid.apk"
GH_SBOM = "FoxHole-v1.2.3-sbom.cdx.json"
FDROID_SBOM = "FoxHole-v1.2.3-fdroid-sbom.cdx.json"
LICENSES = "FoxHole-v1.2.3-license-notices.zip"
INSTALL_PERMISSION = "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'"


def executable(path, source):
    path.write_text(source)
    path.chmod(0o755)


class ReleaseBuildTest(unittest.TestCase):
    def test_channels_are_retained_before_the_shared_output_is_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable(root / "gradlew", '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
channel = next(a.split("=", 1)[1] for a in args if a.startswith("-Pfoxhole.updateChannel="))
with open("calls.jsonl", "a") as output:
    output.write(json.dumps(args) + "\\n")
if os.environ.get("FAIL_CHANNEL") == channel:
    sys.exit(7)
if ":app:assembleRelease" in args:
    for name in ["app/build/outputs/apk/release/app-arm64-v8a-release.apk",
                 "app/build/outputs/mapping/release/mapping.txt"]:
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(channel)
''')
            result = subprocess.run(["bash", str(BUILDER), "122"], cwd=root, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            for channel in ("github", "fdroid"):
                for name in ("app.apk", "mapping.txt"):
                    self.assertEqual((root / "build/release-variants" / channel / name).read_text(), channel)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            self.assertEqual(len(calls), 3)
            for call in calls:
                self.assertIn("-Pfoxhole.lastUploadedVersionCode=122", call)
            self.assertIn(":app:publicReleasePreflight", calls[2])
            self.assertNotIn("-Pfoxhole.splitApks=true", calls[2])

            (root / "calls.jsonl").unlink()
            result = subprocess.run(["bash", str(BUILDER), "122"], cwd=root,
                                    env={**os.environ, "FAIL_CHANNEL": "fdroid"}, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            calls = (root / "calls.jsonl").read_text().splitlines()
            self.assertEqual(len(calls), 2, "A failed APK build must not continue to bundle/preflight")


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
    print(apk.read("test-permissions.txt").decode())
''')
        self.write_apk(GH_APK, INSTALL_PERMISSION)
        self.write_apk(FDROID_APK, "")
        for name, apk in ((GH_SBOM, GH_APK), (FDROID_SBOM, FDROID_APK)):
            self.write_json(name, {"metadata": {"properties": [
                {"name": "foxhole:apkSha256", "value": self.digest(apk)}]}})
        (self.candidate / LICENSES).write_bytes(b"license fixture")
        (self.candidate / "release-certs.txt").write_text("certificate fixture")
        self.write_json("update-manifest.json", {
            "versionCode": 123, "versionName": "1.2.3", "apkName": GH_APK, "apkSha256": self.digest(GH_APK)})
        self.write_json("CANDIDATE.json", {
            "schema": 2, "sourceCommit": COMMIT, "sourceTree": TREE, "versionName": "1.2.3",
            "versionCode": 123, "tag": "v1.2.3", "coreRevision": CORE, "arm64Apk": GH_APK,
            "fdroidArm64Apk": FDROID_APK, "sbom": GH_SBOM, "fdroidSbom": FDROID_SBOM,
            "licenseNotices": LICENSES, "updateChannel": "github"})

    def write_apk(self, name, permissions, commit=COMMIT):
        with zipfile.ZipFile(self.candidate / name, "w") as apk:
            apk.writestr("test-permissions.txt", permissions)
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
        # Existing cryptographic/license validators remain production gates in CI.
        script = '''source "$1"
normalized_expected_cert() { printf '%064d\\n' 0; }
find_build_tool() { printf '%s\\n' "$TEST_AAPT"; }
verify_android_sbom_inventory() { :; }
verify_license_notices_archive() { :; }
verify_apk() { verify_apk_update_channel "$1" "$5" "$6"; }
CORE_FIXTURE=$5
read_release_coordinates() { VERSION_CODE=123; VERSION_NAME=1.2.3; CORE_REVISION=$CORE_FIXTURE; RELEASE_TAG=v1.2.3; }
verify_candidate "$2" "$3" "$4"
'''
        return subprocess.run(["bash", "-c", script, "test", str(PACKAGER), str(self.candidate), COMMIT, TREE, CORE],
                              cwd=ROOT, env={**os.environ, "TEST_AAPT": str(self.aapt)},
                              capture_output=True, text=True)

    def test_both_channels_are_required_and_github_manifest_stays_on_github(self):
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
            "versionCode": 123, "versionName": "1.2.3", "apkName": FDROID_APK,
            "apkSha256": self.digest(FDROID_APK)})
        self.assertNotEqual(self.verify().returncode, 0)


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
