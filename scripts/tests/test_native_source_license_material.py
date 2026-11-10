"""Synthetic strict-loader negatives only; never claim native/consumer qualification."""
from pathlib import Path
import ast
import copy
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
spec = importlib.util.spec_from_file_location('strict_native_material', REPO / 'scripts/native-source-license-material.py')
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)
ACTUAL_GO = HERE / 'fixtures/native-source-material/go1.26.10'


class LoaderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='foxhole-native-legal-unit-')
        self.root = Path(self.temp.name).resolve()
        self.repo, self.payload = self.root / 'app', self.root / 'payload'
        self.repo.mkdir(); self.payload.mkdir()
        self.source = {}
        for name in M.SOURCE_KEYS:
            data = ('synthetic source ' + name + '\n').encode()
            file = self.repo / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
            self.source[name] = M.digest(data)
        natives = []
        for abi in M.ABIS:
            for name in ('lyrebird', 'conjure-client', 'libi2pd.so'):
                data = bytearray(64); data[:4] = b'\x7fELF'; data[4], machine = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}[abi]
                data[5] = 1; data[18:20] = machine.to_bytes(2, 'little')
                file = M.asset_path(self.repo, abi, name); file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
                natives.append({'abi': abi, 'name': name, 'bytes': len(data), 'sha256': M.digest(data)})
        self.modules = [{'declaredPath': 'example.org/mod' + str(i), 'declaredVersion': 'v1.0.0',
                         'path': 'example.org/mod' + str(i), 'version': 'v1.0.0', 'sum': 'h1:' + 'A' * 43 + '=',
                         'owners': ['arm64-v8a/lyrebird'], 'replacementPath': None} for i in range(75)]
        self.modules.append({'declaredPath': 'github.com/refraction-networking/conjure', 'declaredVersion': 'v0.9.1',
                             'path': 'github.com/refraction-networking/conjure', 'version': 'v0.9.1', 'sum': None,
                             'owners': ['arm64-v8a/conjure-client'], 'replacementPath': '../conjure-patched'})
        self.rows = []
        for module in self.modules:
            prefix = 'tor/go-modules/' + M.module_directory(module['path'], module['version']) + '/'
            self.add(prefix + 'LICENSE', b'Synthetic unit license\n', module, 'original')
            self.add(prefix + 'MODULE.txt', (module['path'] + '\n' + module['version'] + '\n').encode(), module, 'generated-descriptor')
        originals = ['tor/lyrebird-BSD-3-Clause.txt', 'tor/lyrebird-GPL-3.0-or-later.txt', 'tor/conjure-client-BSD-3-Clause.txt',
                     'i2pd/i2pd-BSD-3-Clause.txt', 'i2pd/VERSION.txt', 'i2pd/OpenSSL-3.5.9-Apache-2.0.txt',
                     'i2pd/Boost-1.84.0-BSL-1.0.txt', 'i2pd/Android-NDK-29-NOTICE.txt', 'i2pd/Android-NDK-29-NOTICE.toolchain.txt']
        for name in originals: self.add(name, (self.repo / 'third_party/i2pd.version').read_bytes() if name == 'i2pd/VERSION.txt' else b'Synthetic unit original\n')
        self.add('tor/Go-1.26.10-BSD-3-Clause.txt', (ACTUAL_GO / 'LICENSE').read_bytes())
        self.add('tor/Go-1.26.10-PATENTS.txt', (ACTUAL_GO / 'PATENTS').read_bytes())
        for source, name in [('config/native/conjure/stun-v3-patch.json', 'tor/CONJURE-PATCH.json'), ('scripts/prepare-tor-dependency.py', 'tor/CONJURE-PATCH.py')]:
            self.add(name, (self.repo / source).read_bytes())
        self.add('tor/GO-MODULES.json', M.encoded(self.modules))
        self.manifest = {'schema': 1, 'kind': 'guard-go8-cpp4-source-license-material', 'coverage': 'complete', 'gaps': [],
                         'abis': list(M.ABIS), 'producerReceipts': M.PRODUCERS, 'nativeLibraries': natives, 'modules': self.modules,
                         'consumerBindings': {key: 'b' * 64 for key in M.CONSUMERS}, 'files': self.rows,
                         'origins': {key: {'coverage': 'complete', 'authoritySHA256': 'a' * 64} for key in ('goStd', 'localConjure', 'lyrebirdComposite', 'cppAndNDK')}}
        self.manifest['origins']['goStd'].update(authoritySHA256='5f41fd9468bc246e78bd56952951dcb5f846a1b07a7b5399a00dd1bf82846704', sourceCommit='4e8c85f5319d999bfd7617fdc191631e5634e3c5', sourceArchiveSHA256='1e2d5e06a2f103d11f9ae23a39cf3f79d4c7ed6f1335b7715ac2e65894fe1405')
        self.manifest['origins']['localConjure'].update(originalTreeSHA256='c' * 64, effectiveTreeSHA256='d' * 64,
            originalLicenseSHA256=M.digest(b'Synthetic unit license\n'), patchManifestSHA256=self.source['config/native/conjure/stun-v3-patch.json'], patchScriptSHA256=self.source['scripts/prepare-tor-dependency.py'])
        self.manifest['origins']['lyrebirdComposite'].update(sourceCommit='0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1', packageGraphSHA256ByABI={abi: 'e' * 64 for abi in M.ABIS})
        self.manifest['origins']['cppAndNDK'].update(sourceCommit='635b013a612ff47278ef02acf8580a28e10e26c5', OpenSSLArchiveSHA256='603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a', BoostArchiveSHA256='cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454', NDKVersion='29.0.14206865')
        self.manifest_path = self.root / 'manifest.json'
        self.binding_path = self.repo / 'config/native-source-material-bindings.json'
        self.sync()

    def tearDown(self): self.temp.cleanup()

    def add(self, name, data, module=None, kind='original'):
        file = self.payload / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
        self.rows.append({'path': name, 'bytes': len(data), 'sha256': M.digest(data), 'module': module['path'] + '@' + module['version'] if module else None,
                          'sourceH1': module['sum'] if module else None, 'kind': kind})

    def sync(self):
        self.manifest_path.write_bytes(M.encoded(self.manifest))
        self.bindings = {'schema': 1, 'kind': 'qualified-go8-cpp4-consumer-material-bindings', 'coverage': 'complete',
                         'manifestSHA256': M.digest(self.manifest_path.read_bytes()), 'sourceInputs': self.source,
                         'moduleInventorySHA256': M.digest(M.encoded(self.modules)), 'nativeLibraries': self.manifest['nativeLibraries'],
                         'consumerBindings': self.manifest['consumerBindings']}
        self.binding_path.write_bytes(M.encoded(self.bindings))

    def load(self): return M.load(self.manifest_path, self.payload, self.repo, ['arm64-v8a'])

    def test_synthetic_complete_material_rechecks_without_consumer_pass_claim(self):
        value = self.load(); M.recheck(value)
        self.assertFalse(value['completeConsumerLegalQualification'])

    def test_each_null_future_consumer_binding_rejects(self):
        for role in M.CONSUMERS:
            before = self.manifest['consumerBindings'][role]; self.manifest['consumerBindings'][role] = None; self.sync()
            with self.assertRaisesRegex(ValueError, 'consumer bindings missing'): self.load()
            self.manifest['consumerBindings'][role] = before

    def test_changed_native_or_wrong_abi_rejects(self):
        file = M.asset_path(self.repo, 'arm64-v8a', 'lyrebird'); original = file.read_bytes(); file.write_bytes(original + b'tampered')
        with self.assertRaisesRegex(ValueError, 'digest differs'): self.load()
        body = bytearray(original); body[18:20] = (3).to_bytes(2, 'little'); file.write_bytes(body)
        row = next(row for row in self.manifest['nativeLibraries'] if row['abi'] == 'arm64-v8a' and row['name'] == 'lyrebird')
        row['sha256'] = M.digest(body); self.sync()
        with self.assertRaisesRegex(ValueError, 'ABI/size differs'): self.load()

    def test_missing_extra_and_casefold_members_reject(self):
        missing = self.payload / self.rows[0]['path']; body = missing.read_bytes(); missing.unlink()
        with self.assertRaises(FileNotFoundError): self.load()
        missing.write_bytes(body)
        file = self.payload / 'unexpected.txt'; file.write_text('extra')
        with self.assertRaisesRegex(ValueError, 'Extra native'): self.load()
        file.unlink(); self.add('tor/UNAPPROVED-OPERATIONS.txt', b'Synthetic metadata'); self.sync()
        with self.assertRaisesRegex(ValueError, 'Unapproved non-module'): self.load()
        self.rows.pop(); (self.payload / 'tor/UNAPPROVED-OPERATIONS.txt').unlink()
        row = copy.deepcopy(self.rows[0]); row['path'] = str(Path(row['path']).with_name('license')); self.rows.append(row); self.sync()
        with self.assertRaisesRegex(ValueError, 'path/case collision'): self.load()

    def test_module_h1_and_local_registry_relabelling_reject(self):
        self.rows[0]['sourceH1'] = 'h1:' + 'B' * 43 + '='; self.sync()
        with self.assertRaisesRegex(ValueError, 'H1/ownership'): self.load()
        self.rows[0]['sourceH1'] = self.modules[0]['sum']; self.modules[-1]['sum'] = 'h1:' + 'A' * 43 + '='; self.sync()
        with self.assertRaisesRegex(ValueError, 'local replacement'): self.load()

    def test_missing_local_license_or_patch_origin_rejects(self):
        self.manifest['origins']['localConjure']['originalLicenseSHA256'] = 'f' * 64; self.sync()
        with self.assertRaisesRegex(ValueError, 'original license not joined'): self.load()
        self.manifest['origins']['localConjure'] = None; self.sync()
        with self.assertRaisesRegex(ValueError, 'original origin'): self.load()

    def test_wrong_go_std_origin_or_body_rejects(self):
        self.manifest['origins']['goStd']['sourceCommit'] = '0' * 40; self.sync()
        with self.assertRaisesRegex(ValueError, 'standard-library notice origin'): self.load()
        self.manifest['origins']['goStd']['sourceCommit'] = '4e8c85f5319d999bfd7617fdc191631e5634e3c5'
        file = self.payload / 'tor/Go-1.26.10-BSD-3-Clause.txt'; file.write_bytes(b'Wrong substituted license\n')
        row = next(row for row in self.rows if row['path'] == 'tor/Go-1.26.10-BSD-3-Clause.txt'); row.update(bytes=file.stat().st_size, sha256=M.digest(file.read_bytes())); self.sync()
        with self.assertRaisesRegex(ValueError, 'standard-library notice origin'): self.load()

    def test_stale_cpp_and_missing_gpl_composite_reject(self):
        self.manifest['origins']['cppAndNDK']['OpenSSLArchiveSHA256'] = '0' * 64; self.sync()
        with self.assertRaisesRegex(ValueError, 'CPP/NDK'): self.load()
        self.manifest['origins']['cppAndNDK']['OpenSSLArchiveSHA256'] = '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'
        self.manifest['origins']['lyrebirdComposite']['packageGraphSHA256ByABI'].pop('x86'); self.sync()
        with self.assertRaisesRegex(ValueError, 'GPL composite'): self.load()

    def test_payload_and_metadata_private_paths_reject(self):
        self.manifest['origins']['goStd']['private'] = '/srv/foxhole/private'; self.sync()
        with self.assertRaisesRegex(ValueError, 'Operational/private'): self.load()

    def test_symlinks_and_after_generation_mutation_reject(self):
        value = self.load(); file = self.payload / self.rows[0]['path']; original = file.read_bytes(); file.unlink(); target = self.root / 'alias'; target.write_bytes(original); file.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'F9 changed'): M.recheck(value)

    def test_metadata_only_mutation_and_extra_after_load_reject(self):
        value = self.load(); file = self.payload / self.rows[0]['path']; info = file.stat()
        os.utime(file, ns=(info.st_atime_ns, info.st_mtime_ns + 1000000))
        with self.assertRaisesRegex(ValueError, 'F9 changed'): M.recheck(value)
        (self.payload / 'new-after-load.txt').write_text('extra')
        with self.assertRaisesRegex(ValueError, 'inventory changed'): M.recheck(value)

    def test_native_hardlink_is_rejected_even_with_exact_hash_and_abi(self):
        file = M.asset_path(self.repo, 'arm64-v8a', 'lyrebird')
        os.link(file, self.root / 'same-native-bytes')
        self.assertEqual(2, file.stat().st_nlink)
        with self.assertRaisesRegex(ValueError, 'protected positive'): self.load()

    def test_nonfinite_json_numbers_are_rejected(self):
        for value in ('NaN', 'Infinity', '-Infinity', '1e999', '-1e999'):
            with self.assertRaisesRegex(ValueError, 'Non-finite'):
                M.document(('{"value":' + value + '}').encode())

    def test_controls_and_casefold_forbidden_components_are_rejected(self):
        for name in ('tor/a\nLICENSE', 'tor/a\tLICENSE', 'tor/a\x00LICENSE', 'tor/a\u0085LICENSE',
                     'tor/.GiT/LICENSE', 'tor/agents.Md', 'tor/AGENTS.MD/LICENSE'):
            with self.assertRaises(ValueError): M.relative(name)

    def test_generator_default_block_and_static_hashes_are_unchanged(self):
        new = ast.parse((REPO / 'scripts/generate-license-assets.py').read_text())
        static = next(n for n in new.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'STATIC_HASHES' for t in n.targets))
        self.assertEqual('b2ad8987b1dbdfae5d643e434a29c6b50305bb5805337383ec3bb83fb5d2dab9', hashlib.sha256(ast.dump(static).encode()).hexdigest())
        legacy = next(n for n in new.body if isinstance(n, ast.FunctionDef) and n.name == 'copy_legacy_cpp_and_go_material')
        encoded = json.dumps([ast.dump(n) for n in legacy.body], separators=(',', ':')).encode()
        self.assertEqual('9df25adec1f49e9f31db5af234de7919ba6a00536b4347f78064d72a3240fcfb', hashlib.sha256(encoded).hexdigest())



if __name__ == '__main__': unittest.main()
