#!/usr/bin/env python3
"""Malformed ELF and page-layout admission cases; no native compilation."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'verify-native-pages.py'
spec = importlib.util.spec_from_file_location('page_validator', SCRIPT)
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


def fixture(abi='arm64-v8a', align=None, relro_end=None):
    cls, machine, page = pages.ABI_LAYOUTS[abi]
    ident = b'\x7fELF' + bytes([cls, 1, 1]) + bytes(9)
    hformat = '<HHIQQQIHHHHHH' if cls == 2 else '<HHIIIIIHHHHHH'
    pformat = '<IIQQQQQQ' if cls == 2 else '<IIIIIIII'
    hsize = 16 + struct.calcsize(hformat)
    psize = struct.calcsize(pformat)
    header = struct.pack(hformat, 3, machine, 1, 0, hsize, 0, 0, hsize, psize, 2, 0, 0, 0)
    data = bytearray(2 * page)
    data[:hsize] = ident + header
    entries = [(1, 4, 0, 0, 0, page, page, align or page),
               (0x6474E552, 4, 0, 0, 0, page, relro_end or page, 1)]
    for i, fields in enumerate(entries):
        if cls == 1:
            kind, flags, offset, vaddr, paddr, filesz, memsz, alignment = fields
            fields = (kind, offset, vaddr, paddr, filesz, memsz, flags, alignment)
        struct.pack_into(pformat, data, hsize + i * psize, *fields)
    return data


class PageAdmissionTests(unittest.TestCase):
    def verify(self, data, abi='arm64-v8a'):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'fixture.so'
            binary.write_bytes(data)
            return pages.verify(abi, binary)

    def mutate(self, offset, fmt, value, message):
        data = fixture()
        struct.pack_into(fmt, data, offset, value)
        with self.assertRaisesRegex(ValueError, message):
            self.verify(data)

    def test_valid_all_four_abis(self):
        for abi in pages.ABI_LAYOUTS:
            with self.subTest(abi=abi):
                self.assertIn('PASS', self.verify(fixture(abi), abi))

    def test_four_kib_relro_end_refused_on_64_bit(self):
        with self.assertRaisesRegex(ValueError, 'RELRO end'):
            self.verify(fixture(relro_end=4096))

    def test_small_load_alignment_refused(self):
        with self.assertRaisesRegex(ValueError, 'LOAD segment'):
            self.verify(fixture(align=4096))

    def test_non_power_of_two_load_alignment_refused(self):
        with self.assertRaisesRegex(ValueError, 'alignment'):
            self.verify(fixture(align=24576))

    def test_load_page_congruence_refused(self):
        self.mutate(64 + 16, '<Q', 1, 'LOAD segment')

    def test_missing_load_refused(self):
        self.mutate(64, '<I', 4, 'no LOAD')

    def test_missing_relro_refused(self):
        self.mutate(64 + 56, '<I', 4, 'no RELRO')

    def test_truncated_file_extent_refused(self):
        self.mutate(64 + 32, '<Q', 32769, 'file extent')

    def test_load_memory_smaller_than_file_refused(self):
        self.mutate(64 + 40, '<Q', 16383, 'memory extent')

    def test_machine_mismatch_refused(self):
        self.mutate(18, '<H', 62, 'machine')

    def test_invalid_elf_version_refused(self):
        self.mutate(20, '<I', 0, 'header')

    def test_invalid_ident_version_refused(self):
        self.mutate(6, '<B', 0, 'layout')

    def test_wrong_header_size_refused(self):
        self.mutate(52, '<H', 63, 'header')

    def test_truncated_program_table_refused(self):
        self.mutate(56, '<H', 65535, 'program header')


if __name__ == '__main__':
    unittest.main()
