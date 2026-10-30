import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "delivery_sbom", Path(__file__).resolve().parents[1] / "aggregate-delivery-sbom.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SENTINEL_BOM = "sentinel/sbom/sentinel-yara-all-targets.cdx.json"
SENTINEL_REVISION = "sentinel/REVISION.txt"
SENTINEL_REF = "pkg:cargo/foxhole-sentinel-yara@0.1.0"
DEPENDENCY_REF = "pkg:cargo/regex@1.0.0"
SENTINEL_ELF = "lib/arm64-v8a/libfoxhole_sentinel_yara.so"


def cargo_dependency():
    return {"type": "library", "name": "regex", "version": "1.0.0",
            "bom-ref": DEPENDENCY_REF, "purl": DEPENDENCY_REF}


def encoded_string(data):
    length = len(data)
    prefix = bytearray()
    while length >= 128:
        prefix.append((length & 127) | 128)
        length >>= 7
    return bytes(prefix) + bytes([length]) + data


def go_binary():
    module = b"dep\texample.org/dependency\tv1.0.0\n"
    framed = b"0" * 16 + module + b"0" * 16
    return (b"\xff Go buildinf:" + b"\x08\x02" + b"\x00" * 16
            + encoded_string(b"go1.26.8") + encoded_string(framed))


class DeliverySbomTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.notices = self.root / "notices"
        self.apk = self.root / "candidate.apk"
        self.revision = "a" * 40
        for directory in ("scripts", "config", "third_party"):
            (self.root / directory).mkdir()
        (self.root / "config/foxcore-revision.txt").write_text(self.revision + "\n")
        (self.root / "scripts/native-deps.sh").write_text(
            'openssl="${I2PD_OPENSSL_VERSION:-3.5.8}"\n'
            'boost="${I2PD_BOOST_VERSION:-1.84.0}"\n'
            'go="${TOR_TRANSPORT_GO:-go1.26.8}"\n'
            'lyrebird_commit="' + "b" * 40 + '"\n'
            'conjure_commit="' + "c" * 40 + '"\n'
        )
        i2pd_version = b"ref=2.50.0\n"
        (self.root / "third_party/i2pd.version").write_bytes(i2pd_version)
        self.maven = {
            "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"bom-ref": "app", "name": "app", "version": "1"}},
            "components": [{"type": "library", "name": "dependency", "version": "1",
                            "purl": "pkg:maven/example/dependency@1", "bom-ref": "maven-dependency"}],
            "dependencies": [{"ref": "app", "dependsOn": ["maven-dependency"]}],
        }
        core = {
            "bomFormat": "CycloneDX",
            "metadata": {"component": {"bom-ref": "core", "name": "foxcore", "version": "1.0.0"},
                         "properties": [{"name": "foxcore:workspace_members", "value": "0"}]},
            "components": [cargo_dependency()],
            "dependencies": [{"ref": "core", "dependsOn": [DEPENDENCY_REF]},
                             {"ref": DEPENDENCY_REF, "dependsOn": []}],
        }
        self.sentinel = {
            "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"bom-ref": SENTINEL_REF, "name": "foxhole-sentinel-yara",
                                       "version": "0.1.0", "type": "library"}},
            "components": [cargo_dependency()],
            "dependencies": [{"ref": SENTINEL_REF, "dependsOn": [DEPENDENCY_REF]},
                             {"ref": DEPENDENCY_REF, "dependsOn": []}],
        }
        self.inventory = {
            "MANIFEST.json": json.dumps({"foxcoreRevision": self.revision}).encode(),
            "foxcore/foxcore-aarch64-linux-android.cdx.json": json.dumps(core).encode(),
            SENTINEL_BOM: json.dumps(self.sentinel).encode(),
            SENTINEL_REVISION: (self.revision + "\n").encode(),
            "i2pd/VERSION.txt": i2pd_version,
            "tor/GO-MODULES.json": json.dumps([{
                "declaredPath": "example.org/dependency", "declaredVersion": "v1.0.0",
                "path": "example.org/dependency", "version": "v1.0.0",
                "binaries": ["lyrebird", "conjure-client"],
            }]).encode(),
        }
        self.libraries = {
            "lib/arm64-v8a/libfoxhole_native.so": b"owned core ELF",
            SENTINEL_ELF: b"owned sentinel ELF",
            "lib/arm64-v8a/libi2pd.so": b"OpenSSL 3.5.8 fixture",
            "lib/arm64-v8a/liblyrebird.so": go_binary(),
            "lib/arm64-v8a/libconjure_client.so": go_binary(),
        }

    def write_inputs(self):
        for name, data in self.inventory.items():
            destination = self.notices / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.inventory.items():
                archive.writestr("assets/licenses/" + name, data)
            for name, data in self.libraries.items():
                archive.writestr(name, data)

    def aggregate(self):
        return MODULE.aggregate(self.maven, self.notices, self.apk, self.root)

    def test_actual_sentinel_elf_hash_links_to_its_complete_cargo_graph(self):
        self.write_inputs()
        result = self.aggregate()
        entries = {entry["bom-ref"]: entry for entry in result["components"]}
        graph = {row["ref"]: row["dependsOn"] for row in result["dependencies"]}
        elf = next(entry for entry in entries.values() if entry["name"] == SENTINEL_ELF)
        self.assertEqual([{"alg": "SHA-256", "content": hashlib.sha256(b"owned sentinel ELF").hexdigest()}],
                         elf["hashes"])
        self.assertIn(elf["bom-ref"], graph["app"])
        self.assertEqual(["sentinel:root"], graph[elf["bom-ref"]])
        self.assertEqual(SENTINEL_REF, entries["sentinel:root"]["purl"])
        self.assertEqual(["sentinel:" + DEPENDENCY_REF], graph["sentinel:root"])
        self.assertEqual([], graph["sentinel:" + DEPENDENCY_REF])
        self.assertIn("foxcore:" + DEPENDENCY_REF, entries)
        properties = {row["name"]: row["value"] for row in result["metadata"]["properties"]}
        self.assertEqual(self.revision, properties["foxhole:sentinelRevision"])
        self.assertEqual(hashlib.sha256(self.inventory[SENTINEL_BOM]).hexdigest(),
                         properties["foxhole:sentinelBomSha256"])
        self.assertEqual("app", MODULE.validate_bom(result, "test aggregate"))
        self.assertNotIn("purl", self.sentinel["metadata"]["component"])

    def test_missing_sentinel_library_cannot_produce_a_delivery_bom(self):
        del self.libraries[SENTINEL_ELF]
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "missing a required native runtime library"):
            self.aggregate()

    def test_missing_embedded_sentinel_inventory_or_revision_is_rejected(self):
        for path in (SENTINEL_BOM, SENTINEL_REVISION):
            with self.subTest(path=path):
                data = self.inventory.pop(path)
                self.write_inputs()
                external = self.notices / path
                external.parent.mkdir(parents=True, exist_ok=True)
                external.write_bytes(data)
                with self.assertRaisesRegex(ValueError, "lacks embedded delivery inventory: sentinel/"):
                    self.aggregate()
                self.inventory[path] = data

    def test_tampered_external_sentinel_inventory_or_revision_is_rejected(self):
        for path in (SENTINEL_BOM, SENTINEL_REVISION):
            with self.subTest(path=path):
                self.write_inputs()
                (self.notices / path).write_bytes(b"changed external inventory")
                with self.assertRaisesRegex(ValueError, "differs from the candidate APK: sentinel/"):
                    self.aggregate()

    def test_missing_external_sentinel_inventory_or_revision_is_rejected(self):
        for path in (SENTINEL_BOM, SENTINEL_REVISION):
            with self.subTest(path=path):
                self.write_inputs()
                (self.notices / path).unlink()
                with self.assertRaises(FileNotFoundError):
                    self.aggregate()

    def test_invalid_json_in_matching_sentinel_inventories_is_rejected(self):
        self.inventory[SENTINEL_BOM] = b'{"components":'
        self.write_inputs()
        with self.assertRaises(json.JSONDecodeError):
            self.aggregate()

    def test_matching_embedded_revision_must_still_match_the_core_pin(self):
        for revision in ("b" * 40, "not-a-commit", ""):
            with self.subTest(revision=revision):
                self.inventory[SENTINEL_REVISION] = (revision + "\n").encode()
                self.write_inputs()
                with self.assertRaisesRegex(ValueError, "Sentinel.*revision.*Core pin"):
                    self.aggregate()

    def test_malformed_or_incomplete_sentinel_graph_is_rejected(self):
        cases = []
        for path, value in (("components", []), ("dependencies", []), ("dependencies", None)):
            document = copy.deepcopy(self.sentinel)
            document[path] = value
            cases.append(document)
        for root in (None, {}, {"bom-ref": SENTINEL_REF, "name": "different", "version": "0.1.0"}):
            document = copy.deepcopy(self.sentinel)
            document["metadata"]["component"] = root
            cases.append(document)
        for key, value in (("purl", "pkg:maven/wrong/root@0.1.0"), ("bom-ref", "wrong"), ("version", "")):
            document = copy.deepcopy(self.sentinel)
            document["metadata"]["component"][key] = value
            cases.append(document)
        for edge in ({"ref": SENTINEL_REF, "dependsOn": ["missing"]},
                     {"ref": SENTINEL_REF, "dependsOn": [{}]},
                     {"ref": SENTINEL_REF, "dependsOn": "wrong"},
                     {"ref": "missing", "dependsOn": []}):
            document = copy.deepcopy(self.sentinel)
            document["dependencies"][0] = edge
            cases.append(document)
        duplicate = copy.deepcopy(self.sentinel)
        duplicate["dependencies"].append(duplicate["dependencies"][0])
        cases.append(duplicate)
        duplicate_component = copy.deepcopy(self.sentinel)
        duplicate_component["components"].append(cargo_dependency())
        cases.append(duplicate_component)
        missing_leaf = copy.deepcopy(self.sentinel)
        missing_leaf["dependencies"].pop()
        cases.append(missing_leaf)
        disconnected = copy.deepcopy(self.sentinel)
        disconnected["dependencies"][0]["dependsOn"] = []
        cases.append(disconnected)
        for index, document in enumerate(cases):
            with self.subTest(case=index):
                self.inventory[SENTINEL_BOM] = json.dumps(document).encode()
                self.write_inputs()
                with self.assertRaisesRegex(ValueError, "Sentinel BOM"):
                    self.aggregate()


if __name__ == "__main__":
    unittest.main()
