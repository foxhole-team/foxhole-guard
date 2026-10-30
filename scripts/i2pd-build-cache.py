#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

SCHEMA = 1
STAMP = '.foxhole-i2pd-build-cache.json'
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def fingerprint(args):
    records = [['schema', SCHEMA], ['inputs', args.inputs],
               ['environment', [[name, os.environ.get(name, '')] for name in args.env]]]
    for name in args.file:
        path = Path(name).absolute()
        records.append(['file', str(path), str(path.resolve(strict=True)), file_hash(path)])
    for name in args.tree:
        root = Path(name).absolute()
        records.append(['tree', str(root), str(root.resolve(strict=True))])

        def visit(path, ancestors):
            relative = str(path.relative_to(root))
            if path.is_symlink():
                records.append(['link', relative, os.readlink(path)])
            if path.is_dir():
                real = path.resolve(strict=True)
                if real in ancestors:
                    raise ValueError('Cache input contains a symlink cycle: ' + str(path))
                for child in sorted(path.iterdir()):
                    if child.name != '.git':
                        visit(child, ancestors | {real})
            elif path.is_file():
                records.append(['content', relative, path.stat().st_mode & 0o777, file_hash(path)])
            else:
                raise ValueError('Unsupported cache input: ' + str(path))

        visit(root, set())
    payload = json.dumps(records, separators=(',', ':'), ensure_ascii=True).encode()
    print(hashlib.sha256(payload).hexdigest())


def build_directory(args):
    root = Path(args.root).absolute()
    build = Path(args.build).absolute()
    source = Path(args.source).resolve(strict=True)
    if root.resolve() == Path('/') or build.parent != root or build.name not in ABIS:
        raise ValueError('Unexpected i2pd cache layout')
    if build.is_symlink() or root.is_symlink():
        raise ValueError('i2pd build cache must not be a symlink')
    real_build = build.resolve()
    if source == real_build or source in real_build.parents or real_build in source.parents:
        raise ValueError('i2pd build cache must stay outside the source tree')
    return build


def prepare(args):
    build = build_directory(args)
    cache = build / 'CMakeCache.txt'
    reusable = False
    try:
        stamp = json.loads((build / STAMP).read_text())
        reusable = (stamp['schema'] == SCHEMA and stamp['key'] == args.key
                    and not cache.is_symlink() and stamp['cmake_cache'] == file_hash(cache))
    except (OSError, ValueError, KeyError, TypeError):
        pass
    if not reusable:
        if build.exists():
            shutil.rmtree(build)
        build.mkdir(parents=True)
    print('[build-i2pd] [' + build.name + '] CMake cache ' + ('reuse' if reusable else 'reset'), file=os.sys.stderr)


def commit(args):
    build = build_directory(args)
    stamp = {'schema': SCHEMA, 'key': args.key, 'cmake_cache': file_hash(build / 'CMakeCache.txt')}
    descriptor, temporary = tempfile.mkstemp(prefix=STAMP + '.', dir=build)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(stamp, output, sort_keys=True)
            output.write('\n')
        os.replace(temporary, build / STAMP)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser()
    actions = parser.add_subparsers(dest='action', required=True)
    digest = actions.add_parser('fingerprint')
    digest.add_argument('--file', action='append', default=[])
    digest.add_argument('--tree', action='append', default=[])
    digest.add_argument('--env', action='append', default=[])
    digest.add_argument('inputs', nargs='*')
    for action in ('prepare', 'commit'):
        command = actions.add_parser(action)
        command.add_argument('--root', required=True)
        command.add_argument('--build', required=True)
        command.add_argument('--source', required=True)
        command.add_argument('--key', required=True)
    args = parser.parse_args()
    try:
        {'fingerprint': fingerprint, 'prepare': prepare, 'commit': commit}[args.action](args)
    except (OSError, ValueError) as error:
        parser.exit(1, '[build-i2pd] cache error: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
