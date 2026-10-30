import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "tor_boundary", Path(__file__).resolve().parents[1] / "verify-tor-package-boundary.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TorToolchainPolicyTest(unittest.TestCase):
    def test_older_compiler_is_rejected_before_resolving_packages(self):
        self.assert_rejected("go version go1.26.8 linux/amd64\n")

    def test_other_minor_compiler_is_rejected_before_resolving_packages(self):
        self.assert_rejected("go version go1.27.2 linux/amd64\n")

    def test_similar_version_prefix_cannot_pass_the_exact_pin(self):
        self.assert_rejected("go version go1.26.100 linux/amd64\n")

    def assert_rejected(self, version):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_go = root / "source-built-go"
            with mock.patch.dict(MODULE.os.environ, {
                "FOXHOLE_GOROOT": str(source_go), "GOROOT": "/wrong/root",
                "GOTOOLDIR": "/wrong/tools", "GOTOOLCHAIN": "auto", "GOFLAGS": "-mod=mod",
            }):
                with mock.patch.object(MODULE.subprocess, "check_output", return_value=version) as tool:
                    with mock.patch.object(MODULE.subprocess, "run") as resolve:
                        with self.assertRaisesRegex(ValueError, "pinned Go 1.26.10"):
                            MODULE.inventory(root / "native", root / "ndk", root / "out")
                        resolve.assert_not_called()
                    arguments, options = tool.call_args
                    self.assertEqual([str(source_go / "bin/go"), "version"], arguments[0])
                    self.assertEqual("local", options["env"]["GOTOOLCHAIN"])
                    self.assertEqual(str(source_go), options["env"]["GOROOT"])
                    self.assertNotIn("GOTOOLDIR", options["env"])
                    self.assertNotIn("GOFLAGS", options["env"])
            self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    unittest.main()
