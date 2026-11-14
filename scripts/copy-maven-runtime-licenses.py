#!/usr/bin/env python3
"""Exact fresh Maven producer loader. Generated only from a verified actual receipt."""
import argparse
import importlib.util
from pathlib import Path

_contract_spec = importlib.util.spec_from_file_location('foxhole_maven_contract', Path(__file__).with_name('maven180_contract.py'))
contract = importlib.util.module_from_spec(_contract_spec)
_contract_spec.loader.exec_module(contract)

EXPECTED_PUBLIC_MANIFEST_SHA256 = '75b66bb34265363b425b244c25ef9fcf46bd412dfce2dafc991dfb8282c87be2'
EXPECTED_PRODUCER_SHA256 = 'd15be1746409265404e7d86bb64bd60a7bf97947ca8de2667abf12b24a12fb63'

def load(repo, bundle, expected_manifest_sha256=EXPECTED_PUBLIC_MANIFEST_SHA256, *, allow_fdroid_unsigned_projection=False):
    return contract.load_bundle(repo, bundle, expected_manifest_sha256, expected_producer=EXPECTED_PRODUCER_SHA256,
        fdroid_unsigned_source=allow_fdroid_unsigned_projection)[0]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--previous-bundle', type=Path)
    p.add_argument('--fdroid-unsigned-source', action='store_true')
    a = p.parse_args()
    files = load(a.repo, a.bundle, allow_fdroid_unsigned_projection=a.fdroid_unsigned_source)
    target = a.output / 'maven-runtime'
    target.mkdir()
    for name, data in files.items(): contract.write_new(target, name, data)
    contract.write_new(target, 'MANIFEST.json', contract.read(a.bundle / 'MANIFEST.json', EXPECTED_PUBLIC_MANIFEST_SHA256))

if __name__ == '__main__': main()
