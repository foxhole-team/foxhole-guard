import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "source_inputs", Path(__file__).resolve().parents[1] / "i2pd-source-inputs.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class I2pdSourceInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.environment = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("init", "-q")
        (self.root / "build").mkdir()
        (self.root / "build/CMakeLists.txt").write_text("add_library(owned source.cpp)\n")
        (self.root / "source.cpp").write_text("int owned() { return 1; }\n")
        (self.root / "other.cpp").write_text("int owned() { return 1; }\n")
        for index in range(4):
            (self.root / f"link-{index}").symlink_to("source.cpp")
        self.git("add", ".")
        self.git("-c", "user.name=Source fixture", "-c", "user.email=source@invalid", "commit", "--no-gpg-sign", "-qm", "owned fixture")

    def git(self, *arguments):
        return subprocess.check_output(["git", "-C", str(self.root), *arguments], env=self.environment)

    def fingerprint(self):
        return MODULE.source_snapshot(self.root)["fingerprint"]

    def test_tracked_cpp_bytes_change_the_cache_input(self):
        previous = self.fingerprint()
        (self.root / "source.cpp").write_text("int owned() { return 2; }\n")
        self.assertNotEqual(previous, self.fingerprint())

    def test_tracked_cmake_and_raw_modes_change_the_cache_input(self):
        previous = self.fingerprint()
        (self.root / "build/CMakeLists.txt").write_text("add_library(owned other.cpp)\n")
        self.assertNotEqual(previous, self.fingerprint())
        previous = self.fingerprint()
        (self.root / "source.cpp").chmod(0o755)
        self.assertNotEqual(previous, self.fingerprint())

    def test_deleted_tracked_source_changes_the_cache_input(self):
        previous = self.fingerprint()
        (self.root / "source.cpp").unlink()
        for index in range(4):
            (self.root / f"link-{index}").unlink()
        self.assertNotEqual(previous, self.fingerprint())

    def test_all_four_internal_links_are_retained_and_targets_are_bound(self):
        previous = self.fingerprint()
        snapshot = MODULE.source_snapshot(self.root)
        self.assertEqual(4, sum(row["kind"] == "link" for row in snapshot["files"].values()))
        path = self.root / "link-0"
        path.unlink()
        path.symlink_to("other.cpp")
        self.assertNotEqual(previous, self.fingerprint())

    def test_head_and_epoch_changes_invalidate_unchanged_source(self):
        previous = self.fingerprint()
        self.git("-c", "user.name=Source fixture", "-c", "user.email=source@invalid", "commit", "--no-gpg-sign", "--allow-empty", "-qm", "next fixture")
        self.assertNotEqual(previous, self.fingerprint())

    def test_generated_untracked_build_outputs_are_ignored(self):
        previous = self.fingerprint()
        (self.root / "build/generated.o").write_bytes(b"owned output")
        self.assertEqual(previous, self.fingerprint())

    def test_escaped_link_is_rejected_before_cache_decision(self):
        link = self.root / "link-0"
        link.unlink()
        link.symlink_to("../unowned-source.cpp")
        with self.assertRaisesRegex(ValueError, "link"):
            self.fingerprint()

    def test_untracked_parent_link_cannot_expose_an_external_regular_file(self):
        nested = self.root / "nested"
        nested.mkdir()
        (nested / "source.cpp").write_text("int nested() { return 1; }\n")
        self.git("add", "nested/source.cpp")
        nested.rename(self.root / "original-nested")
        with tempfile.TemporaryDirectory() as outside:
            external = Path(outside)
            (external / "source.cpp").write_text("int unowned() { return 2; }\n")
            nested.symlink_to(external, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "parent link"):
                self.fingerprint()

    def test_internal_link_cannot_depend_on_an_untracked_parent_link(self):
        (self.root / "source-alias").symlink_to(self.root, target_is_directory=True)
        link = self.root / "link-0"
        link.unlink()
        link.symlink_to("source-alias/source.cpp")
        with self.assertRaisesRegex(ValueError, "parent link"):
            self.fingerprint()

    def test_link_target_change_during_hashing_is_rejected(self):
        original = MODULE.file_hash
        changed = False

        def hash_and_change(path):
            nonlocal changed
            result = original(path)
            if path.resolve() == (self.root / "source.cpp").resolve() and not changed:
                link = self.root / "link-0"
                link.unlink()
                link.symlink_to("other.cpp")
                changed = True
            return result

        with mock.patch.object(MODULE, "file_hash", side_effect=hash_and_change):
            with self.assertRaisesRegex(ValueError, "link changed"):
                self.fingerprint()


if __name__ == "__main__":
    unittest.main()
