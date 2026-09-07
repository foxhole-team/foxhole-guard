import importlib.util
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path


spec = importlib.util.spec_from_file_location(
    "apk_source", Path(__file__).resolve().parents[1] / "verify-apk-source.py"
)
apk_source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_source)

DEV_COMMIT = "597a3603afa9be92cf2d1bede2882ba27a4f08cd"
TAG_COMMIT = "82ed1e5fa400fa8f4b094466619b4a75547699d0"
ENTRY = "META-INF/version-control-info.textproto"


def repository(commit, path="$PROJECT_DIR"):
    return f'''repositories {{
  system: GIT
  local_root_path: "{path}"
  revision: "{commit}"
}}
'''


class ApkSourceTest(unittest.TestCase):
    def check_archive(self, entries, expected_commit):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    for name, content in entries:
                        archive.writestr(name, content)
            apk_source.verify(apk, expected_commit)

    def test_exact_build_commit_is_accepted(self):
        self.check_archive([(ENTRY, repository(TAG_COMMIT))], TAG_COMMIT)

    def test_equal_source_trees_do_not_make_different_commits_interchangeable(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.check_archive([(ENTRY, repository(DEV_COMMIT))], TAG_COMMIT)

    def test_dev_candidate_keeps_its_own_provenance(self):
        self.check_archive([(ENTRY, repository(DEV_COMMIT))], DEV_COMMIT)

    def test_submodule_revision_cannot_replace_root_revision(self):
        metadata = repository(DEV_COMMIT) + repository(TAG_COMMIT, "$PROJECT_DIR/third_party/i2pd")
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.check_archive([(ENTRY, metadata)], TAG_COMMIT)

    def test_missing_duplicate_and_non_git_provenance_is_rejected(self):
        for entries in (
            [],
            [(ENTRY, repository(TAG_COMMIT))] * 2,
            [(ENTRY, repository(TAG_COMMIT) * 2)],
            [(ENTRY, repository(TAG_COMMIT, "other"))],
            [(ENTRY, repository(TAG_COMMIT).replace("GIT", "SVN"))],
            [(ENTRY, repository(TAG_COMMIT).replace("\n}", f'\n  revision: "{TAG_COMMIT}"\n}}'))],
        ):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                self.check_archive(entries, TAG_COMMIT)

    def test_abbreviated_expected_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "full Git SHA"):
            self.check_archive([(ENTRY, repository(TAG_COMMIT))], TAG_COMMIT[:7])


if __name__ == "__main__":
    unittest.main()
