#!/usr/bin/env python3
"""Exact fresh Maven producer loader. Generated only from a verified actual receipt."""
import argparse
from pathlib import Path
import maven180_contract as contract

EXPECTED_PUBLIC_MANIFEST_SHA256 = '6d446f908dd26f1f3a6c7a48d53ac30fbd1185b2b2388f98c20ff258d7473100'
EXPECTED_PRODUCER_SHA256 = 'd4b00281d9c055d4ca788a46042624595bba99fc4f540cfa487afb25a97f731c'

def load(repo, bundle, expected_manifest_sha256=EXPECTED_PUBLIC_MANIFEST_SHA256, *, allow_fdroid_unsigned_projection=False):
    if allow_fdroid_unsigned_projection:
        raise ValueError('Actual final180 stock unsigned source projection has not been bound')
    return contract.load_bundle(repo, bundle, expected_manifest_sha256, expected_producer=EXPECTED_PRODUCER_SHA256)[0]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fdroid-unsigned-source', action='store_true')
    a = p.parse_args()
    files = load(a.repo, a.bundle, allow_fdroid_unsigned_projection=a.fdroid_unsigned_source)
    target = a.output / 'maven-runtime'
    target.mkdir()
    for name, data in files.items(): contract.write_new(target, name, data)
    contract.write_new(target, 'MANIFEST.json', contract.read(a.bundle / 'MANIFEST.json', EXPECTED_PUBLIC_MANIFEST_SHA256))

if __name__ == '__main__': main()
