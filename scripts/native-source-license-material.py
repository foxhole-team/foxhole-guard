"""Strict current Go8/CPP4 original material loader; no network, Git or build fallback."""
from pathlib import Path, PurePosixPath
from types import MappingProxyType
import hashlib
import json
import os
import re
import stat
import unicodedata
import math

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
PRODUCERS = {'go': '84bc4ecde8f838a4aa875b2c3bd8a8a53534b802ee0038c7e37e9711686d002a',
             'cpp': '7fea9f97a9bc7eb1419c241257f49b27a7447b45c04bcc9af11d8c337806c2fc'}
CONSUMERS = {'coreC3', 'sentinel', 'maven420'}
NON_MODULE_FILES = {'tor/Go-1.26.10-BSD-3-Clause.txt', 'tor/Go-1.26.10-PATENTS.txt', 'tor/lyrebird-BSD-3-Clause.txt',
    'tor/lyrebird-GPL-3.0-or-later.txt', 'tor/conjure-client-BSD-3-Clause.txt', 'tor/CONJURE-PATCH.json',
    'tor/CONJURE-PATCH.py', 'tor/GO-MODULES.json', 'i2pd/i2pd-BSD-3-Clause.txt', 'i2pd/VERSION.txt',
    'i2pd/OpenSSL-3.5.9-Apache-2.0.txt', 'i2pd/Boost-1.84.0-BSL-1.0.txt',
    'i2pd/Android-NDK-29-NOTICE.txt', 'i2pd/Android-NDK-29-NOTICE.toolchain.txt'}
HASH = re.compile('[a-f0-9]{64}')
FIELDS = ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
SOURCE_KEYS = {
    'scripts/build-tor-transports.sh', 'scripts/native-deps.sh', 'scripts/prepare-tor-dependency.py',
    'scripts/verify-native-pages.py', 'scripts/verify-native-host-paths.sh',
    'config/native/conjure/go.mod', 'config/native/conjure/go.sum', 'config/native/conjure/stun-v3-patch.json',
    'config/native/lyrebird/go.mod', 'config/native/lyrebird/go.sum', 'scripts/build-i2pd.sh',
    'scripts/i2pd-build-cache.py', 'scripts/i2pd-source-inputs.py', 'third_party/i2pd.version',
    'scripts/patches/i2pd-0001-standalone-android-daemon.patch',
    'scripts/patches/i2pd-0002-bound-sam-datagram-size.patch',
    'scripts/patches/i2pd-0003-preserve-fragmented-sam-datagrams.patch'}


def need(value, message):
    if not value: raise ValueError(message)


def digest(raw): return hashlib.sha256(raw).hexdigest()
def encoded(value): return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()
def facts(value): return tuple(getattr(value, key) for key in FIELDS)


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'Duplicate native material key')
        result[key] = value
    return result


def nonfinite(value): raise ValueError('Non-finite native material number')
def finite_float(value):
    result = float(value)
    if not math.isfinite(result): nonfinite(value)
    return result
def document(raw): return json.loads(raw, object_pairs_hook=unique, parse_constant=nonfinite, parse_float=finite_float)


def relative(name):
    need(isinstance(name, str) and '\\' not in name and not any(unicodedata.category(char).startswith('C') for char in name),
         'Invalid native material path/control character')
    path = PurePosixPath(name)
    need(name == str(path) and not path.is_absolute() and path.parts
         and all(part.casefold() not in ('.', '..', '.git', 'agents.md') for part in path.parts), 'Unsafe native material path')
    return path


def read(path, maximum=1048576, expected=None, native=False):
    path = Path(path).absolute()
    need(not any(value.is_symlink() for value in (path, *path.parents)), 'Native material symlink')
    info, parent = path.lstat(), path.parent.lstat()
    need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1
         and not (info.st_mode | parent.st_mode) & 0o022 and 0 < info.st_size <= maximum,
         'Native material protected positive file admission failed')
    parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        need(facts(os.fstat(parent_fd)) == facts(parent), 'Native material parent changed before open')
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
        try:
            need(facts(os.fstat(fd)) == facts(info), 'Native material changed before read')
            with os.fdopen(os.dup(fd), 'rb') as stream: raw = stream.read(maximum + 1)
            need(len(raw) == info.st_size and facts(os.fstat(fd)) == facts(path.lstat()) == facts(info)
                 and facts(os.fstat(parent_fd)) == facts(path.parent.lstat()) == facts(parent), 'Native material changed during read')
        finally: os.close(fd)
    finally: os.close(parent_fd)
    need(expected is None or digest(raw) == expected, 'Native material digest differs')
    return raw


def text(raw):
    raw.decode('utf-8')
    need(not raw.startswith((b'\x7fELF', b'PK\x03\x04', b'dex\n', b'\0asm'))
         and not any(value in raw for value in (b'/srv/foxhole/', b'/Users/', b'-----BEGIN PRIVATE KEY-----')),
         'Operational/private/binary native legal payload')


def asset_path(repo, abi, name):
    if name == 'libi2pd.so': return repo / 'app/src/main/jniLibs' / abi / name
    return repo / 'app/src/main/assets/tor' / abi / 'tor/pluggable_transports' / name


def module_directory(path, version):
    value = path + '@' + version
    readable = re.sub('[^A-Za-z0-9._+-]+', '_', value).strip('_')
    return readable[:100] + '-' + digest(value.encode())[:12]


def remember(before, path, body):
    path = Path(path).absolute()
    before[str(path)] = (digest(body), facts(path.lstat()), facts(path.parent.lstat()))


def load(manifest_path, payload_root, app_root, android_abis):
    repo, payload = Path(app_root).absolute(), Path(payload_root).absolute()
    abis = tuple(android_abis)
    need(abis and len(set(abis)) == len(abis) and all(abi in ABIS for abi in abis), 'Invalid requested native material ABI scope')
    binding_path = repo / 'config/native-source-material-bindings.json'
    binding_raw = read(binding_path)
    bindings = document(binding_raw)
    need(set(bindings) == {'schema', 'kind', 'coverage', 'manifestSHA256', 'sourceInputs', 'moduleInventorySHA256', 'nativeLibraries', 'consumerBindings'}
         and bindings['schema'] == 1 and bindings['kind'] == 'qualified-go8-cpp4-consumer-material-bindings'
         and bindings['coverage'] == 'complete', 'Current qualified native material bindings required')
    consumers = bindings['consumerBindings']
    need(isinstance(consumers, dict) and set(consumers) == CONSUMERS
         and all(isinstance(value, str) and HASH.fullmatch(value) for value in consumers.values()),
         'CoreC3/Sentinel/Maven420 actual consumer bindings missing')
    need(isinstance(bindings['manifestSHA256'], str) and HASH.fullmatch(bindings['manifestSHA256'])
         and isinstance(bindings['moduleInventorySHA256'], str) and HASH.fullmatch(bindings['moduleInventorySHA256']),
         'Current native material manifest/inventory pin missing')
    source = bindings['sourceInputs']
    need(isinstance(source, dict) and set(source) == SOURCE_KEYS, 'Native producer source-input closure differs')
    before = {}
    remember(before, binding_path, binding_raw)
    for name, pin in source.items():
        need(isinstance(pin, str) and HASH.fullmatch(pin), 'Native source-input digest missing')
        body = read(repo / name, expected=pin)
        remember(before, repo / name, body)
    raw = read(manifest_path, expected=bindings['manifestSHA256'])
    manifest = document(raw)
    text(encoded(manifest))
    need(set(manifest) == {'schema', 'kind', 'coverage', 'gaps', 'abis', 'producerReceipts', 'nativeLibraries', 'modules', 'origins', 'files', 'consumerBindings'}
         and manifest['schema'] == 1 and manifest['kind'] == 'guard-go8-cpp4-source-license-material'
         and manifest['coverage'] == 'complete' and manifest['gaps'] == [] and manifest['abis'] == list(ABIS)
         and manifest['producerReceipts'] == PRODUCERS and manifest['consumerBindings'] == consumers,
         'Native material source/producer/coverage identity differs')
    need(manifest['nativeLibraries'] == bindings['nativeLibraries'], 'Native material native identity differs')
    natives = manifest['nativeLibraries']
    expected_natives = {(abi, name) for abi in ABIS for name in ('lyrebird', 'conjure-client', 'libi2pd.so')}
    need(isinstance(natives, list) and len(natives) == len(expected_natives)
         and {(row['abi'], row['name']) for row in natives} == expected_natives, 'Native material product coverage differs')
    for row in natives:
        need(set(row) == {'abi', 'name', 'bytes', 'sha256'} and type(row['bytes']) is int and 0 < row['bytes'] <= 128 * 1048576
             and isinstance(row['sha256'], str) and HASH.fullmatch(row['sha256']), 'Invalid native product row')
        if row['abi'] not in abis: continue
        file = asset_path(repo, row['abi'], row['name'])
        body = read(file, 128 * 1048576, row['sha256'], native=True)
        machine = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}[row['abi']]
        need(len(body) == row['bytes'] and len(body) >= 64 and body[:4] == b'\x7fELF'
             and (body[4], int.from_bytes(body[18:20], 'little')) == machine and body[5] == 1, 'Current native asset ABI/size differs')
        remember(before, file, body)
    modules = manifest['modules']
    need(isinstance(modules, list) and len(modules) == 76 and digest(encoded(modules)) == bindings['moduleInventorySHA256'],
         'Exact actual embedded module inventory differs')
    module_map = {}
    for row in modules:
        need(set(row) == {'declaredPath', 'declaredVersion', 'path', 'version', 'sum', 'owners', 'replacementPath'}
             and all(isinstance(row[key], str) and row[key] for key in ('declaredPath', 'declaredVersion', 'path', 'version'))
             and row['owners'] and len(row['owners']) == len(set(row['owners']))
             and set(row['owners']) <= {abi + '/' + name for abi, name in expected_natives if name != 'libi2pd.so'},
             'Invalid embedded module identity/owners')
        key = row['path'] + '@' + row['version']
        need(key not in module_map, 'Duplicate embedded module identity')
        if row['replacementPath'] is None:
            need(isinstance(row['sum'], str) and re.fullmatch(r'h1:[A-Za-z0-9+/]{43}=', row['sum']), 'Registry module H1 missing')
        else:
            need((row['path'], row['version'], row['replacementPath'], row['sum']) ==
                 ('github.com/refraction-networking/conjure', 'v0.9.1', '../conjure-patched', None), 'Unknown or relabelled local replacement')
        module_map[key] = row
    need(sum(row['replacementPath'] is None for row in modules) == 75, 'Registry/local module scope differs')
    origins = manifest['origins']
    need(isinstance(origins, dict) and set(origins) == {'goStd', 'localConjure', 'lyrebirdComposite', 'cppAndNDK'}, 'Native material origins missing')
    for role, origin in origins.items():
        need(isinstance(origin, dict) and origin.get('coverage') == 'complete'
             and isinstance(origin.get('authoritySHA256'), str) and HASH.fullmatch(origin['authoritySHA256']), 'Unqualified native original origin: ' + role)
    local = origins['localConjure']
    need(all(isinstance(local.get(key), str) and HASH.fullmatch(local[key]) for key in
             ('originalTreeSHA256', 'effectiveTreeSHA256', 'originalLicenseSHA256', 'patchManifestSHA256', 'patchScriptSHA256'))
         and local['patchManifestSHA256'] == source['config/native/conjure/stun-v3-patch.json']
         and local['patchScriptSHA256'] == source['scripts/prepare-tor-dependency.py'], 'Local Conjure source/license/patch closure missing')
    composite = origins['lyrebirdComposite']
    need(composite.get('sourceCommit') == '0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1'
         and isinstance(composite.get('packageGraphSHA256ByABI'), dict) and set(composite['packageGraphSHA256ByABI']) == set(ABIS)
         and all(isinstance(value, str) and HASH.fullmatch(value) for value in composite['packageGraphSHA256ByABI'].values()),
         'Actual Lyrebird GPL composite package proof missing')
    cpp = origins['cppAndNDK']
    need(cpp.get('sourceCommit') == '635b013a612ff47278ef02acf8580a28e10e26c5'
         and cpp.get('OpenSSLArchiveSHA256') == '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'
         and cpp.get('BoostArchiveSHA256') == 'cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454'
         and cpp.get('NDKVersion') == '29.0.14206865', 'Current CPP/NDK original source identity differs')
    files, covered, local_license_bound = {}, set(), False
    rows = manifest['files']
    need(isinstance(rows, list) and 0 < len(rows) <= 4096, 'Native notice inventory missing')
    for row in rows:
        need(set(row) == {'path', 'bytes', 'sha256', 'module', 'sourceH1', 'kind'} and row['kind'] in ('original', 'generated-descriptor'), 'Invalid native notice declaration')
        name = row['path']; relative(name)
        need(name.startswith(('tor/', 'i2pd/')) and name not in files and name.casefold() not in {key.casefold() for key in files}, 'Native notice path/case collision')
        need(type(row['bytes']) is int and 0 < row['bytes'] <= 1048576 and isinstance(row['sha256'], str) and HASH.fullmatch(row['sha256']), 'Invalid native notice digest/size')
        body = read(payload / name, expected=row['sha256']); need(len(body) == row['bytes'], 'Native notice size differs'); text(body)
        if row['module'] is not None:
            need(row['module'] in module_map and row['sourceH1'] == module_map[row['module']]['sum'], 'Notice module H1/ownership differs')
            module = module_map[row['module']]
            prefix = 'tor/go-modules/' + module_directory(module['path'], module['version']) + '/'
            need(name.startswith(prefix) and not any(part.startswith('.') for part in Path(name[len(prefix):]).parts),
                 'Notice canonical module directory differs')
            if row['kind'] == 'original':
                need(re.fullmatch(r'(?i)(license|copying|notice)(?:[._-].*)?', Path(name).name) is not None,
                     'Module original is not a license/notice')
                covered.add(row['module'])
                if module['replacementPath'] is not None and digest(body) == local['originalLicenseSHA256']:
                    local_license_bound = True
            else:
                need(name == prefix + 'MODULE.txt' and body == (module['path'] + '\n' + module['version'] + '\n').encode(),
                     'Generated module descriptor differs')
        else:
            need(row['sourceH1'] is None, 'Non-module original has invented H1')
            need(name in NON_MODULE_FILES, 'Unapproved non-module native material path')
        files[name] = body; remember(before, payload / name, body)
    need(covered == set(module_map), 'Embedded module original license coverage incomplete')
    need(local_license_bound, 'Actual local Conjure original license not joined')
    for module in modules:
        descriptor = 'tor/go-modules/' + module_directory(module['path'], module['version']) + '/MODULE.txt'
        need(files.get(descriptor) == (module['path'] + '\n' + module['version'] + '\n').encode(), 'Module descriptor omitted/different')
    need(NON_MODULE_FILES <= set(files), 'Required Go/CPP original material omitted')
    need(digest(files['tor/CONJURE-PATCH.json']) == source['config/native/conjure/stun-v3-patch.json']
         and digest(files['tor/CONJURE-PATCH.py']) == source['scripts/prepare-tor-dependency.py'], 'Copied local patch material differs')
    need(files['i2pd/VERSION.txt'] == read(repo / 'third_party/i2pd.version'), 'Copied i2pd source version differs')
    need(document(files['tor/GO-MODULES.json']) == modules, 'Copied actual declared/effective Go module inventory differs')
    std = origins['goStd']
    need(std.get('sourceCommit') == '4e8c85f5319d999bfd7617fdc191631e5634e3c5'
         and std.get('sourceArchiveSHA256') == '1e2d5e06a2f103d11f9ae23a39cf3f79d4c7ed6f1335b7715ac2e65894fe1405'
         and std['authoritySHA256'] == '5f41fd9468bc246e78bd56952951dcb5f846a1b07a7b5399a00dd1bf82846704'
         and digest(files['tor/Go-1.26.10-BSD-3-Clause.txt']) == '911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad'
         and digest(files['tor/Go-1.26.10-PATENTS.txt']) == '96f408bfae65bf137fc2525d3ecb030271c50c1e90799f87abf8846d8dd505cc',
         'Actual source-built Go standard-library notice origin differs')
    actual = {str(file.relative_to(payload)) for file in payload.rglob('*') if file.is_file() or file.is_symlink()}
    need(actual == set(files), 'Extra native original payload member')
    remember(before, manifest_path, raw)
    return MappingProxyType({'files': MappingProxyType(files), 'before': MappingProxyType(before), 'abis': abis,
                             'payloadRoot': payload, 'payloadFiles': tuple(sorted(files)),
                             'manifestSHA256': digest(raw), 'completeConsumerLegalQualification': False})


def recheck(material):
    payload = material['payloadRoot']
    need({str(file.relative_to(payload)) for file in payload.rglob('*') if file.is_file() or file.is_symlink()}
         == set(material['payloadFiles']), 'Native payload inventory changed during generation')
    for name, (pin, before, parent) in material['before'].items():
        file = Path(name)
        need(facts(file.lstat()) == before and facts(file.parent.lstat()) == parent, 'Native material F9 changed during generation')
        read(file, 128 * 1048576 if file.name in ('lyrebird', 'conjure-client', 'libi2pd.so') else 1048576,
             pin, native=file.name in ('lyrebird', 'conjure-client', 'libi2pd.so'))
