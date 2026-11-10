"""PREPARED NOT RUN: strict fresh runtime + core-library legal producer contract."""
import copy
import hashlib
import json
import re
import stat
from pathlib import Path, PurePosixPath

SCOPES = {'runtime': ':app:publicReleaseRuntimeClasspath', 'desugaring': ':app:coreLibraryDesugaring'}
SOURCE_INPUTS = {
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'app/build.gradle.kts',
    'core/model/build.gradle.kts', 'core/network/build.gradle.kts', 'core/importer/build.gradle.kts',
    'core/profile/build.gradle.kts', 'core/sentinel/build.gradle.kts', 'core/runtime/build.gradle.kts',
    'gradle/libs.versions.toml', 'gradle/sbom.gradle.kts', 'gradle/verification-metadata.xml',
}
NIO = 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
NIO_CONFIG = 'com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5'
POM_LICENSES = {
    NIO: ('GNU General Public License, version 2, with the Classpath Exception',
          'https://github.com/google/desugar_jdk_libs/blob/master/LICENSE'),
    NIO_CONFIG: ('BSD-3-Clause', 'https://opensource.org/licenses/BSD-3-Clause'),
}
COLLECTOR_TEMPLATE_SHA256 = 'fddf4a3f27fa84ae2b785c3ee986a4dc21a08a0eaca43de910f632251e4117c1'
G6_MANIFEST = '89476592db530b111b041d5b5795cbe186b3913ee9f363207d8ba4633682012f'
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024


def need(value, message):
    if not value:
        raise ValueError('Maven180: ' + message)


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pin(value):
    need(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), 'invalid digest')
    return value


def relative(name):
    p = PurePosixPath(name)
    need(not p.is_absolute() and p.parts and not any(x in ('..', '.git', 'AGENTS.md') for x in p.parts), 'unsafe relative path')
    return p


def read(path, expected=None, cap=1024 * 1024):
    path = Path(path)
    s = path.lstat()
    need(stat.S_ISREG(s.st_mode) and s.st_nlink == 1 and 0 < s.st_size <= cap,
         'regular bounded input required: ' + path.name)
    need(not (s.st_mode | path.parent.stat().st_mode) & 0o022, 'input or parent writable by others: ' + path.name)
    need(not path.is_symlink(), 'input is a link')
    data = path.read_bytes()
    need(len(data) == s.st_size, 'input changed during read')
    if expected is not None:
        need(sha(data) == pin(expected), 'input digest differs: ' + path.name)
    return data


def member(root, name):
    need(not Path(root).is_symlink(), 'linked legal/source root')
    root = Path(root).resolve()
    name = relative(name)
    path = root.joinpath(*name.parts)
    parent = path
    while parent != root:
        need(not parent.is_symlink(), 'linked bundle/source path')
        parent = parent.parent
    need(path.resolve().is_relative_to(root), 'escaped bundle/source path')
    return path


def rows(items, key):
    result = {x[key]: x for x in items}
    need(len(result) == len(items), 'duplicate ' + key)
    return result


def counts(capture):
    return {k: len(capture[k]) for k in ('components', 'artifacts', 'pom_files', 'edges')}


def portable(capture):
    result = copy.deepcopy(capture)
    for row in result['artifacts']:
        need('path' in row, 'raw artifact path absent')
        row.pop('path')
    for row in result['pom_files']:
        row.pop('original_query_failures', None)
    return result


def validate_scope_view(scope, capture, admission, *, is_portable=False):
    need(scope in SCOPES, 'scope not in closed allowlist')
    need(capture.get('schema_version') == 1 and capture.get('configuration') == SCOPES[scope], 'wrong configuration scope')
    need(capture.get('additional_configurations') == [], 'unexpected nested/implicit configurations')
    need(capture.get('offline') is True and capture.get('pom_errors') == [], 'complete offline capture required')
    need(capture.get('gradle_version') == admission['gradleVersion'], 'Gradle identity differs')
    need(capture.get('source_sha256') == admission['sourceInputs'] and set(admission['sourceInputs']) == SOURCE_INPUTS,
         'exact13 current source inputs differ')
    for digest in admission['sourceInputs'].values():
        pin(digest)
    components = rows(capture['components'], 'id')
    need(capture['root'] in components and components[capture['root']]['kind'] == 'project', 'invalid graph root')
    for identity, component in components.items():
        if component['kind'] == 'project':
            need(component['version'] == admission['versionName'], 'project source version differs')
        else:
            need(component['kind'] == 'maven' and identity == ':'.join(component[k] for k in ('group', 'name', 'version')),
                 'external component identity differs')
            need(not component['version'].endswith('-SNAPSHOT'), 'mutable dependency')
    keys = {'component', 'name', 'bytes', 'sha256', 'variant'} | (set() if is_portable else {'path'})
    seen = set()
    for artifact in capture['artifacts']:
        need(set(artifact) == keys, 'artifact field set differs')
        need(artifact['component'] in components and components[artifact['component']]['kind'] == 'maven', 'unknown artifact owner')
        need(relative(artifact['name']).name == artifact['name'], 'artifact is not a basename')
        need(type(artifact['bytes']) is int and 0 < artifact['bytes'] <= 128 * 1024 * 1024, 'artifact bound exceeded')
        pin(artifact['sha256'])
        key = (artifact['component'], artifact['name'])
        need(key not in seen, 'duplicate artifact')
        seen.add(key)
    for edge in capture['edges']:
        need(set(edge) == {'from', 'to', 'constraint', 'requested', 'selectedVariant'}, 'edge field set differs')
        need(edge['from'] in components and edge['to'] in components, 'unresolved edge')
    poms = rows(capture['pom_files'], 'coordinate')
    for pom in poms.values():
        expected = {'coordinate', 'path', 'sha256', 'parent', 'resolution', 'licenses'}
        need(set(pom) <= expected | {'original_query_failures'} and expected <= set(pom), 'POM field set differs')
        need(not is_portable or 'original_query_failures' not in pom, 'private POM field in portable capture')
        relative(pom['path'])
        pin(pom['sha256'])
        need(pom['resolution'] in ('MavenPomArtifact query', 'detached exact @pom artifact (non-transitive)'), 'unapproved POM resolution')
    declarations = rows(capture['licenses'], 'component')
    need(set(declarations) == {k for k, v in components.items() if v['kind'] == 'maven'}, 'effective POM declarations incomplete')
    need(counts(capture) == admission['scopes'][scope]['counts'], 'actual admitted counts differ')
    if scope == 'desugaring':
        need({k for k, v in components.items() if v['kind'] == 'maven'} == {NIO, NIO_CONFIG}, 'unreviewed desugaring graph delta')
        need({x['component'] for x in capture['artifacts']} == {NIO, NIO_CONFIG}, 'NIO artifacts missing')
        for coordinate, pair in POM_LICENSES.items():
            need({(x.get('name'), x.get('url')) for x in declarations[coordinate]['licenses']} == {pair}, 'NIO POM license differs')
    return components


def retained_runtime_equal(original, current):
    """Only own project version and reviewed publication variant may differ."""
    a, b = rows(original['components'], 'id'), rows(current['components'], 'id')
    need(set(a) == set(b), 'runtime selected identities changed')
    for name in a:
        ignored = {'version'} if a[name]['kind'] == 'project' else set()
        need({k: v for k, v in a[name].items() if k not in ignored} ==
             {k: v for k, v in b[name].items() if k not in ignored}, 'runtime component changed')
    def artifacts(c):
        return sorted(({k: v for k, v in x.items() if k != 'path'} for x in c['artifacts']), key=canonical)
    def poms(c):
        return sorted(({k: v for k, v in x.items() if k not in ('resolution', 'original_query_failures')}
                       for x in c['pom_files']), key=canonical)
    def edges(c):
        result = copy.deepcopy(c['edges'])
        for row in result:
            if b[row['to']]['kind'] == 'project':
                need(row['selectedVariant'] in ('runtimeElements', 'releaseRuntimeElements', 'publicReleaseRuntimeElements'), 'unreviewed project variant')
                row['selectedVariant'] = 'project-release-runtime'
        return sorted(result, key=canonical)
    need(artifacts(original) == artifacts(current) and poms(original) == poms(current), 'retained runtime archive/POM bytes changed')
    need(edges(original) == edges(current), 'retained runtime edges changed')
    need(sorted(original['licenses'], key=canonical) == sorted(current['licenses'], key=canonical), 'retained effective license declarations changed')


def write_new(root, name, data):
    path = root.joinpath(*relative(name).parts)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.exists():
        need(read(path, cap=MAX_FILE) == data, 'conflicting producer output: ' + name)
        return
    with path.open('xb') as stream:
        stream.write(data)
    path.chmod(0o600)


def scope_view(capture, scope):
    info = capture['configuration_scopes'][scope]
    components = rows(capture['components'], 'id')
    artifacts = {canonical({k: x[k] for k in ('component', 'name', 'sha256', 'variant')}): x
                 for x in capture['artifacts']}
    poms = rows(capture['pom_files'], 'coordinate')
    identities = set(info['component_ids'])
    return dict(schema_version=1, configuration=info['configuration'], additional_configurations=[],
        gradle_version=capture['gradle_version'], offline=capture['offline'], root=info['root'],
        components=[components[k] for k in sorted(identities)], edges=info['edges'],
        artifacts=[artifacts[canonical(k)] for k in info['artifact_keys']],
        pom_files=[poms[k] for k in info['pom_coordinates']],
        licenses=[x for x in capture['licenses'] if x['component'] in identities],
        pom_errors=capture['pom_errors'], source_sha256=capture['source_sha256'])


def validate_merged(capture, admission, *, is_portable=False):
    need(set(capture) == {'schema_version', 'configuration', 'additional_configurations', 'configuration_scopes',
                         'app_identity', 'gradle_version', 'offline', 'root', 'components', 'edges', 'artifacts',
                         'pom_files', 'licenses', 'pom_errors', 'source_sha256', 'limitations'}, 'capture v2 field set')
    need(capture.get('schema_version') == 2, 'actual capture v2 required')
    need(capture['app_identity'] == {'applicationId': 'com.foxhole.guard', 'versionName': admission['versionName'],
                                   'versionCode': admission['versionCode']}, 'actual application identity/version differs')
    need(capture.get('configuration') == SCOPES['runtime'] and
         capture.get('additional_configurations') == [SCOPES['desugaring']], 'closed primary/additional configuration set')
    scope_rows = capture.get('configuration_scopes')
    need(isinstance(scope_rows, dict) and set(scope_rows) == set(SCOPES), 'explicit configuration ownership missing')
    component_ids = set(rows(capture['components'], 'id'))
    artifact_keys = {canonical({k: x[k] for k in ('component', 'name', 'sha256', 'variant')}) for x in capture['artifacts']}
    need(len(artifact_keys) == len(capture['artifacts']), 'duplicate global artifact identity')
    edge_keys = {canonical(x) for x in capture['edges']}
    pom_ids = set(rows(capture['pom_files'], 'coordinate'))
    seen_components, seen_artifacts, seen_edges, seen_poms = set(), set(), set(), set()
    for scope in SCOPES:
        info = scope_rows[scope]
        need(set(info) == {'configuration', 'root', 'component_ids', 'edges', 'artifact_keys', 'pom_coordinates'}, 'scope field set')
        need(info['configuration'] == SCOPES[scope] and info['root'] == capture['root'], 'scope configuration/root differs')
        need(len(info['component_ids']) == len(set(info['component_ids'])), 'duplicate scoped component')
        need(len(info['pom_coordinates']) == len(set(info['pom_coordinates'])), 'duplicate scoped POM')
        for key in info['artifact_keys']:
            need(set(key) == {'component', 'name', 'sha256', 'variant'}, 'scope contains artifact paths/foreign keys')
        ids = set(info['component_ids']); ak = {canonical(x) for x in info['artifact_keys']}
        ek = {canonical(x) for x in info['edges']}; pk = set(info['pom_coordinates'])
        need(ids <= component_ids and ak <= artifact_keys and ek <= edge_keys and pk <= pom_ids, 'scope references unknown global input')
        seen_components |= ids; seen_artifacts |= ak; seen_edges |= ek; seen_poms |= pk
        validate_scope_view(scope, scope_view(capture, scope), admission, is_portable=is_portable)
    need((seen_components, seen_artifacts, seen_edges, seen_poms) ==
         (component_ids, artifact_keys, edge_keys, pom_ids), 'global input has no configuration owner')
    need(counts(capture) == admission['counts'], 'actual merged counts differ')


def make_sbom(capture, version):
    components = []
    for row in capture['components']:
        if row['id'] == capture['root']:
            continue
        item = dict(type='library', **{'bom-ref': row['id']}, name=row['name'], version=row['version'])
        if row['kind'] == 'maven':
            item['group'] = row['group']
            declaration = next(x for x in capture['licenses'] if x['component'] == row['id'])
            item['licenses'] = [{'license': {k: x[k] for k in ('name', 'url') if x.get(k)}} for x in declaration['licenses']]
        else:
            item['licenses'] = [{'license': {'id': 'GPL-3.0-or-later'}}]
        scopes = [name for name, info in capture['configuration_scopes'].items() if row['id'] in info['component_ids']]
        item['properties'] = [{'name': 'foxhole:inputScope', 'value': name} for name in scopes]
        item['properties'] += [{'name': 'foxhole:resolved-archive:' + a['name'] + ':sha256', 'value': a['sha256']}
                               for a in capture['artifacts'] if a['component'] == row['id']]
        components.append(item)
    adjacency = {x['id']: set() for x in capture['components']}
    for edge in capture['edges']:
        adjacency[edge['from']].add(edge['to'])
    return dict(bomFormat='CycloneDX', specVersion='1.6', version=1,
        metadata=dict(component={'type': 'application', 'bom-ref': capture['root'], 'name': 'FoxHole Guard', 'version': version},
            properties=[{'name': 'foxhole:scope', 'value': 'Resolved publicRelease runtime and explicitly owned core-library/L8 inputs; not post-R8/L8 linked delivery proof'}]),
        components=components, dependencies=[{'ref': k, 'dependsOn': sorted(v)} for k, v in sorted(adjacency.items())])



def load_bundle(repo, bundle, expected_manifest, *, expected_producer=None):
    """QA loader proposal; production integration must freeze the exact manifest pin."""
    need(not Path(bundle).is_symlink() and not Path(repo).is_symlink(), 'linked legal root')
    root = Path(bundle).resolve()
    manifest_raw = read(member(root, 'MANIFEST.json'), expected_manifest)
    manifest = json.loads(manifest_raw)
    need(manifest.get('schema') == 2 and manifest.get('scope') == 'actual-guard-runtime-and-desugaring-notices', 'manifest scope')
    inventory = manifest['files']
    need(0 < len(inventory) <= 512, 'inventory bound')
    checked = {}
    total = 0
    for row in inventory:
        need(set(row) == {'path', 'bytes', 'sha256'} and row['path'] not in checked, 'invalid/duplicate manifest row')
        need(type(row['bytes']) is int and 0 < row['bytes'] <= MAX_FILE, 'invalid manifest byte count')
        data = read(member(root, row['path']), row['sha256'], MAX_FILE)
        total += len(data)
        need(len(data) == row['bytes'] and total <= MAX_TOTAL, 'manifest byte budget')
        checked[row['path']] = data
    actual = set()
    for path in root.rglob('*'):
        need(not path.is_symlink(), 'linked bundle member')
        if path.is_file(): actual.add(path.relative_to(root).as_posix())
    need(actual == set(checked) | {'MANIFEST.json'}, 'foreign/missing bundle input')
    need(not any(b'/Users/' in x or b'/srv/foxhole/' in x for x in checked.values()), 'private build path in public bundle')
    producer = json.loads(checked['PRODUCER.json'])
    if expected_producer is not None:
        need(sha(checked['PRODUCER.json']) == pin(expected_producer), 'producer pin differs')
    need(producer['schema'] == 2 and producer['kind'] == 'fresh-guard-runtime-and-desugaring-producer', 'producer role')
    payload = rows(producer['payloadFiles'], 'path')
    need(set(payload) == set(checked) - {'PRODUCER.json'}, 'producer payload closure differs')
    for name, row in payload.items():
        need(row['bytes'] == len(checked[name]) and row['sha256'] == sha(checked[name]), 'producer payload bytes differ')
    need(set(producer['scopes']) == set(SCOPES) and producer['previousProducerRelabelled'] is False, 'producer scopes/history')
    capture_raw = checked['runtime-inventory.json']
    need(sha(capture_raw) == producer['capture']['portableSha256'], 'portable capture digest')
    pin(producer['capture']['actualSha256']); pin(producer['capture']['terminalSha256'])
    capture = json.loads(capture_raw)
    validate_merged(capture, producer, is_portable=True)
    report = json.loads(checked['maven-notice-report.json'])
    need(report['schema_version'] == 2 and report['missing'] == [] and set(report['scopes']) == set(SCOPES), 'legal coverage incomplete')
    need(report['capture_sha256'] == sha(capture_raw) and report['derived_from_actual_report_sha256'] ==
         producer['actualCollectorReportSha256'], 'raw/portable report provenance differs')
    reversed_report = copy.deepcopy(report)
    reversed_report.pop('derived_from_actual_report_sha256')
    reversed_report['capture_sha256'] = producer['capture']['actualSha256']
    need(sha(encoded(reversed_report)) == producer['actualCollectorReportSha256'], 'portable report is not the declared raw derivation')
    for scope in SCOPES:
        view = scope_view(capture, scope)
        need(sha(canonical(view)) == producer['scopes'][scope]['portableScopeViewSha256'], 'scope view differs')
        scope_report = report['scopes'][scope]
        need(scope_report['missing'] == [], 'scope legal coverage incomplete')
        need(len(scope_report['components']) == len(rows(scope_report['components'], 'component')), 'duplicate report owner')
        need({x['component'] for x in scope_report['components']} ==
             {x['id'] for x in view['components'] if x['kind'] == 'maven'}, 'report owner coverage differs')
        for component in scope_report['components']:
            declaration = next(x for x in view['licenses'] if x['component'] == component['component'])
            expected_archives = [{k: x[k] for k in ('name', 'bytes', 'sha256', 'variant')}
                                 for x in view['artifacts'] if x['component'] == component['component']]
            need(component['declaration'] == declaration and declaration['licenses'], 'report POM license owner differs')
            need(sorted(component['artifacts'], key=canonical) == sorted(expected_archives, key=canonical), 'report archive owner differs')
            need(not expected_archives or component['files'] or component['shared_licenses'], 'original legal text missing')
            archive_hashes = {x['sha256'] for x in expected_archives}
            for row in component['files']:
                need(row['archive_sha256'] in archive_hashes, 'original notice came from a different owner archive')
            for row in component['files'] + component['shared_licenses']:
                need(sha(checked['files/' + row['path']]) == row['sha256'], 'original legal bytes/owner differ')
        for pom in view['pom_files']:
            need(sha(checked[pom['path']]) == pom['sha256'], 'original POM differs')
    need(canonical(json.loads(checked['maven-runtime.cdx.json'])) == canonical(make_sbom(capture, producer['versionName'])), 'aggregate SBOM closure/scope differs')
    l8 = json.loads(checked['L8-INPUTS.json'])
    source = json.loads(checked['NIO-PUBLISHER-SOURCE.json'])
    artifacts = rows(scope_view(capture, 'desugaring')['artifacts'], 'component')
    need(l8['taskPath'] == ':app:l8DexDesugarLibPublicRelease' and l8['executed'] is False and
         l8['libraryArchiveSha256'] == source['artifactSha256'] == artifacts[NIO]['sha256'] and
         l8['configurationArchiveSha256'] == source['configurationArchiveSha256'] == artifacts[NIO_CONFIG]['sha256'] and
         l8['sourceInputsSha256'] == sha(canonical(producer['sourceInputs'])) and
         sha(checked['NIO-CONFIGURATION.json']) == l8['configurationMemberSha256'], 'L8/NIO/source closure differs')
    for name, digest in producer['sourceInputs'].items():
        need(sha(read(member(repo, name))) == pin(digest), 'current consumer source is stale: ' + name)
    # No unsigned source projection is invented; actual final180 stock-transform proof remains required.
    return checked, producer
