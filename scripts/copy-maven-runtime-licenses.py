#!/usr/bin/env python3
"""Copy only a bounded, source-bound actual Maven notice bundle."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil


HOOK = '    inputs.file(rootProject.file("scripts/copy-maven-runtime-licenses.py"))\n    inputs.dir(rootProject.file("third_party/maven-runtime"))\n'
EXPECTED_PUBLIC_MANIFEST_SHA256 = '89476592db530b111b041d5b5795cbe186b3913ee9f363207d8ba4633682012f'
ACTUAL_CAPTURE_SHA256 = 'f95d54be9840fe2e5eec4df4fe82d5dc1555f4cb426080b5536278bd098da413'
ACTUAL_COLLECTOR_REPORT_SHA256 = '6ba294c123d6e9724908945c5d0eed03b26a6c773ca370de2ef85cebacf746e2'
FDROID_UNSIGNED_PROJECTION = {'mode': 'exact-stock-fdroid-unsigned-source',
 'originalBoundSourceSha256': '162ef6953c4dec3c34ba11a4a954dce585cfdd3c96184e24c96a32ac2a881ba1',
 'unsignedSourceSha256': '21b8a7b1f65debd737883ae4114d2f03794c22a16627a4a0696c7e3ae8a59e2e',
 'transformation': {'function': 'fdroidserver.common.remove_signing_keys',
                    'functionSha256': '9fe344aa703b2ab4414d3e9eb3b3ff90c7b76dd4168749f9eebe0d878b40d5cc',
                    'module': 'fdroidserver/common.py',
                    'moduleSha256': '3dfa26b876ba70af14f4348bf2b297d1603f448c79fdcad514d51223ccb8607b',
                    'officialImage': 'sha256:1c5999b794734ef08c6108fe491e21f063b8aed8e9a4fb60ff75e806690146b6',
                    'upstreamRepository': 'https://gitlab.com/fdroid/fdroidserver',
                    'gitRevision': None,
                    'version': None},
 'scope': 'Stock removal of release signing configuration only; exact full input/output hashes; '
          'dependency, plugin and repository declarations unchanged; no recapture or '
          'native/reproducibility acceptance'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def matches_captured_gradle(data, expected, allow_fdroid_unsigned_projection=False):
    """Accept only the exact bound source or its opt-in stock unsigned projection."""
    if expected != '31d354e6572649d9e5f47cd6cf0110ff8a766d63331fc819d3b80c949345a06b':
        return False
    # ABI output selection cannot change the pinned Maven dependency graph.
    if data.count(b'isUniversalApk = true') == 1:
        data = data.replace(b'isUniversalApk = true', b'isUniversalApk = false')
    actual = digest(data)
    return (actual == FDROID_UNSIGNED_PROJECTION['originalBoundSourceSha256'] or
            allow_fdroid_unsigned_projection and actual == FDROID_UNSIGNED_PROJECTION['unsignedSourceSha256'])


def regular(root, name):
    relative = PurePosixPath(name)
    if relative.is_absolute() or not relative.parts or '..' in relative.parts:
        raise ValueError('Maven legal bundle path escaped')
    path = root.joinpath(*relative.parts)
    if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Maven legal bundle regular input is missing')
    parent = path.parent
    while parent != root:
        if parent.is_symlink():
            raise ValueError('Maven legal bundle parent is a link')
        parent = parent.parent
    return path


def load(repo, bundle, expected_manifest_sha256=EXPECTED_PUBLIC_MANIFEST_SHA256, *, allow_fdroid_unsigned_projection=False):
    if repo.is_symlink() or bundle.is_symlink():
        raise ValueError('Maven legal root is a link')
    manifest_path = regular(bundle, 'MANIFEST.json')
    if not 0 < manifest_path.stat().st_size <= 1024 * 1024:
        raise ValueError('Maven legal manifest size budget exceeded')
    manifest_bytes = manifest_path.read_bytes()
    if digest(manifest_bytes) != expected_manifest_sha256:
        raise ValueError('Maven legal manifest is not the actual captured bundle')
    manifest = json.loads(manifest_bytes)
    if manifest.get('schema') != 1 or manifest.get('scope') != 'actual-guard-release-runtime-notices':
        raise ValueError('Unsupported Maven legal manifest')
    rows = manifest.get('files')
    if not isinstance(rows, list) or not rows or len(rows) > 512:
        raise ValueError('Maven legal bundle inventory is not bounded')
    checked = {}
    budget = 0
    for row in rows:
        if set(row) != {'path', 'bytes', 'sha256'} or row['path'] in checked:
            raise ValueError('Duplicate or invalid Maven legal inventory row')
        if type(row['bytes']) is not int or not 0 < row['bytes'] <= 8 * 1024 * 1024:
            raise ValueError('Maven legal file budget exceeded')
        path = regular(bundle, row['path'])
        if path.stat().st_size != row['bytes'] or path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError('Maven legal captured file size differs')
        data = path.read_bytes()
        budget += len(data)
        if budget > 16 * 1024 * 1024 or len(data) != row['bytes'] or digest(data) != row['sha256']:
            raise ValueError('Maven legal captured file changed')
        checked[row['path']] = data
    actual = {path.relative_to(bundle).as_posix() for path in bundle.rglob('*') if path.is_file()}
    if actual != set(checked) | {'MANIFEST.json'}:
        raise ValueError('Maven legal bundle files are missing or foreign')
    capture = json.loads(checked['runtime-inventory.json'])
    report = json.loads(checked['maven-notice-report.json'])
    projection = json.loads(checked['PORTABLE-PROVENANCE.json'])
    if (projection.get('actualCaptureSha256') != ACTUAL_CAPTURE_SHA256 or
            projection.get('actualCollectorReportSha256') != ACTUAL_COLLECTOR_REPORT_SHA256 or
            projection.get('portableCaptureSha256') != digest(checked['runtime-inventory.json']) or
            projection.get('derivedCollectorReportSha256') != digest(checked['maven-notice-report.json']) or
            report.get('derived_from_actual_report_sha256') != ACTUAL_COLLECTOR_REPORT_SHA256 or
            projection.get('removedFields') != {'artifact.path': 122, 'pom.original_query_failures': 169}):
        raise ValueError('Maven portable provenance chain differs from actual capture')
    if any(set(row) != {'component', 'name', 'bytes', 'sha256', 'variant'} for row in capture['artifacts']):
        raise ValueError('Maven portable artifact fields are not whitelisted')
    if any('original_query_failures' in row for row in capture['pom_files']):
        raise ValueError('Maven portable capture contains private failure paths')
    identity = {'components': capture['components'], 'edges': capture['edges'], 'artifacts': capture['artifacts'],
                'configuration': capture['configuration'], 'source_sha256': capture['source_sha256']}
    if digest(json.dumps(identity, sort_keys=True).encode()) != projection.get('artifactIdentityAndHashProjectionSha256'):
        raise ValueError('Maven portable graph or artifact identity changed')
    if any(b'/srv/foxhole/' in data or b'/Users/' in data for data in checked.values()):
        raise ValueError('Maven portable legal bundle contains private build paths')
    if capture.get('pom_errors') or report.get('missing') or report.get('capture_sha256') != digest(checked['runtime-inventory.json']):
        raise ValueError('Maven legal graph or original coverage is incomplete')
    if capture.get('configuration') != ':app:releaseRuntimeClasspath' or len(capture.get('components', [])) != 174 or len(capture.get('artifacts', [])) != 122:
        raise ValueError('Maven legal selected graph differs')
    identities = [row['id'] for row in capture['components']]
    if len(set(identities)) != len(identities) or capture.get('root') not in identities:
        raise ValueError('Maven legal graph has duplicate or missing root identity')
    if any(row.get('from') not in identities or row.get('to') not in identities for row in capture.get('edges', [])):
        raise ValueError('Maven legal graph has unresolved edges')
    source_policy = {'app/build.gradle.kts': {'mode': 'exact-Core-android4-and-fdroid-source-native-rebind', 'capturedSha256': '31d354e6572649d9e5f47cd6cf0110ff8a766d63331fc819d3b80c949345a06b', 'previousBoundSourceSha256': '41f22b62565e33fe47432d7aaa9908cc770b64802b7c5f6ec0574d67bc573ee2', 'boundSourceSha256': '162ef6953c4dec3c34ba11a4a954dce585cfdd3c96184e24c96a32ac2a881ba1', 'sourceChangeScope': 'Core checkout/source identity, explicit android4 dispatch, F-Droid-only source-native provenance/staging, and native-build log routing; Maven dependency declarations, plugins and repositories unchanged'}}
    source_policy['app/build.gradle.kts']['fdroidUnsignedProjection'] = FDROID_UNSIGNED_PROJECTION
    identity_policy = {'app/build.gradle.kts': {'mode': 'identity', 'capturedSha256': capture['source_sha256']['app/build.gradle.kts']}}
    projected = projection.get('sourceInputProjections')
    if projected not in (identity_policy, source_policy) or (projected == source_policy and capture['source_sha256']['app/build.gradle.kts'] != source_policy['app/build.gradle.kts']['capturedSha256']):
        raise ValueError('Maven current source identity projection differs')
    for name, expected in capture['source_sha256'].items():
        source_path = regular(repo, name)
        if source_path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError('Maven legal source input size exceeded')
        data = source_path.read_bytes()
        if name == 'app/build.gradle.kts':
            text = data.decode()
            if text.count(HOOK) != 1:
                raise ValueError('Maven legal Gradle projection hook differs')
            # Current capture/provenance bytes and declarations retain exact identity.
            matches = (matches_captured_gradle(data, expected, allow_fdroid_unsigned_projection) if projected == source_policy else digest(data) == expected)
        else:
            matches = digest(data) == expected
        if not matches:
            raise ValueError('Maven legal source graph is stale: ' + name)
    for component in report['components']:
        for row in component['files'] + component['shared_licenses']:
            path = 'files/' + row['path']
            if path not in checked or digest(checked[path]) != row['sha256']:
                raise ValueError('Maven original license owner binding differs')
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fdroid-unsigned-source', action='store_true')
    args = parser.parse_args()
    if args.repo.is_symlink() or args.bundle.is_symlink():
        raise ValueError('Maven legal root is a link')
    checked = load(args.repo.resolve(), args.bundle.resolve(), allow_fdroid_unsigned_projection=args.fdroid_unsigned_source)
    target = args.output / 'maven-runtime'
    target.mkdir()
    for name, data in checked.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    shutil.copyfile(args.bundle / 'MANIFEST.json', target / 'MANIFEST.json')


if __name__ == '__main__':
    main()
