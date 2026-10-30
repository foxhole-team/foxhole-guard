#!/usr/bin/env python3
"""Generate the ARMv7 GitHub updater manifest from a signed public Guard APK."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import zipfile

CERTIFICATE = 'e59de2486084c38f3c77e9df0eb5eff9a4559f3c68024f1208e0e9c04b0df665'
MANIFEST_NAME = 'update-manifest-armeabi-v7a.json'
REQUIRED = {'libfoxhole_native.so', 'libfoxhole_sentinel_yara.so', 'libi2pd.so', 'liblyrebird.so', 'libconjure_client.so'}
MAX_APK_BYTES = 256 * 1024**2

def verify_armv7_inventory(apk):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(entries) > 100000 or len(names) != len(set(names)) or sum(entry.file_size for entry in entries) > 512 * 1024**2:
            raise ValueError('APK inventory is duplicated or oversized')
        libraries = [entry for entry in entries if entry.filename.startswith('lib/') and entry.filename.endswith('.so')]
        for entry in libraries:
            path = PurePosixPath(entry.filename)
            if len(path.parts) != 3 or path.parts[1] != 'armeabi-v7a' or not 20 <= entry.file_size <= MAX_APK_BYTES:
                raise ValueError('APK must contain only ARMv7 native libraries')
            with archive.open(entry) as stream:
                header = stream.read(20)
            if header[:7] != b'\x7fELF\x01\x01\x01' or struct.unpack_from('<H', header, 18)[0] != 40:
                raise ValueError('APK native library is not a little-endian ARM32 ELF')
        if not REQUIRED <= {PurePosixPath(entry.filename).name for entry in libraries}:
            raise ValueError('APK omits a required Guard native module')

def generate(apk, asset_name, expected_tag, apksigner, aapt2, pin):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,190}\.apk', asset_name) or not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError('Invalid APK asset name or size')
    if pin != CERTIFICATE:
        raise ValueError('Release certificate configuration changed')
    verification = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(apk)],
                                  check=True, capture_output=True, text=True, timeout=60).stdout
    signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$', verification, re.M)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise ValueError('APK does not use the canonical release certificate')
    badging = subprocess.run([aapt2, 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True, timeout=60).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != 'com.foxhole.guard' or int(package.group(2)) < 1:
        raise ValueError('APK must be the public Guard package')
    code, version = int(package.group(2)), package.group(3)
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?', version) or expected_tag != 'v' + version:
        raise ValueError('Release tag differs from APK version')
    if "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'" not in badging:
        raise ValueError('Updater manifests require the GitHub APK channel')
    verify_armv7_inventory(apk)
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'versionCode': code, 'versionName': version, 'apkName': asset_name, 'apkSha256': digest,
            'notes': 'See the release notes on GitHub.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--asset-name', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apksigner', required=True)
    parser.add_argument('--aapt2', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.name != MANIFEST_NAME or args.output.exists():
        raise ValueError('ARMv7 manifest must use its distinct new output path')
    pin = (Path(__file__).resolve().parents[1] / 'config/release-cert-sha256.txt').read_text().strip().lower()
    value = generate(args.apk, args.asset_name, args.tag, args.apksigner, args.aapt2, pin)
    with args.output.open('x') as stream:
        stream.write(json.dumps(value, indent=2) + '\n')

if __name__ == '__main__':
    main()
