#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

ABI_LAYOUTS = {'arm64-v8a': (2, 183, 16384), 'armeabi-v7a': (1, 40, 4096),
               'x86': (1, 3, 4096), 'x86_64': (2, 62, 16384)}


def verify(abi, binary):
    if abi not in ABI_LAYOUTS:
        raise ValueError('Unsupported Android ABI: ' + abi)
    expected_class, expected_machine, page = ABI_LAYOUTS[abi]
    data = binary.read_bytes()
    if (len(data) < 16 or data[:4] != b'\x7fELF' or data[4] != expected_class or
            data[5] != 1 or data[6] != 1):
        raise ValueError('ELF layout does not match ' + abi)
    header_format = '<HHIQQQIHHHHHH' if expected_class == 2 else '<HHIIIIIHHHHHH'
    header = struct.unpack_from(header_format, data, 16)
    if (header[0] not in (2, 3) or header[2] != 1 or
            header[7] != 16 + struct.calcsize(header_format)):
        raise ValueError('ELF header is malformed')
    if header[1] != expected_machine:
        raise ValueError('ELF machine does not match ' + abi)
    program_format = '<IIQQQQQQ' if expected_class == 2 else '<IIIIIIII'
    program_size = struct.calcsize(program_format)
    program_offset, stride, count = header[4], header[8], header[9]
    if not count or stride < program_size or program_offset + stride * count > len(data):
        raise ValueError('ELF program header table is malformed')
    loads = 0
    relro = 0
    for index in range(count):
        entry = struct.unpack_from(program_format, data, program_offset + stride * index)
        offset, vaddr, filesz, memsz = ((entry[2], entry[3], entry[5], entry[6])
                                      if expected_class == 2 else
                                      (entry[1], entry[2], entry[4], entry[5]))
        if entry[0] == 1:
            loads += 1
            if offset + filesz > len(data):
                raise ValueError('ELF LOAD file extent is malformed')
            if memsz < filesz:
                raise ValueError('ELF LOAD memory extent is malformed')
            if entry[7] and entry[7] & (entry[7] - 1):
                raise ValueError('ELF LOAD alignment is not a power of two')
            if entry[7] < page or (vaddr - offset) % page:
                raise ValueError('ELF LOAD segment does not support ' + str(page) + '-byte pages')
        if entry[0] == 0x6474E552:
            relro += 1
            if (vaddr + memsz) % page:
                raise ValueError('ELF RELRO end does not support ' + str(page) + '-byte pages')
    if not loads:
        raise ValueError('ELF contains no LOAD segment')
    if not relro:
        raise ValueError('ELF contains no RELRO segment')
    return f'{abi}: native page gate PASS (page_bytes={page}, loads={loads}, relro={relro})'


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3:
            raise ValueError('Usage: verify-native-pages.py ABI ELF')
        print(verify(sys.argv[1], Path(sys.argv[2])))
    except (ValueError, OSError, struct.error) as error:
        print('native page gate: ' + str(error), file=sys.stderr)
        sys.exit(1)
