"""Guard metadata fixtures: authenticated public source, synthetic fork, no native claim."""
import copy
import difflib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE.parent / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

origin = load("fixture_origin", "rustls-fork-origin.py")
selected = load("fixture_selected", "selected-license-sbom.py")
aggregate = load("fixture_aggregate", "aggregate-delivery-sbom.py")
REVISION = "a" * 40

def sha(data): return hashlib.sha256(data).hexdigest()
def encoded(value): return json.dumps(value, sort_keys=True).encode()

def fixture():
 archive=(HERE/'fixtures/rustls-fork/rustls-0.23.46.crate').read_bytes();assert hashlib.sha256(archive).hexdigest()==origin.UPSTREAM_CRATE_SHA256
 original={}
 with tarfile.open(fileobj=io.BytesIO(archive))as tf:
  for member in tf:
   if member.isfile():original[member.name.removeprefix('rustls-0.23.46/')]=tf.extractfile(member).read()
 vendor=dict(original);vendor['Cargo.toml']=b'# FoxHole fixture modification\n'+vendor['Cargo.toml'];vendor['src/client/chrome_hello.rs']=b'// Synthetic fork fixture only.\n'
 patch=''
 for name in ['Cargo.toml','src/client/chrome_hello.rs']:
  patch+='diff --git a/'+name+' b/'+name+'\n'
  patch+=''.join(difflib.unified_diff(original.get(name,b'').decode().splitlines(True),vendor[name].decode().splitlines(True),fromfile='a/'+name if name in original else '/dev/null',tofile='b/'+name))
 upstream=(HERE/'fixtures/rustls-fork/upstream-files.json').read_bytes();assert origin.sha(upstream)==origin.UPSTREAM_MANIFEST_SHA256
 notice=b'FoxHole modified rustls 0.23.46 for a synthetic parser fixture. No TLS/native qualification.\n'
 files={origin.VENDOR_PATH+'/'+name:(data,0o644)for name,data in vendor.items()}
 files.update({origin.UPSTREAM_PATH:(upstream,0o644),origin.PATCH_PATH:(patch.encode(),0o644),origin.NOTICE_PATH:(notice,0o644)})
 rows=[{'path':path,'sha256':origin.sha(body),'bytes':len(body),'mode':mode}for path,(body,mode)in sorted(files.items())if path.startswith(origin.VENDOR_PATH+'/')]
 m={'schema':1,'status':'FROZEN','crate':'rustls','version':'0.23.46','sourceKind':'vendored-modified','vendorPath':origin.VENDOR_PATH,'registryCacheModified':False,'upstreamCrateSha256':origin.UPSTREAM_CRATE_SHA256,'upstreamManifest':{'path':origin.UPSTREAM_PATH,'sha256':origin.UPSTREAM_MANIFEST_SHA256},'vendorFiles':rows,'vendorTreeSha256':origin.sha(json.dumps({r['path']:{k:r[k]for k in('sha256','bytes','mode')}for r in rows},sort_keys=True).encode()),'patches':[{'path':origin.PATCH_PATH,'sha256':origin.sha(patch.encode())}],'licenses':[{'path':origin.VENDOR_PATH+'/'+n,'sha256':v[0],'bytes':v[1]}for n,v in sorted(origin.LICENSES.items())],'modificationNotice':{'path':origin.NOTICE_PATH,'sha256':origin.sha(notice)}}
 files[origin.ORIGIN_PATH]=((json.dumps(m,indent=2)+'\n').encode(),0o644)
 return files,m

class GuardForkLegalTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.files, self.origin_json = fixture()
        self.files["Cargo.lock"] = (b"Synthetic locked source fixture\n", 0o644)
        self.descriptor = origin.validate_origin(lambda p: self.files[p], self.files)
        self.lock = self.files["Cargo.lock"][0]
        self.inputs = {}
        root_id = "path+file:///core#foxcore-android@1.0.0"
        fork_id = "path+file:///core/vendor/rustls#0.23.46"
        self.refs = ["urn:foxhole:cargo:" + sha(p.encode()) for p in (root_id, fork_id)]
        self.metadata = {"workspace_root": "/core", "workspace_members": [root_id], "packages": [
            {"id": root_id, "name": "foxcore-android", "version": "1.0.0", "source": None,
             "manifest_path": "/core/crates/foxcore-android/Cargo.toml"},
            {"id": fork_id, "name": "rustls", "version": "0.23.46", "source": None,
             "manifest_path": "/core/vendor/rustls/Cargo.toml"}],
            "resolve": {"nodes": [{"id": root_id, "deps": [{"pkg": fork_id, "dep_kinds": [{"kind": None}]}]},
                                    {"id": fork_id, "deps": []}]}}
        self.original = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"type": "library", "name": "foxcore-android", "version": "1.0.0",
                "bom-ref": self.refs[0], "licenses": [{"expression": "GPL-3.0-or-later"}], "properties": [
                    {"name": "cargo:source-revision", "value": REVISION},
                    {"name": "cargo:workspace-manifest", "value": "crates/foxcore-android/Cargo.toml"}]}},
            "components": [{"type": "library", "name": "rustls", "version": "0.23.46", "bom-ref": self.refs[1],
                "purl": origin.identity_url(self.descriptor, REVISION),
                "licenses": [{"expression": origin.LICENSE_EXPRESSION}],
                "properties": origin.component_properties(self.descriptor, REVISION)}],
            "dependencies": [{"ref": self.refs[0], "dependsOn": [self.refs[1]]}, {"ref": self.refs[1], "dependsOn": []}]}
        self.refresh()

    def put(self, name, value):
        raw = value if isinstance(value, bytes) else encoded(value)
        path = self.root / (name + ".input")
        path.write_bytes(raw)
        self.inputs[name] = {"path": path.name, "sha256": sha(raw)}
        return self.inputs[name]["sha256"]

    def archive(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for path, (raw, mode) in sorted(self.files.items()):
                member = tarfile.TarInfo(path)
                member.size, member.mode = len(raw), mode
                archive.addfile(member, io.BytesIO(raw))
        return buffer.getvalue()

    def refresh(self):
        archive_hash = self.put("sourceArchive", self.archive())
        library = b"Synthetic metadata fixture, not an ELF\n"
        self.put("nativeLibrary", library)
        tree = b"0|foxcore-android v1.0.0 (/core/crates/foxcore-android)|\n1|rustls v0.23.46 (/core/vendor/rustls)|\n"
        tree_hash = self.put("cargoTree", tree)
        metadata_hash = self.put("cargoMetadata", self.metadata)
        self.original["metadata"]["properties"] = [{"name": "foxhole:native:" + k, "value": v} for k, v in {
            "inventory": "guard-shipped-cargo-selected-normal-build-closure", "core-revision": REVISION,
            "target": "aarch64-linux-android", "features": "shipped", "cargo-lock-sha256": sha(self.lock),
            "tree-sha256": tree_hash, "metadata-sha256": metadata_hash}.items()]
        original_hash = self.put("originalSbom", self.original)
        self.view = copy.deepcopy(self.original)
        self.view["metadata"]["component"]["purl"] = selected.local_purl("foxcore-android", "1.0.0", REVISION)
        self.view["metadata"]["properties"] += selected.delivery_properties(1, original_hash)
        self.view["metadata"]["properties"].append({"name": "foxhole:native:vendored-fork-components", "value": "1"})
        self.sbom = self.root / "selected.cdx.json"
        self.sbom.write_bytes(encoded(self.view))
        rows = []
        for path, raw in [("LICENSE-core", b"Synthetic Core license fixture\n")] + self.descriptor["noticeFiles"]:
            output = self.root / path
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(raw)
            owner = self.refs[0] if path == "LICENSE-core" else self.refs[1]
            rows.append({"path": path, "sha256": sha(raw), "owners": [owner]})
        self.put("cargoNoticeManifest", {"schema_version": 1, "coverage": "complete", "uncovered": [],
            "sbom_sha256": sha(self.sbom.read_bytes()), "files": rows})
        producer = {"exitCode": 0, "coreCommit": REVISION, "consumer": "guard", "features": "shipped",
            "sourceMutations": [], "consumerInputMutations": [], "sourceArchiveSha256": archive_hash,
            "staticLoadAndRelroGate": "pass", "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
            "libraries": [{"path": "arm64-v8a/libfoxhole_native.so", "sha256": sha(library), "bytes": len(library)}] +
                [{"path": a + "/libfoxhole_native.so", "sha256": "f" * 64, "bytes": 5} for a in ("armeabi-v7a", "x86", "x86_64")]}
        producer_hash = self.put("producerReceipt", producer)
        self.put("sourceManifest", {"archiveSha256": archive_hash, "files": {p: sha(raw) for p, (raw, _) in self.files.items()}})
        self.put("captureReceipt", {"status": "selected-cargo-capture-complete", "consumer": "guard", "features": "shipped",
            "coreCommit": REVISION, "cargoLockSha256": sha(self.lock), "producerReceiptSha256": producer_hash,
            "sourceArchiveSha256": archive_hash, "steps": [{"label": "sbom-aarch64-linux-android", "exit_code": 0}]})
        self.put("targetCapture", {"schema_version": 1, "target": "aarch64-linux-android", "features": ["shipped"],
            "core_revision": REVISION, "cargo_lock_sha256": sha(self.lock),
            "tree": {"sha256": tree_hash, "exit_code": 0}, "metadata": {"sha256": metadata_hash, "exit_code": 0}})
        self.receipt_path = self.root / "receipt.json"
        self.receipt_path.write_bytes(encoded({"schema": 1, "coreCommit": REVISION, "consumer": "guard",
            "target": "aarch64-linux-android", "features": "shipped", "inputs": self.inputs, "sbomSha256": sha(self.sbom.read_bytes())}))

    def validate(self):
        loader = getattr(selected, "_load_selected_target_sbom", selected.load_selected_sbom)
        return loader(self.sbom, self.receipt_path, REVISION, self.lock, "1.0.0")

    def test_source_bound_fork_uses_distinct_identity_and_count(self):
        self.assertEqual(self.sbom.read_bytes(), self.validate())
        self.assertEqual(self.refs[1], self.view["components"][0]["bom-ref"])

    def test_rehashed_vendor_change_without_patch_is_refused(self):
        path = "vendor/rustls/src/lib.rs"
        self.files[path] = (self.files[path][0] + b"// unexplained\n", 0o644)
        self.refresh()
        with self.assertRaises(ValueError): self.validate()

    def test_missing_origin_is_refused(self):
        self.files.pop(origin.ORIGIN_PATH)
        self.refresh()
        with self.assertRaises(ValueError): self.validate()

    def test_exact_path_version_and_nonworkspace_membership_required(self):
        for field, value in (("version", "1.0.0"), ("manifest_path", "/core/elsewhere/rustls/Cargo.toml")):
            with self.subTest(field=field):
                saved = self.metadata["packages"][1][field]
                self.metadata["packages"][1][field] = value
                self.refresh()
                with self.assertRaises(ValueError): self.validate()
                self.metadata["packages"][1][field] = saved
        self.metadata["workspace_members"].append(self.metadata["packages"][1]["id"])
        self.refresh()
        with self.assertRaises(ValueError): self.validate()

    def test_other_local_core_version_guard_is_preserved(self):
        self.original["metadata"]["component"]["version"] = "2.0.0"
        self.metadata["packages"][0]["version"] = "2.0.0"
        self.refresh()
        with self.assertRaisesRegex(ValueError, "pinned source version"): self.validate()

    def test_original_notice_cannot_be_replaced_with_rehashed_catalog(self):
        path = self.root / "source-forks/rustls-0.23.46/FOXHOLE-MODIFICATIONS.md"
        path.write_bytes(b"different notice\n")
        catalog = json.loads((self.root / self.inputs["cargoNoticeManifest"]["path"]).read_bytes())
        next(r for r in catalog["files"] if r["path"].endswith("FOXHOLE-MODIFICATIONS.md"))["sha256"] = sha(path.read_bytes())
        self.put("cargoNoticeManifest", catalog)
        receipt = json.loads(self.receipt_path.read_bytes()); receipt["inputs"] = self.inputs
        self.receipt_path.write_bytes(encoded(receipt))
        with self.assertRaises(ValueError): self.validate()

    def test_source_map_must_bind_all_vendor_bytes(self):
        source = json.loads((self.root / self.inputs["sourceManifest"]["path"]).read_bytes())
        source["files"]["vendor/rustls/src/lib.rs"] = "0" * 64
        self.put("sourceManifest", source)
        receipt = json.loads(self.receipt_path.read_bytes()); receipt["inputs"] = self.inputs
        self.receipt_path.write_bytes(encoded(receipt))
        with self.assertRaises(ValueError): self.validate()

    def test_aggregate_accepts_fork_only_after_packaged_pin_binding(self):
        notices = dict(self.descriptor["noticeFiles"])
        pins = {p: sha(raw) for p, (raw, _) in self.files.items()}
        packaged = origin.validate_packaged_origin(notices.__getitem__, pins)
        self.assertEqual(self.view, aggregate.expand_core_workspace(self.view, REVISION, packaged))
        with self.assertRaises(ValueError): aggregate.expand_core_workspace(self.view, REVISION)

    def test_aggregate_preserves_stock_registry_and_fork_as_two_nodes(self):
        notices = dict(self.descriptor["noticeFiles"])
        packaged = origin.validate_packaged_origin(notices.__getitem__, {p: sha(raw) for p, (raw, _) in self.files.items()})
        view = copy.deepcopy(self.view)
        stock = {"name": "rustls", "version": "0.23.46", "purl": "pkg:cargo/rustls@0.23.46", "bom-ref": "stock-registry-rustls"}
        view["components"].append(stock)
        view["dependencies"][0]["dependsOn"].append(stock["bom-ref"])
        view["dependencies"].append({"ref": stock["bom-ref"], "dependsOn": []})
        result = aggregate.expand_core_workspace(view, REVISION, packaged)
        self.assertEqual(2, len(result["components"]))

    def make_delivery_fixture(self):
        fixtures = load("delivery_fixture", "tests/test_delivery_sbom.py")
        delivery = fixtures.DeliverySbomTest()
        delivery.setUp()
        self.addCleanup(delivery.doCleanups)
        delivery.inventory["foxcore/foxcore-aarch64-linux-android.cdx.json"] = encoded(self.view)
        for path, raw in self.descriptor["noticeFiles"]:
            delivery.inventory["foxcore/cargo-notices/" + path] = raw
        pins = {"commit": REVISION, "files": [{"path": p, "sha256": sha(raw)} for p, (raw, _) in sorted(self.files.items())]}
        pins_path = delivery.root / "config/foxcore-source-manifest.json"
        pins_path.write_bytes(encoded(pins))
        delivery.write_inputs()
        return delivery, pins, pins_path

    def test_real_aggregate_path_binds_apk_notices_and_pinned_source_map(self):
        delivery, pins, pins_path = self.make_delivery_fixture()
        result = delivery.aggregate()
        fork = next(item for item in result["components"] if item["name"] == "rustls")
        self.assertEqual(origin.identity_url(self.descriptor, REVISION), fork["purl"])
        self.assertEqual("foxcore:" + self.refs[1], fork["bom-ref"])
        pins["files"] = [row for row in pins["files"] if row["path"] != origin.ORIGIN_PATH]
        pins_path.write_bytes(encoded(pins))
        with self.assertRaisesRegex(ValueError, "pinned source digests"): delivery.aggregate()

    def test_aggregate_cannot_erase_fork_tags_to_masquerade_as_stock(self):
        delivery, _, _ = self.make_delivery_fixture()
        view = copy.deepcopy(self.view)
        fork = view["components"][0]
        fork["purl"], fork["properties"] = "pkg:cargo/rustls@0.23.46", []
        view["metadata"]["properties"] = [r for r in view["metadata"]["properties"] if r["name"] != "foxhole:native:vendored-fork-components"]
        delivery.inventory["foxcore/foxcore-aarch64-linux-android.cdx.json"] = encoded(view)
        delivery.write_inputs()
        with self.assertRaises(ValueError): delivery.aggregate()
        for path in list(delivery.inventory):
            if path.startswith("foxcore/cargo-notices/source-forks/"): del delivery.inventory[path]
        delivery.write_inputs()
        with self.assertRaises(ValueError): delivery.aggregate()

    def test_aggregate_cannot_route_fork_through_legacy_workspace_expansion(self):
        delivery, _, _ = self.make_delivery_fixture()
        view = copy.deepcopy(self.view)
        view["metadata"]["properties"] = [r for r in view["metadata"]["properties"] if r["name"] not in
            ("foxhole:native:explicit-workspace-components", "foxhole:native:vendored-fork-components")]
        view["metadata"]["properties"].append({"name": "foxcore:workspace_members", "value": "0"})
        delivery.inventory["foxcore/foxcore-aarch64-linux-android.cdx.json"] = encoded(view)
        delivery.write_inputs()
        with self.assertRaisesRegex(ValueError, "explicit captured graph"): delivery.aggregate()

    def test_selected_fork_identity_cannot_claim_registry_source(self):
        self.metadata["packages"][1]["source"] = "registry+https://github.com/rust-lang/crates.io-index"
        self.refresh()
        with self.assertRaisesRegex(ValueError, "captured local rustls"): self.validate()

    def test_aggregate_cannot_change_fork_identity_license_or_count(self):
        packaged = origin.validate_packaged_origin(dict(self.descriptor["noticeFiles"]).__getitem__,
            {p: sha(raw) for p, (raw, _) in self.files.items()})
        for field in ("purl", "licenses", "count", "workspace"):
            with self.subTest(field=field):
                view = copy.deepcopy(self.view)
                fork = view["components"][0]
                if field == "purl": fork["purl"] = "pkg:cargo/rustls@0.23.46"
                elif field == "licenses": fork["licenses"] = [{"expression": "MIT"}]
                elif field == "workspace": fork["properties"].append({"name": "cargo:workspace-manifest", "value": "vendor/rustls/Cargo.toml"})
                else: next(r for r in view["metadata"]["properties"] if r["name"] == "foxhole:native:vendored-fork-components")["value"] = "2"
                with self.assertRaises(ValueError): aggregate.expand_core_workspace(view, REVISION, packaged)

    def test_archive_link_duplicate_and_escaping_paths_are_refused(self):
        for mutation in ("link", "duplicate", "escape"):
            with self.subTest(mutation=mutation):
                self.refresh()
                buffer = io.BytesIO(self.archive())
                with tarfile.open(fileobj=buffer, mode="a") as archive:
                    member = tarfile.TarInfo("vendor/rustls/Cargo.toml" if mutation == "duplicate" else "../escape" if mutation == "escape" else "symlink")
                    if mutation == "link": member.type, member.linkname = tarfile.SYMTYPE, "vendor/rustls"
                    archive.addfile(member)
                archive_hash = self.put("sourceArchive", buffer.getvalue())
                for name in ("producerReceipt", "sourceManifest", "captureReceipt"):
                    value = json.loads((self.root / self.inputs[name]["path"]).read_bytes())
                    value["archiveSha256" if name == "sourceManifest" else "sourceArchiveSha256"] = archive_hash
                    if name == "captureReceipt": value["producerReceiptSha256"] = self.inputs["producerReceipt"]["sha256"]
                    self.put(name, value)
                receipt = json.loads(self.receipt_path.read_bytes()); receipt["inputs"] = self.inputs
                self.receipt_path.write_bytes(encoded(receipt))
                with self.assertRaises(ValueError): self.validate()

if __name__ == "__main__": unittest.main()
