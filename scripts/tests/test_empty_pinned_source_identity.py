"""Generator callback regression; synthetic source identity, no Git/native execution."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPTS = HERE if (HERE / 'generate-license-assets.py').is_file() else HERE.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


G = load('empty_source_generator', SCRIPTS / 'generate-license-assets.py')
M = load('empty_source_retained_role', SCRIPTS / 'retained-sentinel-role.py')


class ProjectionValidated(Exception):
    pass


class EmptySourceIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.empty = 'native/sentinel-yara/vendor/yara-x/src/compiler/tests/testdata/warnings/10.out'
        self.blobs = {name: ('synthetic ' + name).encode() for name in M.FIXED | {'native/sentinel-yara/Cargo.lock'}}
        self.blobs[self.empty] = b''
        self.files = {name: hashlib.sha256(body).hexdigest() for name, body in self.blobs.items()}
        self.args = SimpleNamespace(sentinel_sbom='synthetic-selected', sentinel_sbom_receipt='synthetic-receipt',
            sentinel_retained_role='synthetic-role', sentinel_current_source_manifest='synthetic-source',
            sentinel_current_producer='synthetic-producer', sentinel_current_stage='synthetic-stage')

    def git(self, argv, **kwargs):
        self.assertIs(kwargs.get('check'), True)
        self.assertIs(kwargs.get('capture_output'), True)
        if argv[3] == 'ls-tree':
            return SimpleNamespace(stdout='\n'.join(name for name in self.files if name.startswith('native/sentinel-yara/')))
        self.assertEqual(argv[3], 'show')
        name = argv[-1].split(':', 1)[1]
        return SimpleNamespace(stdout=self.blobs[name])

    def invoke_generator_projection(self, run):
        def generation(root, args, revision, loader, source_blob, source_names):
            M.validate_projection({'unchangedInputFiles': self.files}, self.files, self.files, source_blob, source_names)
            # Stop after the actual identity callback, before unrelated synthetic legal material.
            raise ProjectionValidated()

        modules = {'retained_sentinel_delivery': SimpleNamespace(generation=generation),
                   'selected_sentinel_inventory': SimpleNamespace()}
        def spec(name, path):
            return SimpleNamespace(name=name, loader=SimpleNamespace(exec_module=lambda module: None))
        with patch.object(G, 'REPO_ROOT', self.root), patch.object(G.subprocess, 'run', side_effect=run), \
             patch.object(G.importlib.util, 'spec_from_file_location', side_effect=spec), \
             patch.object(G.importlib.util, 'module_from_spec', side_effect=lambda spec: modules[spec.name]):
            G.copy_sentinel_material(self.root, self.args, self.root, M.CORE)

    def test_pinned_empty_source_fixture_reaches_full_projection_validation(self):
        with self.assertRaises(ProjectionValidated):
            self.invoke_generator_projection(self.git)

    def test_default_legal_blob_reader_still_rejects_empty_bytes(self):
        with patch.object(G.subprocess, 'run', side_effect=self.git):
            with self.assertRaisesRegex(SystemExit, 'empty pinned FoxCore file'):
                G.git_blob(self.root, M.CORE, self.empty)

    def test_failed_git_read_is_not_admitted_as_empty_source(self):
        def failed(argv, **kwargs):
            if argv[3] == 'ls-tree': return self.git(argv, **kwargs)
            self.assertEqual(argv[3], 'show')
            self.assertIs(kwargs.get('check'), True)
            raise subprocess.CalledProcessError(1, argv, stderr=b'synthetic missing blob')
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke_generator_projection(failed)

    def test_empty_bytes_still_refused_when_the_pinned_source_digest_is_nonempty(self):
        files = dict(self.files)
        files[self.empty] = hashlib.sha256(b'expected nonempty source').hexdigest()
        with self.assertRaisesRegex(ValueError, 'immutable current Core Git blobs'):
            M.validate_projection({'unchangedInputFiles': files}, files, files, self.blobs.__getitem__,
                lambda: [name for name in files if name.startswith('native/sentinel-yara/')])


if __name__ == '__main__':
    unittest.main()
