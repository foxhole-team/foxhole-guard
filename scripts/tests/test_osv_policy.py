import datetime
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("policy", pathlib.Path(__file__).parents[1] / "classify-osv-report.py")
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class OsvPolicyTest(unittest.TestCase):
    def report(self, source):
        return {"results": [{"source": {"path": source}, "packages": [{"package": {
            "name": "com.squareup.okhttp3:okhttp", "version": "5.4.0", "ecosystem": "Maven"},
            "vulnerabilities": [{"id": "TEST-ADVISORY"}]}]}]}

    def test_origin_cannot_exempt_runtime_finding(self):
        for source in ("gradle/verification-metadata.xml", "Cargo.lock", "go.mod", "delivery.cdx.json"):
            blocked, _ = policy.classify(self.report(source), {"exceptions": []}, datetime.date(2026, 9, 8))
            self.assertEqual(1, len(blocked))

    def test_exception_requires_exact_identity_and_current_expiry(self):
        exception = dict(ecosystem="Maven", package="com.squareup.okhttp3:okhttp", version="5.4.0",
                         advisory="TEST-ADVISORY", reason="fixture only", evidence="fixture", expires="2026-09-08")
        report = self.report("any")
        self.assertFalse(policy.classify(report, {"exceptions": [exception]}, datetime.date(2026, 9, 8))[0])
        for key, value in (("version", "5.3.0"), ("advisory", "OTHER"), ("expires", "2026-09-07")):
            self.assertTrue(policy.classify(report, {"exceptions": [{**exception, key: value}]}, datetime.date(2026, 9, 8))[0])

    def test_missing_and_malformed_results_fail(self):
        for report in ({}, {"results": None}, {"results": [{}]}):
            with self.assertRaises(ValueError):
                policy.classify(report, {"exceptions": []}, datetime.date(2026, 9, 8))

    def test_aliases_deduplicate_across_origins_and_preserve_evidence(self):
        report = self.report("runtime.cdx.json")
        report["results"][0]["packages"][0]["vulnerabilities"][0]["aliases"] = ["CVE-TEST"]
        other = self.report("build.cdx.json")["results"][0]
        other["packages"][0]["vulnerabilities"] = [{"id": "OTHER-ID", "aliases": ["CVE-TEST"]}]
        report["results"].append(other)
        blocked, _ = policy.classify(report, {"exceptions": []}, datetime.date(2026, 9, 8))
        self.assertEqual(1, len(blocked))
        for value in ("runtime.cdx.json", "build.cdx.json", "TEST-ADVISORY", "OTHER-ID", "CVE-TEST"):
            self.assertIn(value, blocked[0])

    def test_malformed_entries_never_pass_as_clean(self):
        cases = [None, [], {"results": [None]}, {"results": [{"packages": [None]}]}]
        for report in cases:
            with self.assertRaises(ValueError):
                policy.classify(report, {"exceptions": []}, datetime.date(2026, 9, 8))
        for broken_policy in (None, [], {"exceptions": [None]}, {"exceptions": [{"package": "anything"}]}):
            with self.assertRaises(ValueError):
                policy.classify({"results": []}, broken_policy, datetime.date(2026, 9, 8))

    def test_empty_report_cannot_contradict_scanner_failure(self):
        import json
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            report = pathlib.Path(directory, "report.json")
            exceptions = pathlib.Path(directory, "policy.json")
            report.write_text(json.dumps({"results": []}))
            exceptions.write_text(json.dumps({"exceptions": []}))
            result = subprocess.run([__import__("sys").executable, str(pathlib.Path(__file__).parents[1] / "classify-osv-report.py"),
                                     str(report), str(exceptions), "--scanner-status", "1"], capture_output=True)
            self.assertNotEqual(0, result.returncode)
