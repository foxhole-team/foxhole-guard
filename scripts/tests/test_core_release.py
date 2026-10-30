import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("core_release", Path(__file__).resolve().parents[1] / "verify-core-release.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class CoreReleaseTest(unittest.TestCase):
    def test_exact_source_and_elf_required_before_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, release, output = (root / name for name in ("source", "release", "output"))
            source.mkdir(); release.mkdir()
            for target in (source, release): (target / "Cargo.lock").write_text("locked")
            rows = []
            for abi in ("arm64-v8a", "armeabi-v7a"):
                library = release / "jniLibs" / abi / "libfoxhole_native.so"
                library.parent.mkdir(parents=True)
                library.write_bytes(b"verified ELF")
                rows.append({"abi": abi, "bytes": 12, "sha256": hashlib.sha256(library.read_bytes()).hexdigest()})
            sha = "a" * 40
            (release / "MANIFEST.json").write_text(json.dumps({"git": {"commit": sha, "dirty": False}, "libs": rows}))
            (release / "RELEASE.json").write_text(json.dumps({"main_commit": sha}))
            MODULE.verify(source, release, sha, ["arm64-v8a"], output)
            self.assertEqual(b"verified ELF", (output / "arm64-v8a/libfoxhole_native.so").read_bytes())
            with self.assertRaises(ValueError): MODULE.verify(source, release, "b" * 40, ["arm64-v8a"])
            (source / "Cargo.lock").write_text("different")
            with self.assertRaises(ValueError): MODULE.verify(source, release, sha, ["arm64-v8a"])
            (source / "Cargo.lock").write_text("locked")
            (release / "jniLibs/arm64-v8a/libfoxhole_native.so").write_bytes(b"tampered ELF")
            with self.assertRaises(ValueError): MODULE.verify(source, release, sha, ["arm64-v8a"])

    def test_four_abi_release_verifies_every_requested_library_before_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, release, output = (root / name for name in ("source", "release", "output"))
            source.mkdir()
            release.mkdir()
            for target in (source, release):
                (target / "Cargo.lock").write_text("locked")
            rows = []
            abis = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
            for abi in abis:
                library = release / "jniLibs" / abi / "libfoxhole_native.so"
                library.parent.mkdir(parents=True)
                data = ("verified " + abi).encode()
                library.write_bytes(data)
                rows.append({"abi": abi, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            sha = "a" * 40
            (release / "MANIFEST.json").write_text(json.dumps({"git": {"commit": sha, "dirty": False}, "libs": rows}))
            (release / "RELEASE.json").write_text(json.dumps({"main_commit": sha}))
            MODULE.verify(source, release, sha, abis, output)
            for abi in abis:
                self.assertEqual(("verified " + abi).encode(), (output / abi / "libfoxhole_native.so").read_bytes())

    def test_invalid_requested_abi_is_refused_without_copying_any_library(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, release, output = (root / name for name in ("source", "release", "output"))
            source.mkdir()
            release.mkdir()
            for target in (source, release):
                (target / "Cargo.lock").write_text("locked")
            rows = []
            for abi in ("arm64-v8a", "armeabi-v7a"):
                library = release / "jniLibs" / abi / "libfoxhole_native.so"
                library.parent.mkdir(parents=True)
                library.write_bytes(b"verified ELF")
                rows.append({"abi": abi, "bytes": 12, "sha256": hashlib.sha256(b"verified ELF").hexdigest()})
            sha = "a" * 40
            (release / "MANIFEST.json").write_text(json.dumps({"git": {"commit": sha, "dirty": False}, "libs": rows}))
            (release / "RELEASE.json").write_text(json.dumps({"main_commit": sha}))
            for abis in ([], ["arm64-v8a", "mips"], ["arm64-v8a", "arm64-v8a"], ["x86"]):
                with self.subTest(abis=abis), self.assertRaises(ValueError):
                    MODULE.verify(source, release, sha, abis, output)
                self.assertFalse(output.exists())
