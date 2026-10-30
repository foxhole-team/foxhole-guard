#!/usr/bin/env python3
"""Fetch and freeze exact publisher inputs for the graphics native rebuild."""
import argparse
import base64
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tarfile
import time
import urllib.parse
import urllib.request
import zipfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require_cache_parent(target):
    parent = target.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise ValueError('Pinned source cache parent is a link')
        parent = parent.parent


def download(identity):
    url = identity['url']
    parsed = urllib.parse.urlsplit(url)
    allowed = {'android.googlesource.com', 'dl.google.com', 'download.libsodium.org', 'repo.maven.apache.org'}
    if parsed.scheme != 'https' or parsed.hostname not in allowed or parsed.username or parsed.password:
        raise ValueError('Unexpected source publisher URL')
    started = time.monotonic()
    request = urllib.request.Request(url, headers={'User-Agent': 'FoxHole-native-source/1.0'})
    with urllib.request.urlopen(request, timeout=30) as response:
        redirect = urllib.parse.urlsplit(response.url)
        if redirect.scheme != 'https' or redirect.hostname not in allowed or redirect.username or redirect.password:
            raise ValueError('Unexpected source publisher redirect')
        data = bytearray()
        while True:
            if time.monotonic() - started > 45:
                raise ValueError('Source download deadline exceeded')
            chunk = response.read(65536)
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > 16 * 1024 * 1024:
                raise ValueError('Source download budget exceeded')
    return bytes(data)


def fetch(identity, target, offline):
    require_cache_parent(target)
    if target.is_file():
        if target.is_symlink() or sha(target) != identity['sha256']:
            raise ValueError('Pinned source cache differs: ' + target.name)
        return
    if offline:
        raise ValueError('Pinned source cache is unavailable offline: ' + target.name)
    data = download(identity)
    if hashlib.sha256(data).hexdigest() != identity['sha256']:
        raise ValueError('Publisher source digest differs: ' + target.name)
    with target.open('xb') as stream:
        stream.write(data)


def canonical_graphics_source(data, source_files):
    """Gitiles adds request timestamps; only pinned Git blobs enter the USTAR."""
    if (not 0 < len(data) <= 16 * 1024 * 1024 or not isinstance(source_files, dict) or
            len(source_files) != 12):
        raise ValueError('Graphics publisher source budget or pin set differs')
    directories = set()
    for name, pin in source_files.items():
        path = PurePosixPath(name)
        if (not name.isascii() or path.is_absolute() or path.as_posix() != name or
                any(part in ('', '.', '..') for part in name.split('/')) or
                any(ord(c) < 32 or c == '\\' for c in name) or
                re.fullmatch('[0-9a-f]{64}', pin.get('sha256', '')) is None or
                re.fullmatch('[0-9a-f]{40}', pin.get('gitBlobSha1', '')) is None):
            raise ValueError('Graphics Git blob pin differs')
        directories.update(parent.as_posix() for parent in path.parents if parent != PurePosixPath('.'))
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
        raw = stream.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('Graphics source tar budget exceeded')
    files, seen = {}, set()
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as archive:
        for member in archive:
            name = member.name.rstrip('/') if member.isdir() else member.name
            path = PurePosixPath(name)
            if (len(seen) >= 32 or not name.isascii() or path.is_absolute() or path.as_posix() != name or
                    any(part in ('', '.', '..') for part in name.split('/')) or
                    any(ord(c) < 32 or c == '\\' for c in name) or name in seen or
                    (member.isdir() and member.name not in (name, name + '/'))):
                raise ValueError('Unsafe or duplicate graphics source member')
            seen.add(name)
            if member.isdir():
                if (name not in directories or member.size != 0 or stat.S_IMODE(member.mode) != 0o755 or
                        member.mode & ~0o7777 not in (0, stat.S_IFDIR)):
                    raise ValueError('Unexpected graphics source directory')
                continue
            if (member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE) or member.linkname or name not in source_files or
                    not 0 < member.size <= 256 * 1024 or stat.S_IMODE(member.mode) != 0o644 or
                    member.mode & ~0o7777 not in (0, stat.S_IFREG)):
                raise ValueError('Unexpected graphics source file, type, mode or size')
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError('Graphics source member has no body')
            with stream:
                body = stream.read(member.size + 1)
            pin = source_files[name]
            blob = hashlib.sha1(b'blob ' + str(len(body)).encode('ascii') + b'\0' + body).hexdigest()
            if (len(body) != member.size or hashlib.sha256(body).hexdigest() != pin['sha256'] or
                    blob != pin['gitBlobSha1']):
                raise ValueError('Graphics source content or Git blob differs: ' + name)
            files[name] = body
        if any(raw[archive.offset:]):
            raise ValueError('Unexpected trailing graphics archive data')
    if set(files) != set(source_files):
        raise ValueError('Graphics source file set differs')
    result = io.BytesIO()
    with tarfile.open(fileobj=result, mode='w', format=tarfile.USTAR_FORMAT, encoding='ascii') as archive:
        for name in sorted(files):
            info = tarfile.TarInfo(name)
            info.type, info.mode, info.uid, info.gid = tarfile.REGTYPE, 0o644, 0, 0
            info.uname, info.gname, info.mtime, info.size = '', '', 0, len(files[name])
            archive.addfile(info, io.BytesIO(files[name]))
    return result.getvalue()


def fetch_graphics_source(graphics, target, offline):
    identity = graphics['sourceArchive']
    url = urllib.parse.urlsplit(identity['url'])
    if (identity.get('format') != 'canonical-ustar-v1' or
            re.fullmatch('[0-9a-f]{64}', identity.get('sha256') or '') is None or
            re.fullmatch('[0-9a-f]{64}', identity.get('historicalPublisherArchiveSha256') or '') is None or
            url.scheme != 'https' or url.netloc != 'android.googlesource.com' or url.query or url.fragment or
            re.fullmatch('[0-9a-f]{40}', graphics['commit']) is None or
            url.path != '/platform/frameworks/support/+archive/' + graphics['commit'] +
            '/graphics/graphics-path/src/main/cpp.tar.gz'):
        raise ValueError('Canonical graphics source identity differs')
    require_cache_parent(target)
    if target.exists() or target.is_symlink() or offline:
        fetch(identity, target, True)
        return
    data = canonical_graphics_source(download(identity), graphics['sourceFiles'])
    if hashlib.sha256(data).hexdigest() != identity['sha256']:
        raise ValueError('Canonical graphics source archive digest differs')
    with target.open('xb') as stream:
        stream.write(data)


def prepare(repo, destination, cache, offline, component='graphics'):
    if destination.exists():
        raise ValueError('Compile input directory must be new')
    destination.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    config = repo / 'config/native/page-aligned-native.json'
    components = json.loads(config.read_text())
    if component == 'sodium':
        return prepare_sodium(repo, destination, cache, offline, config, components['sodium'])
    graphics = components['graphics']
    names = {'sourceArchive': 'graphics-path-1.0.1-native.tar',
             'originalAar': 'graphics-path-1.0.1.aar',
             'originalSourceJar': 'graphics-path-1.0.1-sources.jar'}
    for key, name in names.items():
        cached = cache / name
        if key == 'sourceArchive':
            fetch_graphics_source(graphics, cached, offline)
        else:
            fetch(graphics[key], cached, offline)
        shutil.copyfile(cached, destination / name)
    producer = repo / 'scripts/build-graphics-page-aligned.py'
    common = repo / 'scripts/page_aligned_native_common.py'
    shutil.copyfile(producer, destination / producer.name)
    shutil.copyfile(common, destination / common.name)
    consumer = destination / 'consumer/scripts'
    consumer.mkdir(parents=True)
    for name in ['verify-native-pages.py', 'verify-native-host-paths.sh']:
        shutil.copyfile(repo / 'scripts' / name, consumer / name)
    with zipfile.ZipFile(destination / names['originalAar']) as archive:
        for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
            member = 'jni/' + abi + '/libandroidx.graphics.path.so'
            target = destination / 'original-native' / abi / 'libandroidx.graphics.path.so'
            target.parent.mkdir(parents=True)
            target.write_bytes(archive.read(member))
    source_licence = graphics['sourceLicense']
    cached = cache / 'graphics-source-LICENSE.base64'
    fetch(source_licence, cached, offline)
    (destination / 'original-source-LICENSE.txt').write_bytes(base64.b64decode(cached.read_bytes(), validate=True))
    manifest = {'schema': 1, 'graphicsReleaseCommit': graphics['commit'],
                'graphicsSourceFiles': {name: item['sha256'] for name, item in graphics['sourceFiles'].items()},
                'files': {path.relative_to(destination).as_posix(): sha(path)
                          for path in destination.rglob('*') if path.is_file()},
                'repoInputHashes': {path.relative_to(repo).as_posix(): sha(path) for path in
                                   [config, producer, common, repo / 'scripts/build-page-aligned-native.sh',
                                    repo / 'scripts/prepare-page-aligned-native-inputs.py', repo / 'scripts/verify-native-pages.py',
                                    repo / 'scripts/verify-native-host-paths.sh']},
                'scope': 'Pinned original Maven Java/native and exact publisher source for a separately derived native build.'}
    (destination / 'producer-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')


def prepare_sodium(repo, destination, cache, offline, config, sodium):
    names = {'sourceArchive': 'libsodium-1.0.20.tar.gz', 'originalAar': 'lazysodium-android-5.2.0.aar'}
    for key, name in names.items():
        cached = cache / name
        fetch(sodium[key], cached, offline)
        shutil.copyfile(cached, destination / name)
    patch = repo / sodium['patch']['path']
    if sha(patch) != sodium['patch']['sha256']:
        raise ValueError('Approved upstream sodium security patch differs')
    shutil.copyfile(patch, destination / 'libsodium-CVE-2025-69277-upstream.patch')
    producer = repo / 'scripts/build-sodium-page-aligned.py'
    common = repo / 'scripts/page_aligned_native_common.py'
    shutil.copyfile(producer, destination / producer.name)
    shutil.copyfile(common, destination / common.name)
    consumer = destination / 'consumer/scripts'
    consumer.mkdir(parents=True)
    for name in ['verify-native-pages.py', 'verify-native-host-paths.sh']:
        shutil.copyfile(repo / 'scripts' / name, consumer / name)
    with zipfile.ZipFile(destination / names['originalAar']) as archive:
        for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
            target = destination / 'original-native' / abi / 'libsodium.so'
            target.parent.mkdir(parents=True)
            target.write_bytes(archive.read('jni/' + abi + '/libsodium.so'))
    inventory = repo / sodium['sourceInventory']['path']
    if sha(inventory) != sodium['sourceInventory']['sha256']:
        raise ValueError('Original/effective sodium reference inventory differs')
    manifest = {'schema': 1, 'upstreamFixCommit': sodium['upstreamSecurityFixCommit'],
                'sodiumPatchedFiles': sodium['effectivePatchedFiles'],
                'files': {p.relative_to(destination).as_posix(): sha(p)
                          for p in destination.rglob('*') if p.is_file()},
                'repoInputHashes': {p.relative_to(repo).as_posix(): sha(p) for p in
                                   [config, producer, common, patch, inventory,
                                    repo / 'scripts/build-page-aligned-native.sh',
                                    repo / 'scripts/prepare-page-aligned-native-inputs.py',
                                    repo / 'scripts/verify-native-pages.py', repo / 'scripts/verify-native-host-paths.sh']},
                'scope': 'Authenticated pinned original1.0.20 with exact upstream CVE backport; original Java remains Maven5.2.'}
    (destination / 'producer-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--component', choices=['graphics', 'sodium'], default='graphics')
    args = parser.parse_args()
    prepare(args.repo.resolve(), args.destination, args.cache, args.offline, args.component)


if __name__ == '__main__':
    main()
