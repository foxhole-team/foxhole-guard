import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "explicit_delivery_sbom", Path(__file__).resolve().parents[1] / "aggregate-delivery-sbom.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExplicitWorkspaceSbomTest(unittest.TestCase):
    def setUp(self):
        self.revision = "a" * 40
        root = {"bom-ref": "android", "name": "foxcore-android", "version": "1.0.0",
                "purl": "pkg:generic/foxhole/foxcore-android@1.0.0?core_commit=" + self.revision,
                "properties": [{"name": "cargo:source-revision", "value": self.revision},
                               {"name": "cargo:workspace-manifest", "value": "crates/foxcore-android/Cargo.toml"}]}
        self.bom = {"bomFormat": "CycloneDX", "metadata": {"component": root, "properties": [
            {"name": "foxhole:native:explicit-workspace-components", "value": "1"},
            {"name": "foxhole:native:core-revision", "value": self.revision}]},
            "components": [{"name": "dep", "version": "2.0.0", "bom-ref": "dep", "purl": "pkg:cargo/dep@2.0.0"}],
            "dependencies": [{"ref": "android", "dependsOn": ["dep"]}, {"ref": "dep", "dependsOn": []}]}

    def test_complete_explicit_source_graph_retains_its_actual_count(self):
        result = MODULE.expand_core_workspace(self.bom, self.revision)
        self.assertEqual(self.bom, result)
        self.assertEqual("1", result["metadata"]["properties"][0]["value"])
        self.assertNotIn("foxcore:workspace_members", {item["name"] for item in result["metadata"]["properties"]})

    def test_wrong_count_or_redefined_legacy_count_is_refused(self):
        for count in ("0", "2", "01"):
            altered = copy.deepcopy(self.bom)
            altered["metadata"]["properties"][0]["value"] = count
            with self.subTest(count=count), self.assertRaises(ValueError):
                MODULE.expand_core_workspace(altered, self.revision)
        altered = copy.deepcopy(self.bom)
        altered["metadata"]["properties"].append({"name": "foxcore:workspace_members", "value": "0"})
        with self.assertRaises(ValueError):
            MODULE.expand_core_workspace(altered, self.revision)

    def test_unresolved_or_omitted_or_disconnected_edges_are_refused(self):
        for mutation in ("unknown", "omitted", "disconnected", "duplicate"):
            altered = copy.deepcopy(self.bom)
            if mutation == "unknown":
                altered["dependencies"][0]["dependsOn"].append("missing")
            elif mutation == "omitted":
                altered["dependencies"].pop()
            elif mutation == "duplicate":
                altered["dependencies"].append(altered["dependencies"][0])
            else:
                altered["dependencies"][0]["dependsOn"] = []
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                MODULE.expand_core_workspace(altered, self.revision)

    def test_unpublished_workspace_node_cannot_claim_crates_io_or_other_revision(self):
        for purl in ("pkg:cargo/foxcore-android@1.0.0", "pkg:generic/foxhole/other@1.0.0?core_commit=" + self.revision):
            altered = copy.deepcopy(self.bom)
            altered["metadata"]["component"]["purl"] = purl
            with self.subTest(purl=purl), self.assertRaises(ValueError):
                MODULE.expand_core_workspace(altered, self.revision)
        with self.assertRaises(ValueError):
            MODULE.expand_core_workspace(self.bom, "b" * 40)

    def test_legacy_omitted_workspace_expansion_stays_compatible(self):
        legacy = {"metadata": {"component": {"bom-ref": "root", "version": "1.0.0"},
                  "properties": [{"name": "foxcore:workspace_members", "value": "1"}]},
                  "components": [], "dependencies": [{"ref": "root", "dependsOn": ["pkg:cargo/foxcore-net@1.0.0"]},
                  {"ref": "pkg:cargo/foxcore-net@1.0.0", "dependsOn": []}]}
        result = MODULE.expand_core_workspace(legacy)
        self.assertEqual("pkg:cargo/foxcore-net@1.0.0", result["components"][0]["purl"])


if __name__ == "__main__":
    unittest.main()
