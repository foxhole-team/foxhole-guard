import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "selected_licenses", Path(__file__).resolve().parents[1] / "selected-sentinel-sbom.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
GENERATOR_SPEC = importlib.util.spec_from_file_location(
    "license_generator_inventory", Path(__file__).resolve().parents[1] / "generate-license-assets.py"
)
GENERATOR = importlib.util.module_from_spec(GENERATOR_SPEC)
GENERATOR_SPEC.loader.exec_module(GENERATOR)


class SelectedSentinelSbomTest(unittest.TestCase):
    def setUp(self):
        SPEC.loader.exec_module(MODULE)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.revision = "a" * 40
        self.lock = b"actual locked source bytes\n"
        self.inputs = {}
        self.manifests = {"foxhole-sentinel-yara": b'[package]\nname="foxhole-sentinel-yara"\nversion="0.1.0"\n',
                          "yara-x": b'[package]\nname="yara-x"\nversion="1.21.0"\n',
                          "yara-x-parser": b'[package]\nname="yara-x-parser"\nversion="1.21.0"\n'}
        paths = {"foxhole-sentinel-yara":"native/sentinel-yara/Cargo.toml", "yara-x":"native/sentinel-yara/vendor/yara-x/Cargo.toml", "yara-x-parser":"native/sentinel-yara/vendor/yara-x-parser/Cargo.toml"}
        self.manifest_hashes = {paths[name]:self.hash(body) for name,body in self.manifests.items()}
        self.archive = self.put("sourceArchive", b"original producer archive")
        self.library = self.put("nativeLibrary", b"actual library bytes")
        self.tree = self.put("cargoTree", b"0|foxhole-sentinel-yara v0.1.0 (/core/native/sentinel-yara)|\n1|dep v2.0.0|\n")
        metadata = {"packages": [
            {"id": "path+file:///core#foxhole-sentinel-yara@0.1.0", "name": "foxhole-sentinel-yara",
             "version": "0.1.0", "source": None, "manifest_path": "/core/native/sentinel-yara/Cargo.toml"},
            {"id": "registry+https://github.com/rust-lang/crates.io-index#dep@2.0.0",
             "name": "dep", "version": "2.0.0", "source": "registry+https://github.com/rust-lang/crates.io-index"},
        ]}
        root_id, child_id = (item["id"] for item in metadata["packages"])
        metadata["resolve"] = {"nodes": [
            {"id": root_id, "deps": [{"pkg": child_id, "dep_kinds": [{"kind": None}]}]},
            {"id": child_id, "deps": []},
        ]}
        self.metadata = self.put("cargoMetadata", metadata)
        refs = ["urn:foxhole:cargo:" + self.hash(item["id"].encode()) for item in metadata["packages"]]
        self.original = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"type": "library", "name": "foxhole-sentinel-yara", "version": "0.1.0",
                "bom-ref": refs[0], "licenses": [{"expression": "GPL-3.0-or-later"}], "properties": [
                    {"name": "cargo:source-revision", "value": self.revision},
                    {"name": "cargo:workspace-manifest", "value": "Cargo.toml"}]},
                "properties": [{"name": "foxhole:native:" + key, "value": value} for key, value in {
                    "inventory": "public-sentinel-own-workspace-selected-normal-build-closure",
                    "target": "aarch64-linux-android", "core-revision": self.revision, "features": "",
                    "tree-sha256": self.tree["sha256"], "metadata-sha256": self.metadata["sha256"],
                    "cargo-lock-sha256": self.hash(self.lock)}.items()]},
            "components": [{"type": "library", "name": "dep", "version": "2.0.0", "bom-ref": refs[1],
                "purl": "pkg:cargo/dep@2.0.0", "licenses": [{"expression": "MIT"}]}],
            "dependencies": [{"ref": refs[0], "dependsOn": [refs[1]]}, {"ref": refs[1], "dependsOn": []}]}
        self.put("originalSbom", self.original)
        self.view = copy.deepcopy(self.original)
        self.view["metadata"]["component"]["purl"] = MODULE.local_purl("foxhole-sentinel-yara", "0.1.0", self.revision)
        self.view["metadata"]["properties"] += MODULE.delivery_properties(1, self.inputs["originalSbom"]["sha256"])
        self.sbom = self.root / "selected.cdx.json"
        self.sbom.write_text(json.dumps(self.view))
        self.notice_rows = []
        for name, owner in (("LICENSE-core", refs[0]), ("LICENSE-dep", refs[1])):
            content = ("Original " + name + " legal bytes\n").encode()
            (self.root / name).write_bytes(content)
            self.notice_rows.append({"path": name, "sha256": self.hash(content), "owners": [owner]})
        self.put("cargoNoticeManifest", {"schema_version": 1, "coverage": "complete", "uncovered": [],
                                         "sbom_sha256": self.hash(self.sbom.read_bytes()), "files": self.notice_rows})
        producer = {"exitCode": 0, "coreCommit": self.revision, "consumer": "sentinel", "features": "sentinel-v2",
            "sourceMutations": [], "consumerInputMutations": [], "sourceArchiveSha256": self.archive["sha256"],
            "libraries": [{"path": "arm64-v8a/libfoxhole_sentinel_yara.so", "bytes": len(b"actual library bytes"),
                "sha256": self.library["sha256"]}], "abis":["arm64-v8a","armeabi-v7a","x86","x86_64"], "staticLoadAndRelroGate":"pass"}
        producer["libraries"] += [{"path":abi+"/libfoxhole_sentinel_yara.so", "bytes":5,"sha256":"f"*64} for abi in ("armeabi-v7a","x86","x86_64")]
        self.put("producerReceipt", producer)
        self.put("sourceManifest", {"archiveSha256": self.archive["sha256"], "files": {"native/sentinel-yara/Cargo.lock": self.hash(self.lock), **self.manifest_hashes}})
        self.put("captureReceipt", {"status": "selected-cargo-capture-complete", "consumer": "sentinel",
            "features": "sentinel-v2", "coreCommit": self.revision, "cargoLockSha256": self.hash(self.lock),
            "producerReceiptSha256": self.inputs["producerReceipt"]["sha256"],
            "sourceArchiveSha256": self.archive["sha256"], "steps": [{"label": "sbom-aarch64-linux-android", "exit_code": 0}]})
        self.put("targetCapture", {"schema_version": 1, "target": "aarch64-linux-android", "features": [],
            "core_revision": self.revision, "cargo_lock_sha256": self.hash(self.lock),
            "metadata": {"sha256": self.metadata["sha256"], "exit_code": 0},
            "tree": {"sha256": self.tree["sha256"], "exit_code": 0}})
        self.receipt = {"schema": 1, "coreCommit": self.revision, "consumer": "sentinel", "target": "aarch64-linux-android",
            "features": "sentinel-v2", "sbomSha256": self.hash(self.sbom.read_bytes()), "inputs": self.inputs}
        self.receipt_path = self.root / "receipt.json"
        self.receipt_path.write_text(json.dumps(self.receipt))

    @staticmethod
    def hash(data):
        return hashlib.sha256(data).hexdigest()

    def put(self, name, value):
        data = value if isinstance(value, bytes) else json.dumps(value).encode()
        path = self.root / (name + ".input")
        path.write_bytes(data)
        record = {"path": path.name, "sha256": self.hash(data)}
        self.inputs[name] = record
        return record

    def validate(self):
        return MODULE._load_selected_target_sbom(self.sbom, self.receipt_path, self.revision, self.lock, self.manifests)

    def refresh_receipt(self):
        self.receipt["sbomSha256"] = self.hash(self.sbom.read_bytes())
        self.receipt_path.write_text(json.dumps(self.receipt))

    def test_exact_selected_view_accepts_original_producer_and_complete_closure(self):
        self.assertEqual(self.sbom.read_bytes(), self.validate())

    def test_different_pin_is_refused(self):
        with self.assertRaises(ValueError):
            MODULE._load_selected_target_sbom(self.sbom, self.receipt_path, "b" * 40, self.lock, self.manifests)


    def test_modified_library_source_archive_or_metadata_is_refused(self):
        for name in ("nativeLibrary", "sourceArchive", "cargoMetadata"):
            with self.subTest(name=name):
                path = self.root / self.inputs[name]["path"]
                previous = path.read_bytes()
                path.write_bytes(b"changed")
                with self.assertRaises(ValueError):
                    self.validate()
                path.write_bytes(previous)

    def test_failed_or_mutated_producer_is_refused_even_with_updated_receipt_hash(self):
        original = json.loads((self.root / self.inputs["producerReceipt"]["path"]).read_text())
        for field, value in (("exitCode", 1), ("exitCode", False), ("sourceMutations", ["Cargo.lock"]),
                             ("consumerInputMutations", ["app/build.gradle.kts"]), ("features", "vless")):
            with self.subTest(field=field):
                altered = {**original, field: value}
                self.put("producerReceipt", altered)
                capture = json.loads((self.root / self.inputs["captureReceipt"]["path"]).read_text())
                capture["producerReceiptSha256"] = self.inputs["producerReceipt"]["sha256"]
                self.put("captureReceipt", capture)
                self.refresh_receipt()
                with self.assertRaises(ValueError):
                    self.validate()

    def test_arbitrary_sbom_changes_are_refused_even_when_hash_is_refreshed(self):
        for mutation in ("version", "edges", "license", "unrelatedProperty"):
            with self.subTest(mutation=mutation):
                altered = copy.deepcopy(self.view)
                if mutation == "version":
                    altered["components"][0]["version"] = "3.0.0"
                elif mutation == "edges":
                    altered["dependencies"][0]["dependsOn"] = []
                elif mutation == "license":
                    altered["components"][0].pop("licenses")
                else:
                    altered["metadata"]["properties"].append({"name": "unexpected", "value": "yes"})
                self.sbom.write_text(json.dumps(altered))
                self.refresh_receipt()
                with self.assertRaises(ValueError):
                    self.validate()

    def test_escape_and_symlink_inputs_are_refused(self):
        original = copy.deepcopy(self.inputs["cargoTree"])
        self.inputs["cargoTree"]["path"] = "../escape"
        self.refresh_receipt()
        with self.assertRaises(ValueError):
            self.validate()
        self.inputs["cargoTree"] = original
        path = self.root / original["path"]
        data = path.read_bytes()
        path.unlink()
        target = self.root / "tree-original"
        target.write_bytes(data)
        path.symlink_to(target.name)
        self.refresh_receipt()
        with self.assertRaises(ValueError):
            self.validate()

    def test_disconnected_or_incomplete_original_graph_is_refused(self):
        for mutation in ("disconnected", "duplicate", "dangling"):
            with self.subTest(mutation=mutation):
                altered = copy.deepcopy(self.original)
                if mutation == "disconnected":
                    altered["dependencies"][0]["dependsOn"] = []
                elif mutation == "duplicate":
                    altered["dependencies"].append(altered["dependencies"][0])
                else:
                    altered["dependencies"][0]["dependsOn"].append("unknown")
                self.put("originalSbom", altered)
                view = copy.deepcopy(altered)
                view["metadata"]["component"]["purl"] = MODULE.local_purl("foxhole-sentinel-yara", "0.1.0", self.revision)
                view["metadata"]["properties"] += MODULE.delivery_properties(1, self.inputs["originalSbom"]["sha256"])
                self.sbom.write_text(json.dumps(view))
                self.refresh_receipt()
                with self.assertRaises(ValueError):
                    self.validate()


    def test_original_cargo_notice_bytes_are_bundled_and_tampering_is_refused(self):
        self.four_delivery()
        args = SimpleNamespace(sentinel_sbom=self.sbom, sentinel_sbom_receipt=self.receipt_path)
        output = self.root / "output"
        paths = {"foxhole-sentinel-yara":"native/sentinel-yara/Cargo.toml", "yara-x":"native/sentinel-yara/vendor/yara-x/Cargo.toml", "yara-x-parser":"native/sentinel-yara/vendor/yara-x-parser/Cargo.toml"}
        blobs = {paths[name]:body for name,body in self.manifests.items()}
        blobs.update({"native/sentinel-yara/Cargo.lock":self.lock, "native/sentinel-yara/LICENSE":b"Original Sentinel license", "native/sentinel-yara/README.md":b"Source attribution", "native/sentinel-yara/vendor/PROVENANCE.json":b"{}"})
        with patch.object(GENERATOR, "core_delivery_profile", return_value="legacy4"), patch.object(GENERATOR, "git_blob", side_effect=lambda root, revision, name:blobs[name]), patch.object(GENERATOR.subprocess, "run", return_value=SimpleNamespace(stdout="")):
            GENERATOR.copy_sentinel_material(output, args, self.root, self.revision)
        self.assertEqual((self.root / "LICENSE-dep").read_bytes(), (output / "sentinel/cargo-notices/LICENSE-dep").read_bytes())
        self.assertEqual((output/"sentinel/selected-aarch64-linux-android.cdx.json").read_bytes(),self.sbom.read_bytes())
        self.assertFalse((output/"sentinel/third-party").exists())
        self.assertEqual(len(list((output/"sentinel").glob("selected-*.cdx.json"))), 4)
        self.assertEqual(json.loads((output/"sentinel/ABI-COVERAGE.json").read_bytes())["unionComponents"], 2)
        (self.root / "LICENSE-dep").write_bytes(b"replaced")
        with self.assertRaises(ValueError):
            MODULE.load_selected_delivery(self.sbom, self.receipt_path, self.revision, self.lock, self.manifests)

    def test_notice_coverage_cannot_omit_a_selected_package(self):
        self.put("cargoNoticeManifest", {"schema_version": 1, "coverage": "complete", "uncovered": [],
                                         "sbom_sha256": self.hash(self.sbom.read_bytes()), "files": self.notice_rows[:1]})
        self.refresh_receipt()
        with self.assertRaisesRegex(ValueError, "omits selected"):
            self.validate()

    def test_reachable_graph_cannot_drop_a_transitive_edge_present_in_actual_target_tree(self):
        metadata = json.loads((self.root / self.inputs["cargoMetadata"]["path"]).read_text())
        third = {"id": "registry+https://github.com/rust-lang/crates.io-index#third@3.0.0",
                 "name": "third", "version": "3.0.0", "source": "registry+https://github.com/rust-lang/crates.io-index"}
        metadata["packages"].append(third)
        dep = {"pkg": third["id"], "dep_kinds": [{"kind": None}]}
        metadata["resolve"]["nodes"][0]["deps"].append(dep)
        metadata["resolve"]["nodes"][1]["deps"].append(dep)
        metadata["resolve"]["nodes"].append({"id": third["id"], "deps": []})
        self.put("cargoMetadata", metadata)
        self.put("cargoTree", b"0|foxhole-sentinel-yara v0.1.0 (/core/native/sentinel-yara)|\n"
                             b"1|dep v2.0.0|\n2|third v3.0.0|\n1|third v3.0.0|\n")
        target = json.loads((self.root / self.inputs["targetCapture"]["path"]).read_text())
        target["tree"]["sha256"] = self.inputs["cargoTree"]["sha256"]
        target["metadata"]["sha256"] = self.inputs["cargoMetadata"]["sha256"]
        self.put("targetCapture", target)
        original = copy.deepcopy(self.original)
        ref = "urn:foxhole:cargo:" + self.hash(third["id"].encode())
        original["components"].append({"name": "third", "version": "3.0.0", "type": "library", "bom-ref": ref,
                                       "purl": "pkg:cargo/third@3.0.0", "licenses": [{"expression": "MIT"}]})
        original["dependencies"][0]["dependsOn"].append(ref)
        original["dependencies"].append({"ref": ref, "dependsOn": []})
        for row in original["metadata"]["properties"]:
            if row["name"] == "foxhole:native:tree-sha256":
                row["value"] = self.inputs["cargoTree"]["sha256"]
            elif row["name"] == "foxhole:native:metadata-sha256":
                row["value"] = self.inputs["cargoMetadata"]["sha256"]
        self.put("originalSbom", original)
        view = copy.deepcopy(original)
        view["metadata"]["component"]["purl"] = MODULE.local_purl("foxhole-sentinel-yara", "0.1.0", self.revision)
        view["metadata"]["properties"] += MODULE.delivery_properties(1, self.inputs["originalSbom"]["sha256"])
        self.sbom.write_text(json.dumps(view))
        self.refresh_receipt()
        with self.assertRaisesRegex(ValueError, "actual target's captured tree"):
            self.validate()





    def test_pinned_sentinel_manifest_drift_is_refused(self):
        changed = {**self.manifests, "foxhole-sentinel-yara": self.manifests["foxhole-sentinel-yara"].replace(b"0.1.0", b"0.2.0")}
        with self.assertRaises(ValueError):
            MODULE._load_selected_target_sbom(self.sbom, self.receipt_path, self.revision, self.lock, changed)

    def test_stale_legacy_sentinel_material_cannot_be_copied(self):
        values = {"native/sentinel-yara/Cargo.lock": b'[[package]]\nname="wasmtime"\nversion="49.0.3"\n',
                  "native/sentinel-yara/third-party/licenses.json": b'[{"name":"wasmtime","version":"49.0.2"}]'}
        with patch.object(GENERATOR, "git_blob", side_effect=lambda root, rev, name: values[name]):
            with self.assertRaises(ValueError):
                GENERATOR.copy_sentinel_material(self.root/"output", SimpleNamespace(), self.root, self.revision)
        self.assertFalse((self.root/"output").exists())

    def test_generator_requires_complete_sentinel_input_pair(self):
        for args in (SimpleNamespace(sentinel_sbom=self.sbom), SimpleNamespace(sentinel_sbom_receipt=self.receipt_path)):
            with self.assertRaises(ValueError):
                GENERATOR.copy_sentinel_material(self.root/"output", args, self.root, self.revision)


    def four_delivery(self):
        shared_inputs = copy.deepcopy(self.inputs)
        producer = json.loads((self.root / self.inputs["producerReceipt"]["path"]).read_bytes())
        producer["libraries"] = []
        for target, abi in MODULE.TARGET_ABIS.items():
            body = ("actual native " + abi).encode()
            path = self.root / (target + ".so")
            path.write_bytes(body)
            producer["libraries"].append({"path": abi + "/libfoxhole_sentinel_yara.so",
                                          "sha256": self.hash(body), "bytes": len(body)})
        self.put("producerReceipt", producer)
        capture = json.loads((self.root / self.inputs["captureReceipt"]["path"]).read_bytes())
        capture["producerReceiptSha256"] = self.inputs["producerReceipt"]["sha256"]
        capture["steps"] = [{"label": "sbom-" + target, "exit_code": 0} for target in MODULE.TARGET_ABIS]
        self.put("captureReceipt", capture)
        shared_inputs.update({name: copy.deepcopy(self.inputs[name]) for name in ("producerReceipt", "captureReceipt")})
        validated, target_rows = {}, {}
        for target, abi in MODULE.TARGET_ABIS.items():
            original = copy.deepcopy(self.original)
            for prop in original["metadata"]["properties"]:
                if prop["name"] == "foxhole:native:target":
                    prop["value"] = target
            original_path = self.root / (target + ".original.json")
            original_path.write_bytes(MODULE.encoded(original))
            view = copy.deepcopy(original)
            view["metadata"]["component"]["purl"] = MODULE.local_purl("foxhole-sentinel-yara", "0.1.0", self.revision)
            view["metadata"]["properties"] += MODULE.delivery_properties(1, self.hash(original_path.read_bytes()))
            sbom = self.root / (target + ".cdx.json")
            sbom.write_bytes(MODULE.encoded(view))
            target_capture = json.loads((self.root / shared_inputs["targetCapture"]["path"]).read_bytes())
            target_capture["target"] = target
            target_path = self.root / (target + ".capture.json")
            target_path.write_bytes(MODULE.encoded(target_capture))
            manifest = self.root / (target + ".notices.json")
            manifest.write_bytes(MODULE.encoded({"schema_version": 1, "coverage": "complete", "uncovered": [],
                "sbom_sha256": self.hash(sbom.read_bytes()), "files": self.notice_rows}))
            inputs = copy.deepcopy(shared_inputs)
            for name, path in [("originalSbom", original_path), ("nativeLibrary", self.root / (target + ".so")),
                               ("targetCapture", target_path), ("cargoNoticeManifest", manifest)]:
                inputs[name] = {"path": path.name, "sha256": self.hash(path.read_bytes())}
            receipt = self.root / (target + ".inputs.json")
            receipt.write_bytes(MODULE.encoded({"schema": 1, "coreCommit": self.revision, "consumer": "sentinel",
                "target": target, "features": "sentinel-v2", "sbomSha256": self.hash(sbom.read_bytes()), "inputs": inputs}))
            validated[target] = {"data": sbom.read_bytes(), "receipt": receipt}
            target_rows[target] = {"receipt": {"path": receipt.name, "sha256": self.hash(receipt.read_bytes())},
                                   "sbom": {"path": sbom.name, "sha256": self.hash(sbom.read_bytes())}}
        material = MODULE.combined_evidence(validated, self.revision)
        coverage = self.root / "ABI-COVERAGE.json"
        coverage.write_bytes(material["coverage"])
        union = self.root / "UNION.json"
        union.write_bytes(material["notices"][0][1])
        primary = json.loads(validated[MODULE.PRIMARY_TARGET]["receipt"].read_bytes())
        self.receipt = {**primary, "schema": 2, "targets": target_rows,
            "abiCoverage": {"path": coverage.name, "sha256": self.hash(coverage.read_bytes())},
            "unionNoticeManifest": {"path": union.name, "sha256": self.hash(union.read_bytes())}}
        self.receipt_path.write_bytes(MODULE.encoded(self.receipt))
        self.sbom = self.root / (MODULE.PRIMARY_TARGET + ".cdx.json")
        return material

    def whole_validate(self):
        return MODULE.load_selected_delivery(self.sbom, self.receipt_path, self.revision, self.lock, self.manifests)

    def test_four_abi_producer_cannot_admit_only_arm64_inventory(self):
        with self.assertRaisesRegex(ValueError, "Four-target"):
            self.whole_validate()

    def test_four_distinct_graphs_and_original_notice_union_are_admitted(self):
        expected = self.four_delivery()
        self.assertEqual(expected, self.whole_validate())
        self.assertEqual(set(expected["targets"]), set(MODULE.TARGET_ABIS))
        certificate = json.loads(expected["coverage"])
        self.assertEqual({row["abi"] for row in certificate["targets"].values()}, set(MODULE.TARGET_ABIS.values()))

    def test_one_missing_target_is_refused_even_when_other_three_are_valid(self):
        self.four_delivery()
        del self.receipt["targets"]["armv7-linux-androideabi"]
        self.receipt_path.write_bytes(MODULE.encoded(self.receipt))
        with self.assertRaisesRegex(ValueError, "Exact Sentinel delivery target receipts are required"):
            self.whole_validate()

    def test_nonprimary_native_library_and_original_graph_tampering_are_refused(self):
        self.four_delivery()
        for name in ("i686-linux-android.so", "armv7-linux-androideabi.original.json"):
            with self.subTest(name=name):
                path = self.root / name
                previous = path.read_bytes()
                path.write_bytes(b"changed")
                with self.assertRaises(ValueError):
                    self.whole_validate()
                path.write_bytes(previous)

    def test_union_certificate_and_notice_owner_omission_are_refused_with_refreshed_hash(self):
        self.four_delivery()
        for key in ("abiCoverage", "unionNoticeManifest"):
            with self.subTest(key=key):
                row = self.receipt[key]
                path = self.root / row["path"]
                previous = path.read_bytes()
                value = json.loads(previous)
                if key == "abiCoverage":
                    value["unionComponentRefs"].pop()
                else:
                    value["files"][0]["owners"] = []
                path.write_bytes(MODULE.encoded(value))
                row["sha256"] = self.hash(path.read_bytes())
                self.receipt_path.write_bytes(MODULE.encoded(self.receipt))
                with self.assertRaisesRegex(ValueError, "Computed"):
                    self.whole_validate()
                path.write_bytes(previous)
                row["sha256"] = self.hash(previous)
                self.receipt_path.write_bytes(MODULE.encoded(self.receipt))

    def test_cross_target_notice_conflicting_original_bytes_are_refused(self):
        self.four_delivery()
        target = "i686-linux-android"
        row = self.receipt["targets"][target]["receipt"]
        path = self.root / row["path"]
        receipt = json.loads(path.read_bytes())
        new_file = self.root / "conflicting-LICENSE"
        new_file.write_bytes(b"Different original publisher content")
        notice_path = self.root / receipt["inputs"]["cargoNoticeManifest"]["path"]
        manifest = json.loads(notice_path.read_bytes())
        # A target manifest can independently authenticate another file at the same relative destination.
        sub = self.root / "isolated-notices"
        sub.mkdir()
        for item in manifest["files"]:
            (sub / item["path"]).write_bytes((self.root / item["path"]).read_bytes())
        first = manifest["files"][0]
        (sub / first["path"]).write_bytes(new_file.read_bytes())
        first["sha256"] = self.hash(new_file.read_bytes())
        relocated = sub / "MANIFEST.json"
        relocated.write_bytes(MODULE.encoded(manifest))
        receipt["inputs"]["cargoNoticeManifest"] = {"path": "isolated-notices/MANIFEST.json", "sha256": self.hash(relocated.read_bytes())}
        path.write_bytes(MODULE.encoded(receipt))
        row["sha256"] = self.hash(path.read_bytes())
        self.receipt_path.write_bytes(MODULE.encoded(self.receipt))
        with self.assertRaisesRegex(ValueError, "notice bytes collide"):
            self.whole_validate()

    def test_non_arm64_component_is_in_union_and_keeps_its_own_graph(self):
        self.four_delivery()
        target = "armv7-linux-androideabi"
        row = self.receipt["targets"][target]
        receipt_path = self.root / row["receipt"]["path"]
        receipt = json.loads(receipt_path.read_bytes())
        inputs = receipt["inputs"]
        metadata = json.loads((self.root / inputs["cargoMetadata"]["path"]).read_bytes())
        extra = {"id": "registry+https://github.com/rust-lang/crates.io-index#arm-only@1.0.0",
                 "name": "arm-only", "version": "1.0.0", "source": "registry+https://github.com/rust-lang/crates.io-index"}
        metadata["packages"].append(extra)
        metadata["resolve"]["nodes"][0]["deps"].append({"pkg": extra["id"], "dep_kinds": [{"kind": None}]})
        metadata["resolve"]["nodes"].append({"id": extra["id"], "deps": []})
        tree = (self.root / inputs["cargoTree"]["path"]).read_bytes() + b"1|arm-only v1.0.0|\n"
        for name, data in [("cargoMetadata", MODULE.encoded(metadata)), ("cargoTree", tree)]:
            path = self.root / (target + "-" + name)
            path.write_bytes(data)
            inputs[name] = {"path": path.name, "sha256": self.hash(data)}
        capture_path = self.root / inputs["targetCapture"]["path"]
        capture = json.loads(capture_path.read_bytes())
        for kind, name in [("metadata", "cargoMetadata"), ("tree", "cargoTree")]:
            capture[kind]["sha256"] = inputs[name]["sha256"]
        capture_path.write_bytes(MODULE.encoded(capture))
        inputs["targetCapture"]["sha256"] = self.hash(capture_path.read_bytes())
        original_path = self.root / inputs["originalSbom"]["path"]
        original = json.loads(original_path.read_bytes())
        ref = "urn:foxhole:cargo:" + self.hash(extra["id"].encode())
        original["components"].append({"type": "library", "bom-ref": ref, "name": "arm-only", "version": "1.0.0",
                                      "purl": "pkg:cargo/arm-only@1.0.0", "licenses": [{"expression": "MIT"}]})
        original["dependencies"][0]["dependsOn"].append(ref)
        original["dependencies"].append({"ref": ref, "dependsOn": []})
        for prop in original["metadata"]["properties"]:
            if prop["name"] == "foxhole:native:tree-sha256":
                prop["value"] = inputs["cargoTree"]["sha256"]
            elif prop["name"] == "foxhole:native:metadata-sha256":
                prop["value"] = inputs["cargoMetadata"]["sha256"]
        original_path.write_bytes(MODULE.encoded(original))
        inputs["originalSbom"]["sha256"] = self.hash(original_path.read_bytes())
        view = copy.deepcopy(original)
        view["metadata"]["component"]["purl"] = MODULE.local_purl("foxhole-sentinel-yara", "0.1.0", self.revision)
        view["metadata"]["properties"] += MODULE.delivery_properties(1, inputs["originalSbom"]["sha256"])
        sbom_path = self.root / row["sbom"]["path"]
        sbom_path.write_bytes(MODULE.encoded(view))
        row["sbom"]["sha256"] = receipt["sbomSha256"] = self.hash(sbom_path.read_bytes())
        extra_license = self.root / "LICENSE-arm-only"
        extra_license.write_bytes(b"Original arm-only license\n")
        manifest_path = self.root / inputs["cargoNoticeManifest"]["path"]
        manifest = json.loads(manifest_path.read_bytes())
        manifest["sbom_sha256"] = receipt["sbomSha256"]
        manifest["files"].append({"path": extra_license.name, "sha256": self.hash(extra_license.read_bytes()), "owners": [ref]})
        manifest_path.write_bytes(MODULE.encoded(manifest))
        inputs["cargoNoticeManifest"]["sha256"] = self.hash(manifest_path.read_bytes())
        receipt_path.write_bytes(MODULE.encoded(receipt))
        row["receipt"]["sha256"] = self.hash(receipt_path.read_bytes())
        targets = {name: {"data": (self.root / value["sbom"]["path"]).read_bytes(),
                          "receipt": self.root / value["receipt"]["path"]} for name, value in self.receipt["targets"].items()}
        material = MODULE.combined_evidence(targets, self.revision)
        for key, body in [("abiCoverage", material["coverage"]), ("unionNoticeManifest", material["notices"][0][1])]:
            (self.root / self.receipt[key]["path"]).write_bytes(body)
            self.receipt[key]["sha256"] = self.hash(body)
        self.receipt_path.write_bytes(MODULE.encoded(self.receipt))
        actual = self.whole_validate()
        coverage = json.loads(actual["coverage"])
        self.assertEqual(coverage["unionComponents"], 3)
        self.assertFalse(coverage["targets"][target]["componentSubsetOfPrimary"])
        self.assertIn(ref, coverage["unionComponentRefs"])
        self.assertNotIn(b'"name": "arm-only"', actual["targets"][MODULE.PRIMARY_TARGET])


    def test_android3_scope_counts_three_validated_graphs(self):
        self.four_delivery()
        selected = MODULE.delivery_targets("android3")
        targets = {target: {"data": (self.root / self.receipt["targets"][target]["sbom"]["path"]).read_bytes(),
                            "receipt": self.root / self.receipt["targets"][target]["receipt"]["path"]}
                   for target in selected}
        coverage = json.loads(MODULE.combined_evidence(targets, self.revision, "android3")["coverage"])
        self.assertEqual(set(coverage["targets"]), set(selected))
        self.assertEqual(len(coverage["targets"]), 3)
        self.assertNotIn("i686-linux-android", coverage["targets"])
        self.assertTrue(coverage["scope"].startswith("Three distinct android3 captured Cargo graphs"))

    def test_existing_profile_scope_counts_remain_exact(self):
        self.four_delivery()
        for profile, prefix in (("android64", "Two distinct android64"),
                                ("android-armv7", "One exact ARMv7"),
                                ("android4", "Four distinct android4"),
                                ("legacy4", "Four distinct captured")):
            with self.subTest(profile=profile):
                selected = MODULE.delivery_targets(profile)
                targets = {target: {"data": (self.root / self.receipt["targets"][target]["sbom"]["path"]).read_bytes(),
                                    "receipt": self.root / self.receipt["targets"][target]["receipt"]["path"]}
                           for target in selected}
                coverage = json.loads(MODULE.combined_evidence(targets, self.revision, profile)["coverage"])
                self.assertEqual(set(coverage["targets"]), set(selected))
                self.assertTrue(coverage["scope"].startswith(prefix))

    def test_android3_missing_graph_remains_refused(self):
        self.four_delivery()
        targets = {target: {"data": (self.root / row["sbom"]["path"]).read_bytes(),
                            "receipt": self.root / row["receipt"]["path"]}
                   for target, row in self.receipt["targets"].items()
                   if target in MODULE.delivery_targets("android3") and target != "armv7-linux-androideabi"}
        with self.assertRaises(ValueError):
            MODULE.combined_evidence(targets, self.revision, "android3")


if __name__ == "__main__":
    unittest.main()
