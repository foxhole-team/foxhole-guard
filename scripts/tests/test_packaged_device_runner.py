"""Exercise device-runner failure propagation without booting an emulator."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "run-packaged-device-tests.sh"
FAKE_ADB = '''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
args = sys.argv[1:]
with Path("calls").open("a") as calls:
    calls.write(" ".join(args) + "\\n")
if "df" in args:
    print("Filesystem 1K-blocks Used Available Use% Mounted on")
    print("/dev/block/data 6291456 1000 " + os.environ.get("FREE_KB", "4000000") + " 1% /data")
elif "instrument" in args:
    runtime = any(a.startswith("com.foxhole.core.runtime.test/") for a in args)
    fail = runtime and os.environ.get("FAIL_RUNTIME") == "1"
    print("FAILURES!!!" if fail else "OK (4 tests)")
    if fail and os.environ.get("ADB_FAIL") == "1":
        sys.exit(7)
'''


class PackagedDeviceRunnerTest(unittest.TestCase):
    def run_runner(self, **overrides):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            adb = root / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o700)
            apks = root / "apks"
            apks.mkdir()
            for name in ("app.apk", "app-test.apk", "runtime-test.apk"):
                (apks / name).touch()
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], **overrides)
            result = subprocess.run(["bash", str(SCRIPT), str(apks)], cwd=root, env=environment,
                                    capture_output=True, text=True, check=False)
            return result, (root / "calls").read_text()

    def test_all_suites_must_pass(self):
        result, calls = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, calls.count(" instrument "))

    def test_test_failure_still_runs_other_suite_and_fails_job(self):
        result, calls = self.run_runner(FAIL_RUNTIME="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, calls.count(" instrument "))

    def test_adb_failure_still_runs_other_suite_and_fails_job(self):
        result, calls = self.run_runner(FAIL_RUNTIME="1", ADB_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, calls.count(" instrument "))

    def test_low_storage_stops_before_installing_any_apk(self):
        result, calls = self.run_runner(FREE_KB="50000")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(" install ", calls)
        self.assertIn("at least 1 GiB", result.stderr)


if __name__ == "__main__":
    unittest.main()
