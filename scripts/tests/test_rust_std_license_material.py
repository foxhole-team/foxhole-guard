import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
CURRENT_CORE_COMMIT: str = "3a233e51d1903a8f88d842f711065fd0421ea5ee"
CURRENT_PRODUCER_SHA256: str = "419483147824b39ef31bfce9cae53bc29b42de88081af2649c3bbdea3d25f10b"
PREVIOUS_CORE_COMMIT: str = "13b931b2ba324fa1981c11860c4536468d1644f2"
PREVIOUS_PRODUCER_SHA256: str = "eb57f94b2748395c1241ef35f1a54517f287198bcdb01932ae0d630d021d5153"
PREVIOUS_PROVENANCE_SHA256: str = "1d5330fcfcb79ceb89b1d7bab1c5cf214c8da8874d30a0a08a48667f7c00a285"
spec = importlib.util.spec_from_file_location("std_test_generator", ROOT / "scripts/generate-license-assets.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class RustStdLicenseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.material = self.root / "third_party/licenses/rust-std-1.97.1"
        self.material.parent.mkdir(parents=True)
        shutil.copytree(ROOT / "third_party/licenses/rust-std-1.97.1", self.material)
        self.output = self.root / "output"
        self.output.mkdir()
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copyfile(ROOT / "scripts/android-delivery-profile.py", scripts / "android-delivery-profile.py")
        config = self.root / "config"
        config.mkdir()
        profile = {"androidDeliveryProfile": "android64", "abis": ["arm64-v8a", "x86_64"],
                   "targets": ["aarch64-linux-android", "x86_64-linux-android"]}
        (config / "foxcore-android-delivery.json").write_text(json.dumps({"schema": 1, **profile}))
        (config / "foxcore-source-manifest.json").write_text(json.dumps(profile))
        self.args = argparse.Namespace(foxcore_sbom_receipt=None, core_delivery_profile="android64",
                                       android_abis="arm64-v8a,x86_64")
        self.revision = CURRENT_CORE_COMMIT
        self.core = self.root / "core"

    def run_copy(self, channel="1.97.1", revision=None):
        toolchain = f'[toolchain]\nchannel="{channel}"\n'.encode()
        with patch.object(generator, "REPO_ROOT", self.root), patch.object(
            generator, "git_blob", return_value=toolchain
        ):
            generator.copy_rust_std_material(
                self.args, self.output, self.core, revision or self.revision
            )

    def historical_attribution(self, core_commit, provenance_sha256):
        value = json.loads((self.material / "PROVENANCE.json").read_bytes())
        matches = [prior for prior in value["historicalCurrentAttributions"]
                   if prior["nativeProducer"]["coreCommit"] == core_commit
                   and prior["provenanceSha256"] == provenance_sha256]
        self.assertEqual(len(matches), 1)
        return matches[0]

    def test_real_original_material_is_copied_separately(self):
        self.run_copy()
        self.assertEqual(
            (self.output / "foxcore/toolchain/COPYRIGHT-library.html").read_bytes(),
            (self.material / "COPYRIGHT-library.html").read_bytes(),
        )
        self.assertTrue((self.output / "foxcore/toolchain/PROVENANCE.json").is_file())

    def test_other_pinned_compiler_is_rejected(self):
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy("1.97.2")

    def test_other_core_producer_pin_is_rejected(self):
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy(revision="a" * 40)

    def test_tampered_installed_original_notice_is_rejected(self):
        path = self.material / "COPYRIGHT-library.html"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_tampered_publisher_supplement_is_rejected(self):
        path = self.material / "LICENSE-MIT"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_different_controlled_native_producer_is_rejected(self):
        path = self.root / "selected-receipt.json"
        path.write_text(
            json.dumps(
                {
                    "coreCommit": self.revision,
                    "inputs": {"producerReceipt": {"sha256": "b" * 64}},
                }
            )
        )
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_actual_current_controlled_guard_producer_is_accepted(self):
        path = self.root / "selected-current-receipt.json"
        path.write_text(json.dumps({"coreCommit": self.revision, "inputs": {"producerReceipt": {
            "sha256": CURRENT_PRODUCER_SHA256}}}))
        self.args.foxcore_sbom_receipt = path
        self.run_copy()

    def test_original_material_capture_subject_is_preserved_separately(self):
        value = json.loads((self.material / "PROVENANCE.json").read_bytes())
        self.assertEqual(value["nativeProducer"]["coreCommit"], self.revision)
        self.assertEqual(value["nativeProducer"]["producerReceiptSha256"],
                         CURRENT_PRODUCER_SHA256)
        original = value["archivalMaterialCaptureNativeProducer"]
        self.assertEqual(original["coreCommit"], "0ae41cc6ca61b9654d38b8ac65fc3d85ffdb2d3e")
        self.assertEqual(original["producerReceiptSha256"],
                         "776001fc7d7a3e7c481d765161a745fd6da828c4837740118a641edf4e016646")
        self.assertNotEqual(original["sourceArchiveSha256"], value["nativeProducer"]["sourceArchiveSha256"])
        self.assertEqual(original["imageId"], value["nativeProducer"]["imageId"])

    def test_historical_producer_is_not_accepted_as_current(self):
        path = self.root / "selected-old-producer.json"
        path.write_text(json.dumps({"coreCommit": self.revision, "inputs": {"producerReceipt": {
            "sha256": "776001fc7d7a3e7c481d765161a745fd6da828c4837740118a641edf4e016646"}}}))
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()


    def test_previous_current_producer_is_preserved_as_history(self):
        value = json.loads((self.material / "PROVENANCE.json").read_bytes())
        prior = value["historicalCurrentAttributions"][0]
        self.assertEqual(prior["provenanceSha256"], "27f8bd9b6f0a8c8a4ff09d56fba0bf015a69a48ed25295364c36978bf4c5e3a5")
        self.assertEqual(prior["nativeProducer"]["coreCommit"], "049706ede3d93dd98e300eb75d49ebe5c6cdac52")
        self.assertEqual(prior["nativeProducer"]["producerReceiptSha256"], "a74788db77973f68d6db23f5c49b983a9e266f9b25b05d148a32ace976c13a04")

    def test_previous_current_producer_is_not_accepted_for_new_core(self):
        path = self.root / "selected-prior-current-producer.json"
        path.write_text(json.dumps({"coreCommit": self.revision, "inputs": {"producerReceipt": {
            "sha256": "a74788db77973f68d6db23f5c49b983a9e266f9b25b05d148a32ace976c13a04"}}}))
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_previous_e4_attribution_is_preserved_but_rejected_for_current(self):
        prior = self.historical_attribution(
            "e4ba3758ee79c9a40f8e3ae31c0da72da2c44026",
            "baa8d749d8e93179dd76618db6f07bc2078e3d0544ff8a5082b8befe68170d25",
        )
        path = self.root / "selected-e4-producer.json"
        path.write_text(json.dumps({"coreCommit": self.revision, "inputs": {"producerReceipt": {
            "sha256": prior["nativeProducer"]["producerReceiptSha256"]}}}))
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_previous_13b_attribution_is_preserved_but_rejected_for_current(self):
        prior = self.historical_attribution(PREVIOUS_CORE_COMMIT, PREVIOUS_PROVENANCE_SHA256)
        self.assertEqual(prior["nativeProducer"]["producerReceiptSha256"], PREVIOUS_PRODUCER_SHA256)
        path = self.root / "selected-13b-producer.json"
        path.write_text(json.dumps({"coreCommit": self.revision, "inputs": {"producerReceipt": {
            "sha256": PREVIOUS_PRODUCER_SHA256}}}))
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy()

    def test_previous_13b_pin_cannot_use_current_producer(self):
        path = self.root / "selected-current-producer-with-13b-pin.json"
        path.write_text(json.dumps({"coreCommit": PREVIOUS_CORE_COMMIT, "inputs": {"producerReceipt": {
            "sha256": CURRENT_PRODUCER_SHA256}}}))
        self.args.foxcore_sbom_receipt = path
        with self.assertRaises((ValueError, SystemExit)):
            self.run_copy(revision=PREVIOUS_CORE_COMMIT)

if __name__ == "__main__":
    unittest.main()
