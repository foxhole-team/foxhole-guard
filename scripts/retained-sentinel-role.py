"""One explicit retained EA Sentinel role for the qualified be0 android64 consumer."""
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

EA = 'ea4f180fc2bf5a5dd108bd304bb86e97fa14a625'
CORE = 'be0ff0ed269341b4c4ee6b1f6d9976344ecbd3f1'
ABIS = ['arm64-v8a', 'x86_64']
TARGETS = ['aarch64-linux-android', 'x86_64-linux-android']
FOUR = {'aarch64-linux-android':'arm64-v8a', 'armv7-linux-androideabi':'armeabi-v7a',
        'i686-linux-android':'x86', 'x86_64-linux-android':'x86_64'}
FIXED = {'scripts/sentinel-android-build.sh', 'rust-toolchain.toml', '.cargo/config.toml'}
PROOFS = {'sentinel_producer','sentinel_stage','sentinel_source_manifest','sentinel_capture',
          'sentinel_legal_report','sentinel_catalog','sentinel_selected_receipt','sentinel_selected_sbom'}
HASH = re.compile('[a-f0-9]{64}')


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'))+'\n').encode()


def pairs(values):
    result = {}
    for key, value in values:
        require(key not in result, 'Duplicate retained-role JSON member')
        result[key] = value
    return result


def read_json(raw):
    require(len(raw) <= 4*1024**2, 'Retained authority JSON exceeds bound')
    return json.loads(raw, object_pairs_hook=pairs)


def read_file(path, wanted=None):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 4*1024**2,
            'Retained authority must be a bounded regular file')
    raw = path.read_bytes()
    require(wanted is None or sha(raw) == wanted, 'Retained input differs from independent pin')
    return raw


def authority(root):
    raw = read_file(Path(root)/'config/sentinel-retained-authority.json')
    value = read_json(raw)
    require(value.get('schema') == 1 and value.get('kind') == 'pinned-retained-original-sentinel-authority' and
            value.get('status') == 'BOUND_ACTUAL_ROOT_ROLE' and isinstance(value.get('roleSha256'),str) and
            HASH.fullmatch(value['roleSha256']), 'Actual Root-emitted retained role is required; draft is not deployable')
    require(value.get('originalCoreCommit') == EA and value.get('consumerCoreCommit') == CORE and
            value.get('androidDeliveryProfile') == 'android64' and value.get('abis') == ABIS and
            value.get('targets') == TARGETS and set(value.get('originalProofs',{})) == PROOFS,
            'Unknown retained original/current/profile authority')
    require(all(isinstance(v,str) and HASH.fullmatch(v) for v in value['originalProofs'].values()),
            'Original eight authorities must all be pinned')
    return value, raw


def validate_role(raw, config):
    require(sha(raw) == config['roleSha256'], 'Retained role differs from independent app source authority')
    role = read_json(raw)
    require(role.get('schema') == 1 and role.get('kind') == 'retained-original-sentinel-current-source-input-equivalence' and
            role.get('originalCoreCommit') == EA and role.get('consumerCoreCommit') == CORE and
            role.get('androidDeliveryProfile') == 'android64' and role.get('abis') == ABIS and
            role.get('newNativeBuildClaimed') is False and role.get('oldJvmResultClaimed') is False,
            'Retained role changes source identity or makes a new build claim')
    for key in ('originalProofs','currentProducerReceiptSha256','currentSourceManifestSha256',
                'currentStageReceiptSha256','currentSourceArchiveSha256','originalLibraries'):
        require(role.get(key) == config[key], 'Retained role changes pinned authority: '+key)
    files = role.get('unchangedInputFiles')
    require(isinstance(files,dict) and len(files) == config['unchangedInputFileCount'] and
            sha(encoded(files)) == config['unchangedInputFilesSha256'], 'Complete independent Sentinel selector differs')
    for path, digest in files.items():
        p = PurePosixPath(path)
        require(not p.is_absolute() and not any(x in ('..','.git','AGENTS.md') for x in p.parts) and
                (path.startswith('native/sentinel-yara/') or path in FIXED) and
                isinstance(digest,str) and HASH.fullmatch(digest), 'Unsafe or unrelated retained input')
    require(FIXED <= set(files), 'Actual Sentinel producer entry/toolchain/config are required')
    require(role.get('selectedLibraryPaths') == [abi+'/libfoxhole_sentinel_yara.so' for abi in ABIS],
            'Retained role must select exactly both android64 Sentinel slices')
    return role


def selector(files):
    require(isinstance(files,dict) and FIXED <= set(files), 'Actual independent Sentinel entry/config files are missing')
    return {name:files[name] for name in sorted(FIXED | {n for n in files if n.startswith('native/sentinel-yara/')})}


def consumer_binding(role, config, config_raw):
    return encoded({'schema':1,'kind':'retained-sentinel-consumer-binding','roleSha256':config['roleSha256'],
        'authoritySha256':sha(config_raw),'originalCoreCommit':EA,'consumerCoreCommit':CORE,
        'androidDeliveryProfile':'android64','abis':ABIS,'targets':TARGETS,
        'currentProducerReceiptSha256':role['currentProducerReceiptSha256'],
        'currentSourceManifestSha256':role['currentSourceManifestSha256'],
        'currentStageReceiptSha256':role['currentStageReceiptSha256'],
        'currentSourceArchiveSha256':role['currentSourceArchiveSha256'],
        'selectedLibraryPaths':role['selectedLibraryPaths'],'newNativeBuildClaimed':False})


def validate_projection(role, old_files, current_files, git_blob, git_names):
    selected = role['unchangedInputFiles']
    require(selector(old_files) == selector(current_files) == selected,
            'Original/current full Sentinel input sets or bytes changed')
    names = set(git_names()) | FIXED
    require(names == set(selected) and all(sha(git_blob(name)) == digest for name,digest in selected.items()),
            'Retained input projection differs from immutable current Core Git blobs')


def generation(root, args, core_revision, loader, git_blob, git_names):
    require(core_revision == CORE and args.android_abis.split() == ABIS,
            'Retained Sentinel requires exact be0/android64 app selection')
    config, config_raw = authority(root)
    role_raw = read_file(args.sentinel_retained_role, config['roleSha256'])
    role = validate_role(role_raw, config)
    source = read_json(read_file(args.sentinel_current_source_manifest, config['currentSourceManifestSha256']))
    producer = read_json(read_file(args.sentinel_current_producer, config['currentProducerReceiptSha256']))
    stage = read_json(read_file(args.sentinel_current_stage, config['currentStageReceiptSha256']))
    require(all(source.get(k) == v for k,v in {'androidDeliveryProfile':'android64','abis':ABIS,'targets':TARGETS}.items()) and
            source.get('archiveSha256') == config['currentSourceArchiveSha256'] and
            isinstance(source.get('files'),dict) and len(source['files']) == config['currentSourceFiles'],
            'Full genuine current producer source map is required; compiled subset is insufficient')
    require(producer.get('coreCommit') == CORE and producer.get('consumer') == 'guard' and producer.get('features') == 'shipped' and
            producer.get('androidDeliveryProfile') == 'android64' and producer.get('abis') == ABIS and producer.get('targets') == TARGETS and
            type(producer.get('exitCode')) is int and producer.get('exitCode') == 0 and producer.get('sourceMutations') == producer.get('consumerInputMutations') == [] and
            producer.get('sourceManifestSha256') == config['currentSourceManifestSha256'] and
            producer.get('sourceArchiveSha256') == config['currentSourceArchiveSha256'] and
            producer.get('libraries') == config['currentLibraries'] and producer.get('staticLoadAndRelroGate') == 'pass',
            'Retained role is not bound to the actual new Guard producer')
    require(stage.get('image') == 'sha256:42937d2c1a0128612cb31a1119c24af835f36ddf2f49073fcca4beb948cb775b' and
            all(type(stage.get(k)) is int and stage[k] == 0 for k in
                ('exitCode','cleanupExitCode','postflightExitCode','cacheFenceExitCode')),
            'Current Guard producer stage/fences are not successful')
    receipt_raw = read_file(args.sentinel_sbom_receipt, config['originalProofs']['sentinel_selected_receipt'])
    read_file(args.sentinel_sbom, config['originalProofs']['sentinel_selected_sbom'])
    receipt = loader.read_json(receipt_raw); inputs = receipt['inputs']; base = Path(args.sentinel_sbom_receipt).parent.resolve(strict=True)
    old = {}
    for name, proof in (('producerReceipt','sentinel_producer'),('sourceManifest','sentinel_source_manifest'),('captureReceipt','sentinel_capture')):
        require(inputs[name]['sha256'] == config['originalProofs'][proof], 'Original selected Sentinel input authority differs')
        path = loader.checked_input(base, inputs[name], 4*1024**2)
        old[name] = loader.read_json(path.read_bytes())
    require(old['producerReceipt']['libraries'] == role['originalLibraries'], 'Original four Sentinel ELF identities differ')
    validate_projection(role, old['sourceManifest']['files'], source['files'], git_blob, git_names)
    return {'revision':EA,'role':role_raw,'binding':consumer_binding(role,config,config_raw),'config':config}


def packaged(root, revision, profile, read_notice, read_library, core_coverage):
    require(revision == CORE and profile == 'android64', 'Unknown retained Sentinel consumer')
    config, config_raw = authority(root)
    role_raw = read_notice('sentinel/RETAINED-ROLE.json'); role = validate_role(role_raw,config)
    require(read_notice('sentinel/RETAINED-CONSUMER.json') == consumer_binding(role,config,config_raw),
            'Packaged retained consumer binding differs')
    current = core_coverage.get('authorities',{})
    for name,key in (('producerReceipt','currentProducerReceiptSha256'),('sourceManifest','currentSourceManifestSha256'),('sourceArchive','currentSourceArchiveSha256')):
        require(current.get(name) == role[key], 'Retained role differs from current Core selected producer/source')
    require(len(config['currentLibraries']) == 2 and {r['path'] for r in config['currentLibraries']} ==
            {abi+'/libfoxhole_native.so' for abi in ABIS}, 'Exactly both current Guard Core ELF authorities are required')
    for row in config['currentLibraries']:
        raw = read_library('lib/'+row['path'])
        require(len(raw) == row['bytes'] and sha(raw) == row['sha256'],
                'Retained consumer is not packaged with the actual new Guard Core JNI')
    coverage_raw = read_notice('sentinel/ABI-COVERAGE.json')
    require(sha(coverage_raw) == config['originalCoverageSha256'], 'Original four-target Sentinel coverage changed')
    coverage = read_json(coverage_raw)
    require(coverage.get('coreCommit') == EA and set(coverage.get('targets',{})) == set(FOUR),
            'Original four-target Sentinel evidence is required')
    for name,proof in (('producerReceipt','sentinel_producer'),('sourceManifest','sentinel_source_manifest'),('captureReceipt','sentinel_capture')):
        require(coverage['authorities'].get(name) == role['originalProofs'][proof], 'Original Sentinel graph authority differs')
    notices_raw = read_notice('sentinel/cargo-notices/MANIFEST.json')
    require(sha(notices_raw) == config['originalNoticeUnionSha256'] == coverage.get('noticeManifestSha256'),
            'Original Sentinel notice union changed')
    catalog = read_json(notices_raw)
    for row in catalog['files']:
        name = row['path']; require(not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts,
                                   'Unsafe retained notice path')
        require(sha(read_notice('sentinel/cargo-notices/'+name)) == row['sha256'], 'Original Sentinel notice bytes changed')
    nodes, edges, root_node, metadata = {}, {}, None, None
    for target,abi in FOUR.items():
        data = read_notice('sentinel/selected-'+target+'.cdx.json')
        require(sha(data) == coverage['targets'][target]['deliverySbomSha256'], 'Original selected Sentinel target changed')
        if target not in TARGETS:
            continue
        doc = read_json(data); node = doc['metadata']['component']
        require(root_node is None or root_node == node, 'Retained selected Sentinel roots differ')
        root_node,metadata = node,doc['metadata']
        for item in [node]+doc['components']:
            ref=item['bom-ref']; require(ref not in nodes or nodes[ref] == item, 'Retained target component conflict');nodes[ref]=item
        for row in doc['dependencies']:edges.setdefault(row['ref'],set()).update(row['dependsOn'])
        library = read_library('lib/'+abi+'/libfoxhole_sentinel_yara.so')
        expected = [row for row in role['originalLibraries'] if row['path']==abi+'/libfoxhole_sentinel_yara.so']
        require(len(expected)==1 and len(library)==expected[0]['bytes'] and
                sha(library)==expected[0]['sha256']==coverage['targets'][target]['nativeLibrarySha256'],
                'APK retained Sentinel slice differs from original producer')
    require(root_node is not None and set(edges)==set(nodes) and all(set(v)<=set(nodes) for v in edges.values()),
            'Retained selected Sentinel graph is incomplete')
    result={'bomFormat':'CycloneDX','specVersion':'1.5','version':1,'metadata':copy.deepcopy(metadata),
            'components':[nodes[n] for n in sorted(nodes) if n!=root_node['bom-ref']],
            'dependencies':[{'ref':n,'dependsOn':sorted(v)}for n,v in sorted(edges.items())]}
    return result, config['roleSha256']
