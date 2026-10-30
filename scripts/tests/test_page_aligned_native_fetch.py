#!/usr/bin/env python3
import hashlib
import gzip
import io
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('fetcher', ROOT / 'scripts/prepare-page-aligned-native-inputs.py')
FETCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCHER)


class NativeFetchTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='native-fetch-contract-')
        self.root = Path(self.owned.name)
        self.path = self.root / 'publisher-source'
        self.data = b'original publisher bytes'
        self.identity = {'url': 'https://dl.google.com/exact-publisher-artifact',
                         'sha256': hashlib.sha256(self.data).hexdigest()}

    def tearDown(self):
        self.owned.cleanup()

    def test_verified_cache_is_used_offline(self):
        self.path.write_bytes(self.data)
        FETCHER.fetch(self.identity, self.path, True)

    def test_same_size_cache_tamper_is_rejected(self):
        self.path.write_bytes(b'X' + self.data[1:])
        with self.assertRaisesRegex(ValueError, 'differs'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_missing_offline_cache_is_an_error(self):
        with self.assertRaisesRegex(ValueError, 'offline'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_cached_symlink_is_rejected(self):
        original = self.root / 'outside'
        original.write_bytes(self.data)
        self.path.symlink_to(original)
        with self.assertRaisesRegex(ValueError, 'differs'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_foreign_publisher_is_refused_before_network(self):
        identity = {**self.identity, 'url': 'https://foreign.invalid/source'}
        with self.assertRaisesRegex(ValueError, 'publisher URL'):
            FETCHER.fetch(identity, self.path, False)

    def test_cache_parent_symlink_is_rejected(self):
        outside = self.root / 'outside-cache'
        outside.mkdir()
        (outside / 'publisher-source').write_bytes(self.data)
        parent = self.root / 'alias'
        parent.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'parent'):
            FETCHER.fetch(self.identity, parent / 'publisher-source', True)

    def test_credential_bearing_publisher_is_refused(self):
        identity = {**self.identity, 'url': 'https://synthetic:synthetic@dl.google.com/source'}
        with self.assertRaisesRegex(ValueError, 'publisher URL'):
            FETCHER.fetch(identity, self.path, False)


class GraphicsCanonicalSourceTest(unittest.TestCase):
    def setUp(self):
        self.names = sorted(json.loads((ROOT / 'config/native/page-aligned-native.json').read_text())['graphics']['sourceFiles'])
        self.files = {name: ('publisher source: ' + name).encode() for name in self.names}
        self.pins = {name: {'sha256': hashlib.sha256(body).hexdigest(),
                            'gitBlobSha1': hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest()}
                     for name, body in self.files.items()}

    def transport(self, entries=None, timestamp=1, uid=0, reverse=False, tail=b''):
        if entries is None:
            entries = [('math', None, tarfile.DIRTYPE, 0o40755)] + [
                (name, body, tarfile.REGTYPE, 0o100644) for name, body in self.files.items()]
        if reverse:
            entries = list(reversed(entries))
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w', format=tarfile.PAX_FORMAT) as archive:
            for name, body, kind, mode in entries:
                info = tarfile.TarInfo(name)
                info.type, info.mode, info.uid, info.gid, info.mtime = kind, mode, uid, uid, timestamp
                info.uname = info.gname = 'publisher metadata'
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    info.linkname = 'Conic.cpp'
                info.size = len(body) if body is not None else 0
                archive.addfile(info, io.BytesIO(body) if body is not None else None)
        return gzip.compress(raw.getvalue() + tail, mtime=int(timestamp))

    def canonical(self, **kwargs):
        return FETCHER.canonical_graphics_source(self.transport(**kwargs), self.pins)

    def test_request_metadata_does_not_change_canonical_source(self):
        self.assertEqual(self.canonical(timestamp=1.25), self.canonical(timestamp=987654.75, uid=1234, reverse=True))

    def test_canonical_format_contains_only_the_twelve_fixed_regular_files(self):
        with tarfile.open(fileobj=io.BytesIO(self.canonical()), mode='r:') as archive:
            members = archive.getmembers()
            self.assertEqual(self.names, [item.name for item in members])
            for item in members:
                self.assertEqual((tarfile.REGTYPE, 0o644, 0, 0, '', '', 0, {}, ''),
                                 (item.type, item.mode, item.uid, item.gid, item.uname, item.gname,
                                  item.mtime, item.pax_headers, item.linkname))
                self.assertEqual(self.files[item.name], archive.extractfile(item).read())

    def test_same_size_source_tamper_is_rejected(self):
        entries = [(name, b'X' + body[1:] if name == 'Conic.cpp' else body, tarfile.REGTYPE, 0o644)
                   for name, body in self.files.items()]
        with self.assertRaisesRegex(ValueError, 'content or Git blob'):
            self.canonical(entries=entries)

    def test_git_blob_identity_is_independently_required(self):
        self.pins['Conic.cpp']['gitBlobSha1'] = '0' * 40
        with self.assertRaisesRegex(ValueError, 'content or Git blob'):
            self.canonical()

    def test_missing_file_is_rejected(self):
        entries = [(name, body, tarfile.REGTYPE, 0o644) for name, body in self.files.items() if name != 'Conic.cpp']
        with self.assertRaisesRegex(ValueError, 'file set'):
            self.canonical(entries=entries)

    def test_extra_file_is_rejected(self):
        entries = [(name, body, tarfile.REGTYPE, 0o644) for name, body in self.files.items()]
        with self.assertRaisesRegex(ValueError, 'Unexpected graphics source file'):
            self.canonical(entries=entries + [('extra.cpp', b'not pinned', tarfile.REGTYPE, 0o644)])

    def test_duplicate_file_is_rejected(self):
        entries = [(name, body, tarfile.REGTYPE, 0o644) for name, body in self.files.items()]
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.canonical(entries=entries + [entries[0]])

    def test_unsafe_member_paths_are_rejected(self):
        for name in ['../Conic.cpp', '/Conic.cpp', './Conic.cpp', 'math/../Conic.cpp', 'math//compiler.h', 'math\\compiler.h']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.canonical(entries=[(name, b'untrusted', tarfile.REGTYPE, 0o644)])

    def test_links_are_rejected(self):
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE]:
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Unexpected graphics source file'):
                self.canonical(entries=[('Conic.cpp', None, kind, 0o644)])

    def test_source_modes_are_not_metadata_waivers(self):
        with self.assertRaisesRegex(ValueError, 'mode or size'):
            self.canonical(entries=[('Conic.cpp', self.files['Conic.cpp'], tarfile.REGTYPE, 0o755)])

    def test_only_the_known_optional_directory_is_allowed(self):
        with self.assertRaisesRegex(ValueError, 'source directory'):
            self.canonical(entries=[('foreign', None, tarfile.DIRTYPE, 0o755)])
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.canonical(entries=[('math', None, tarfile.DIRTYPE, 0o755)] * 2)

    def test_member_size_budget_is_checked_before_content(self):
        with self.assertRaisesRegex(ValueError, 'mode or size'):
            self.canonical(entries=[('Conic.cpp', b'X' * (256 * 1024 + 1), tarfile.REGTYPE, 0o644)])

    def test_compressed_bomb_is_bounded_before_tar_parsing(self):
        with self.assertRaisesRegex(ValueError, 'tar budget'):
            FETCHER.canonical_graphics_source(gzip.compress(b'\0' * (1024 * 1024 + 1)), self.pins)

    def test_nonzero_trailing_archive_data_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'trailing'):
            self.canonical(tail=b'unexpected trailing data')

    def test_canonical_digest_mismatch_never_populates_cache(self):
        graphics = {'commit': '8a05a22af450d589ef911d772a001a49dcb05b71', 'sourceFiles': self.pins,
                    'sourceArchive': {'url': 'https://android.googlesource.com/platform/frameworks/support/+archive/8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path/src/main/cpp.tar.gz',
                                      'format': 'canonical-ustar-v1', 'sha256': '0' * 64,
                                      'historicalPublisherArchiveSha256': 'f' * 64}}
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'canonical.tar'
            with mock.patch.object(FETCHER, 'download', return_value=self.transport()), self.assertRaisesRegex(ValueError, 'archive digest'):
                FETCHER.fetch_graphics_source(graphics, target, False)
            self.assertFalse(target.exists())

    def test_generic_transport_digest_remains_strict(self):
        first, second = self.transport(timestamp=1), self.transport(timestamp=2)
        identity = {'url': 'https://android.googlesource.com/original', 'sha256': hashlib.sha256(first).hexdigest()}
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'original.tar.gz'
            with mock.patch.object(FETCHER, 'download', return_value=second), self.assertRaisesRegex(ValueError, 'Publisher source digest'):
                FETCHER.fetch(identity, target, False)
            self.assertFalse(target.exists())


if __name__ == '__main__':
    unittest.main()
