import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).with_name("fixtures") / "sqlite-fork"


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SQLITE = load("sqlite_legal_origin", "sqlite-fork-origin.py")
SELECTED = load("sqlite_selected", "selected-license-sbom.py")
DELIVERY = load("sqlite_delivery", "aggregate-delivery-sbom.py")
REVISION = "a" * 40


class SQLiteLegalTest(unittest.TestCase):
    def fixture(self):
        files = {SQLITE.NOTICE_PREFIX + path.name: path.read_bytes() for path in FIXTURE.iterdir()}
        origin = json.loads(files[SQLITE.NOTICE_PREFIX + "ORIGIN.json"])
        pins = {"vendor/libsqlite3-sys/" + row["path"]: row["sha256"] for row in origin["vendorFiles"]}
        for name, original in (("ORIGIN.json", SQLITE.METADATA),
                               ("upstream-security.patch", origin["patchPath"]),
                               ("FOXHOLE-MODIFICATIONS.md", "config/source-forks/libsqlite3-sys-0.37.0.NOTICE.md")):
            pins[original] = hashlib.sha256(files[SQLITE.NOTICE_PREFIX + name]).hexdigest()
        proof = SQLITE.validate_packaged_origin(files.__getitem__, pins)
        return files, pins, proof

    def graph(self):
        _, _, proof = self.fixture()
        proof["sourceBound"] = True
        root_id = "path+file:///core/crates/foxcore-android#1.0.4"
        sqlite_id = "path+file:///core/vendor/libsqlite3-sys#0.37.0"
        root_ref = "urn:foxhole:cargo:" + hashlib.sha256(root_id.encode()).hexdigest()
        sqlite_ref = "urn:foxhole:cargo:" + hashlib.sha256(sqlite_id.encode()).hexdigest()
        root = {"type": "library", "name": "foxcore-android", "version": "1.0.4", "bom-ref": root_ref,
                "purl": SELECTED.local_purl("foxcore-android", "1.0.4", REVISION),
                "licenses": [{"expression": "GPL-3.0-or-later"}],
                "properties": [{"name": "cargo:workspace-manifest", "value": "crates/foxcore-android/Cargo.toml"},
                               {"name": "cargo:source-revision", "value": REVISION}]}
        component = {"type": "library", "name": "libsqlite3-sys", "version": "0.37.0", "bom-ref": sqlite_ref,
                     "purl": SQLITE.identity_url(proof, REVISION), "licenses": [{"expression": "MIT"}],
                     "properties": SQLITE.component_properties(proof, REVISION)}
        bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
               "metadata": {"component": root, "properties": [
                   {"name": "foxhole:native:core-revision", "value": REVISION},
                   {"name": "foxhole:native:explicit-workspace-components", "value": "1"},
                   {"name": "foxhole:native:vendored-fork-components", "value": "1"}]},
               "components": [component], "dependencies": [{"ref": root_ref, "dependsOn": [sqlite_ref]},
                                                            {"ref": sqlite_ref, "dependsOn": []}]}
        metadata = {"workspace_root": "/core", "workspace_members": [root_id], "packages": [
            {"id": root_id, "name": "foxcore-android", "version": "1.0.4", "source": None,
             "manifest_path": "/core/crates/foxcore-android/Cargo.toml"},
            {"id": sqlite_id, "name": "libsqlite3-sys", "version": "0.37.0", "source": None,
             "manifest_path": "/core/vendor/libsqlite3-sys/Cargo.toml"}],
            "resolve": {"nodes": [{"id": root_id, "deps": [{"pkg": sqlite_id, "dep_kinds": [{"kind": None}]}]},
                                  {"id": sqlite_id, "deps": []}]}}
        return bom, metadata, proof

    def test_exact_packaged_origin_and_captured_graph_are_accepted(self):
        bom, metadata, proof = self.graph()
        SELECTED.validate_graph(bom, metadata, REVISION, "1.0.4", sqlite_origin=proof)
        DELIVERY.expand_core_workspace(bom, REVISION, sqlite_origin=proof)

    def test_changed_patch_or_compiled_source_pin_is_refused(self):
        files, pins, _ = self.fixture()
        changed = dict(files)
        changed[SQLITE.NOTICE_PREFIX + "upstream-security.patch"] += b"\n"
        with self.assertRaises(ValueError):
            SQLITE.validate_packaged_origin(changed.__getitem__, pins)
        altered = dict(pins)
        altered["vendor/libsqlite3-sys/sqlite3/sqlite3.c"] = "0" * 64
        with self.assertRaises(ValueError):
            SQLITE.validate_packaged_origin(files.__getitem__, altered)

    def test_rehashed_forged_effective_source_inventory_is_refused(self):
        files, pins, _ = self.fixture()
        metadata = json.loads(files[SQLITE.NOTICE_PREFIX + "ORIGIN.json"])
        metadata["vendorFiles"][0]["sha256"] = "0" * 64
        files[SQLITE.NOTICE_PREFIX + "ORIGIN.json"] = json.dumps(metadata).encode()
        pins[SQLITE.METADATA] = hashlib.sha256(files[SQLITE.NOTICE_PREFIX + "ORIGIN.json"]).hexdigest()
        with self.assertRaises(ValueError):
            SQLITE.validate_packaged_origin(files.__getitem__, pins)

    def test_registry_or_workspace_override_cannot_inherit_the_fork(self):
        for mutation in ("registry", "workspace", "manifest"):
            with self.subTest(mutation=mutation):
                bom, metadata, proof = self.graph()
                package = metadata["packages"][1]
                if mutation == "registry":
                    package["source"] = "registry+https://github.com/rust-lang/crates.io-index"
                elif mutation == "workspace":
                    metadata["workspace_members"].append(package["id"])
                else:
                    package["manifest_path"] = "/core/other/Cargo.toml"
                with self.assertRaises(ValueError):
                    SELECTED.validate_graph(bom, metadata, REVISION, "1.0.4", sqlite_origin=proof)

    def test_missing_origin_or_duplicate_provenance_is_refused(self):
        bom, metadata, proof = self.graph()
        with self.assertRaises(ValueError):
            SELECTED.validate_graph(bom, metadata, REVISION, "1.0.4")
        bom["components"][0]["properties"].append(copy.deepcopy(bom["components"][0]["properties"][0]))
        with self.assertRaises(ValueError):
            SQLITE.validate_component(bom["components"][0], proof, REVISION)

    def test_source_fork_license_cannot_be_relabelled(self):
        bom, metadata, proof = self.graph()
        bom["components"][0]["licenses"] = [{"expression": "Apache-2.0"}]
        with self.assertRaises(ValueError):
            SELECTED.validate_graph(bom, metadata, REVISION, "1.0.4", sqlite_origin=proof)
        with self.assertRaises(ValueError):
            DELIVERY.expand_core_workspace(bom, REVISION, sqlite_origin=proof)

    def test_source_archive_traversal_and_links_are_refused(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("symlink", tarfile.SYMTYPE)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "source.tar"
                with tarfile.open(path, "w") as archive:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    if kind == tarfile.SYMTYPE:
                        member.linkname = "Cargo.toml"
                    archive.addfile(member, io.BytesIO())
                with self.assertRaises(ValueError):
                    SELECTED.load_sqlite_origin(path, {"files": {}})

    def test_notice_component_ownership_is_required(self):
        files, _, proof = self.fixture()
        ref = "captured-sqlite-component"
        files["MANIFEST.json"] = json.dumps({"files": [{"path": path, "owners": [ref]} for path in files]}).encode()
        SELECTED.validate_sqlite_notices(list(files.items()), proof, ref)
        with self.assertRaises(ValueError):
            SELECTED.validate_sqlite_notices(list(files.items()), proof, "different-component")


if __name__ == "__main__":
    unittest.main()
