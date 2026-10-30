#!/usr/bin/env python3
"""Run the actual SAM frame methods with deterministic I/O stubs in Docker."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def method(source, name):
    marker = re.search(r'(?m)^\s*(?:void|size_t|i2p::util::Mapping) SAMSocket::' + name + r'\s*\(', source)
    if marker is None:
        raise ValueError('Missing SAM method: ' + name)
    start = marker.start()
    opening = source.index('{', marker.end())
    depth = 1
    position = opening + 1
    while depth:
        if source[position] == '{':
            depth += 1
        elif source[position] == '}':
            depth -= 1
        position += 1
    return source[start:position]


def constants(header):
    names = ['SAM_SOCKET_BUFFER_SIZE', 'SAM_STREAM_MAX_SEND_BUFFER_SIZE', 'SAM_PARAM_SIZE',
             'SAM_PARAM_DESTINATION', 'SAM_PING', 'SAM_SESSION_CREATE', 'SAM_STREAM_CONNECT',
             'SAM_STREAM_ACCEPT', 'SAM_STREAM_FORWARD', 'SAM_DEST_GENERATE', 'SAM_NAMING_LOOKUP',
             'SAM_SESSION_ADD', 'SAM_SESSION_REMOVE', 'SAM_DATAGRAM_SEND', 'SAM_RAW_SEND']
    result = []
    for name in names:
        match = re.search(r'(?m)^\s*(?:const|constexpr)\s+[^\n]*\b' + name + r'\b[^\n]*;', header)
        if match is None:
            raise ValueError('Missing SAM constant: ' + name)
        result.append(match.group().strip())
    return '\n'.join(result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--patch', type=Path)
    parser.add_argument('--compiler', default='clang++')
    parser.add_argument('--sanitizers', default='')
    args = parser.parse_args()
    source_path = args.source.resolve()
    header_path = source_path.with_suffix('.h')
    template = Path(__file__).with_name('i2pd_sam_frames.cpp.in').read_text()
    with tempfile.TemporaryDirectory(prefix='foxhole-sam-test-') as temporary:
        work = Path(temporary)
        if args.patch:
            tree = work / 'source'
            (tree / 'libi2pd_client').mkdir(parents=True)
            shutil.copyfile(source_path, tree / 'libi2pd_client/SAM.cpp')
            subprocess.run(['git', 'apply', str(args.patch.resolve())], cwd=tree, check=True)
            source_path = tree / 'libi2pd_client/SAM.cpp'
        source = source_path.read_text()
        methods = '\n\n'.join(method(source, name) for name in
                              ['ExtractParams', 'ProcessDatagramSend', 'HandleMessage', 'Receive'])
        cpp = work / 'sam_frames.cpp'
        cpp.write_text(template.replace('@SAM_CONSTANTS@', constants(header_path.read_text()))
                       .replace('@SAM_METHODS@', methods))
        failures = 0
        print('Testing native size_t and simulated 32-bit size_t arithmetic; this is not a 32-bit ABI run.', flush=True)
        for width in (64, 32):
            binary = work / ('sam_frames_' + str(width))
            flags = ['-DFOXHOLE_SAM_SIZE32'] if width == 32 else []
            if args.sanitizers:
                flags += ['-fsanitize=' + args.sanitizers, '-fno-omit-frame-pointer']
            subprocess.run([args.compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            *flags, str(cpp), '-o', str(binary)], check=True)
            result = subprocess.run([str(binary)], check=False)
            failures += result.returncode != 0
        return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())
