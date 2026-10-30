#!/usr/bin/env python3
"""No-match grep batches must not reject otherwise valid JNI references."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/verify-foxcore-jni-seam.sh'
MATCH = re.search(r"sh -c '([^']+)' sh \{\} \+", SCRIPT.read_text())
COMMAND = MATCH.group(1) if MATCH else None


class JniReferenceBatchesTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='jni-reference-batches-')
        self.root = Path(self.owned.name)
        self.empty = self.root / 'Empty.kt'
        self.empty.write_text('fun unrelated() = Unit\n')
        self.reference = self.root / 'Reference.kt'
        self.reference.write_text('FoxholeNativeEngine.nativeImportLink("test")\n')
        self.assertIsNotNone(COMMAND)

    def tearDown(self):
        self.owned.cleanup()

    def batch(self, *paths):
        return subprocess.run(['sh', '-c', COMMAND, 'sh', *map(str, paths)],
                              text=True, capture_output=True, timeout=5)

    def test_independent_no_match_batch_and_matching_batch_succeed(self):
        empty = self.batch(self.empty)
        matched = self.batch(self.reference)
        self.assertEqual(empty.returncode, 0)
        self.assertEqual(empty.stdout, '')
        self.assertEqual(matched.returncode, 0)
        self.assertIn('FoxholeNativeEngine.nativeImportLink', matched.stdout)

    def test_all_no_match_is_empty_for_the_later_facade_check(self):
        result = self.batch(self.empty)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_missing_file_read_error_is_still_rejected(self):
        result = self.batch(self.reference, self.root / 'Missing.kt')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No such file', result.stderr)

    def test_find_failure_is_still_rejected(self):
        result = subprocess.run(['find', str(self.root / 'missing-root'), '-type', 'f',
                                 '-name', '*.kt', '-exec', 'sh', '-c', COMMAND, 'sh', '{}', '+'],
                                text=True, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
