import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("licenses", Path(__file__).resolve().parents[1] / "generate-license-assets.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LicenseAbisTest(unittest.TestCase):
    def test_emulator_inventory_never_requires_unbuilt_arm_transports(self):
        paths = MODULE.transport_binary_paths(["x86_64"])
        self.assertEqual(["lyrebird", "conjure-client"], [name for name, _ in paths])
        self.assertTrue(all("x86_64" in path.parts for _, path in paths))
        self.assertFalse(any("arm64-v8a" in path.parts for _, path in paths))

    def test_each_shipped_abi_contributes_its_actual_binary(self):
        paths = MODULE.transport_binary_paths(["arm64-v8a", "armeabi-v7a"])
        self.assertEqual(4, len(paths))
        self.assertEqual({"arm64-v8a", "armeabi-v7a"}, {path.parts[-4] for _, path in paths})

    def test_unknown_and_repeated_abis_are_refused(self):
        for abis in ([], ["mips"], ["x86_64", "x86_64"]):
            with self.assertRaises(ValueError):
                MODULE.transport_binary_paths(abis)
