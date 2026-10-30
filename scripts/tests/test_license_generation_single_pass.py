"""Tiny public Cargo fixtures; no native tools, production material or graph build."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

FIXTURE_SPEC = importlib.util.spec_from_file_location('public_cargo_fixture', Path(__file__).with_name('test_selected_license_sbom.py'))
FIXTURES = importlib.util.module_from_spec(FIXTURE_SPEC); FIXTURE_SPEC.loader.exec_module(FIXTURES)
GEN = FIXTURES.GENERATOR


class LicenseGenerationSinglePass(unittest.TestCase):
    def setUp(self):
        self.fixture = FIXTURES.SelectedLicenseSbomTest(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.four_delivery()
        self.root = self.fixture.root.resolve()
        self.args = SimpleNamespace(foxcore_sbom=self.fixture.sbom.resolve(), foxcore_sbom_receipt=self.fixture.receipt_path.resolve())
        profile = patch.object(GEN, 'core_delivery_profile', return_value='legacy4')
        profile.start()
        self.addCleanup(profile.stop)
        self.blobs = {'Cargo.toml': b'[workspace.package]\nversion="1.0.0"\n', 'Cargo.lock': self.fixture.lock,
                      'LICENSE': b'original core GPL text\n', 'THIRD_PARTY_NOTICES.md': b'original core notice text\n'}

    def core(self, generator, output):
        return generator.copy_core_material(output, self.args, self.root, self.fixture.revision)

    def test_core_bytes_equal_previous_public_pipeline_with_one_validation(self):
        old = self.root / 'old-output'; new = self.root / 'new-output'
        path = GEN.REPO_ROOT / 'scripts/selected-license-sbom.py'
        spec = importlib.util.spec_from_file_location('counted_core_loader', path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with patch.object(module, 'load_selected_delivery', wraps=module.load_selected_delivery) as validation:
            # Import mechanics only are replaced; the real complete validator runs.
            with patch.object(GEN.importlib.util, 'spec_from_file_location', return_value=spec), \
                    patch.object(GEN.importlib.util, 'module_from_spec', return_value=module), patch.object(spec.loader, 'exec_module'):
                with patch.object(GEN, 'git_blob', side_effect=lambda root, revision, name: self.blobs[name]):
                    self.core(GEN, new)
            self.assertEqual(1, validation.call_count)
        # The previous two public helpers remain strict and provide the output oracle.
        with patch.object(GEN, 'git_blob', side_effect=lambda root, revision, name: self.blobs[name]):
            primary = GEN.core_inventory(self.args, self.root, self.fixture.revision)
            GEN.write(old, 'foxcore/FoxHole-Core-GPL-3.0-or-later.txt', self.blobs['LICENSE'])
            GEN.write(old, 'foxcore/THIRD_PARTY_NOTICES.md', self.blobs['THIRD_PARTY_NOTICES.md'])
            GEN.write(old, 'foxcore/foxcore-aarch64-linux-android.cdx.json', primary)
            GEN.copy_selected_cargo_notices(old, self.args, primary, self.root, self.fixture.revision)
            GEN.write(old, 'foxcore/REVISION.txt', (self.fixture.revision + '\n').encode())
        files = lambda root: {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
        self.assertEqual(files(old), files(new))

    def test_original_tree_changed_after_validation_is_refused(self):
        original_write = GEN.write; mutated = []
        tree = self.root / self.fixture.inputs['cargoTree']['path']
        def write(output, name, body):
            original_write(output, name, body)
            if name.startswith('foxcore/') and not mutated:
                tree.write_bytes(b'changed tree after complete validation\n'); mutated.append(True)
        with patch.object(GEN, 'git_blob', side_effect=lambda root, revision, name: self.blobs[name]), patch.object(GEN, 'write', side_effect=write):
            with self.assertRaisesRegex(ValueError, 'after validation'):
                self.core(GEN, self.root / 'changed-tree-output')
        self.assertTrue(mutated)

    def test_original_notice_changed_after_validation_is_refused(self):
        original_write = GEN.write; mutated = []
        notice = self.root / 'LICENSE-dep'
        def write(output, name, body):
            original_write(output, name, body)
            if name.startswith('foxcore/') and not mutated:
                notice.write_bytes(b'changed original notice\n'); mutated.append(True)
        with patch.object(GEN, 'git_blob', side_effect=lambda root, revision, name: self.blobs[name]), patch.object(GEN, 'write', side_effect=write):
            with self.assertRaisesRegex(ValueError, 'after validation'):
                self.core(GEN, self.root / 'changed-notice-output')
        self.assertTrue(mutated)

    def test_each_generate_call_revalidates_changed_receipts(self):
        with patch.object(GEN, 'git_blob', side_effect=lambda root, revision, name: self.blobs[name]):
            self.core(GEN, self.root / 'first-output')
            self.fixture.receipt_path.write_text('{"schema": 2}\n')
            with self.assertRaises(ValueError): self.core(GEN, self.root / 'second-output')


if __name__ == '__main__':
    unittest.main()
