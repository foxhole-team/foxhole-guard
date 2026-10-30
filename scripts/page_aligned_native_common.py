"""Small shared source/command checks for task-owned native producers."""
import hashlib
from pathlib import Path, PurePosixPath
import subprocess
import tarfile


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def run(argv, directory, logfile, env=None, expected=0, limit=300):
    with logfile.open('w') as log:
        result = subprocess.run(argv, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT,
                                timeout=limit, check=False)
    if result.returncode != expected:
        raise ValueError(f'{logfile.name}: actual exit {result.returncode}, expected {expected}')
    return {'argv': argv, 'exitCode': result.returncode, 'logSha256': sha(logfile)}


def extract(archive, destination, prefix=None):
    destination.mkdir()
    root = destination.resolve()
    with tarfile.open(archive) as stream:
        members = stream.getmembers()
        if len(members) > 4096 or sum(m.size for m in members) > 64 * 1024 * 1024:
            raise ValueError('Source archive exceeds the owned source budget')
        names = set()
        for member in members:
            name = member.name
            normalized = PurePosixPath(name)
            if (name.startswith('/') or '..' in normalized.parts or not normalized.parts or
                    normalized.as_posix() != name.rstrip('/') or
                    any(ord(c) < 32 or c == '\\' for c in name) or
                    member.size < 0 or (member.isdir() and member.size != 0) or
                    not (member.isfile() or member.isdir()) or normalized.as_posix() in names):
                raise ValueError('Unsafe or duplicate archive member')
            names.add(normalized.as_posix())
        for member in members:
            target = root.joinpath(*PurePosixPath(member.name).parts)
            if not target.resolve().is_relative_to(root):
                raise ValueError('Source member escaped its owned directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            parent = target.parent
            while parent != root:
                if parent.is_symlink():
                    raise ValueError('Source parent is a link')
                parent = parent.parent
            if member.isdir():
                target.mkdir(exist_ok=True)
                if target.is_symlink() or not target.is_dir():
                    raise ValueError('Source directory conflicts with a file')
                continue
            source = stream.extractfile(member)
            if source is None:
                raise ValueError('Regular source member has no body')
            remaining = member.size
            with source, target.open('xb') as output:
                while remaining:
                    data = source.read(min(65536, remaining))
                    if not data:
                        raise ValueError('Truncated source member')
                    output.write(data)
                    remaining -= len(data)
            target.chmod(member.mode & 0o777)
    return destination / prefix if prefix else destination


def tree(directory):
    records = {}
    for path in directory.rglob('*'):
        if path.is_symlink():
            raise ValueError('Unexpected link in compile source')
        if path.is_file():
            records[str(path.relative_to(directory))] = {
                'sha256': sha(path), 'mode': path.stat().st_mode & 0o777, 'bytes': path.stat().st_size}
    return records


def symbol_set(tool, binary):
    result = subprocess.run([str(tool), '--dynamic', '--defined-only', '--format=posix', str(binary)],
                            capture_output=True, text=True, check=True, timeout=30)
    return sorted({line.split()[0] for line in result.stdout.splitlines() if line.strip()})


def require_graphics_export_surface(original, derived):
    if original != derived or not any(name.split('@', 1)[0] == 'JNI_OnLoad' for name in derived):
        raise ValueError('Original graphics1.0.1 JNI/export surface differs')
