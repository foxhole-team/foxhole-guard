"""Synthetic metadata contracts; no native compiler, ELF execution or Android claim."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


COPY = module('canonical_page_evidence_fixture', ROOT / 'scripts/copy-derived-native-licenses.py')
AGG = module('aggregate_page_evidence_fixture', ROOT / 'scripts/aggregate-delivery-sbom.py')
CONFIG = json.loads((ROOT / 'config/native/page-aligned-native.json').read_text())
DATA = b'explicit synthetic metadata bytes; not an ELF'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def raw(component='graphics', base='/builder/one'):
    out, inputs, ndk = base + '/output', base + '/inputs', base + '/ndk'
    strip = ndk + '/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
    steps = []
    def step(argv, code=0):
        steps.append({'argv': argv, 'exitCode': code, 'logSha256': 'a' * 64})
    if component == 'graphics':
        for abi in COPY.ABIS:
            step(['cmake', '-S', out + '/original-graphics', '-B', out + '/build-' + abi,
                  '-DCMAKE_TOOLCHAIN_FILE=' + ndk + '/build/cmake/android.toolchain.cmake',
                  '-DCMAKE_CXX_FLAGS=-Os -g0 -ffile-prefix-map=' + out + '=/usr/src/foxhole-native',
                  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'])
            step(['cmake', '--build', out + '/build-' + abi, '--parallel', '4'])
            step([strip, '--strip-unneeded', '-o', out + '/jniLibs/' + abi + '/libandroidx.graphics.path.so',
                  out + '/build-' + abi + '/libandroidx.graphics.path.so'])
    else:
        step(['patch', '--fuzz=0', '-i', inputs + '/libsodium-CVE-2025-69277-upstream.patch'])
        for label, source in [('red', 'host-red-source'), ('green', 'prepared-sodium')]:
            step([out + '/' + source + '/configure', '--disable-shared', '--enable-static'])
            step(['make', '-j4'])
        step(['make', '-j1', 'check', 'TESTS=core_ed25519'], 2)
        step(['make', '-j4', 'check'])
        for abi in COPY.ABIS:
            step([out + '/prepared-sodium/configure', '--host=' + abi, '--enable-shared'])
            step(['make', '-j4'])
            step([strip, '--strip-unneeded', '-o', out + '/jniLibs/' + abi + '/libsodium.so',
                  out + '/sodium-' + abi + '/src/libsodium/.libs/libsodium.so'])
    result = {'schema': 1, 'exitCode': 0,
              'status': 'actual-graphics1.0.1-page-aligned-native-produced' if component == 'graphics' else
                        'actual-sodium1.0.20-patched-page-aligned-native-produced',
              'elapsedSeconds': 7.25, 'producerInputManifestSha256': 'b' * 64, 'sourceMutations': [],
              'ndk': {'version': '29.0.14206865', 'sourcePropertiesSha256': 'c' * 64,
                      'toolsSha256': {'llvm-strip': 'd' * 64}}, 'steps': steps,
              'libraries': [{'abi': 'armeabi-v7a', 'path': 'jniLibs/armeabi-v7a/libandroidx.graphics.path.so',
                             'bytes': len(DATA), 'sha256': digest(DATA), 'api': 26}],
              'scope': 'Synthetic producer metadata contract fixture.'}
    if component == 'graphics':
        result.update(graphicsReleaseCommit=CONFIG['graphics']['commit'],
                      graphicsOriginalSource={name: {'sha256': z['sha256']} for name,z in CONFIG['graphics']['sourceFiles'].items()},
                      originalMavenJavaUnchanged=True)
    else:
        result.update(originalSodiumSource={'synthetic.c': {'sha256': 'e' * 64}},
                      effectivePatchedSodiumSource={'synthetic.c': {'sha256': 'f' * 64}},
                      upstreamSecurityFixCommit=CONFIG['sodium']['upstreamSecurityFixCommit'],
                      upstreamRedRegressionExpectedExit=2, upstreamGreenSuiteExit=0,
                      upstreamGreenTestCounts={'TOTAL': 80, 'PASS': 80, 'SKIP': 0, 'XFAIL': 0,
                                               'FAIL': 0, 'XPASS': 0, 'ERROR': 0})
    return result


class ProjectionContracts(unittest.TestCase):
    def test_graphics_timing_and_log_variation_is_equal(self):
        a,b = raw(),raw(); b['elapsedSeconds'] = 802.125
        for step in b['steps']: step['logSha256'] = 'f' * 64
        self.assertEqual(COPY.encoded(COPY.canonical_evidence('graphics',a)), COPY.encoded(COPY.canonical_evidence('graphics',b)))

    def test_sodium_timing_and_log_variation_is_equal(self):
        a,b = raw('sodium'),raw('sodium'); b['elapsedSeconds'] = 91.0
        for step in b['steps']: step['logSha256'] = '0' * 64
        self.assertEqual(COPY.encoded(COPY.canonical_evidence('sodium',a)), COPY.encoded(COPY.canonical_evidence('sodium',b)))

    def test_builder_roots_are_role_normalized(self):
        for kind in ('graphics','sodium'):
            self.assertEqual(COPY.encoded(COPY.canonical_evidence(kind,raw(kind,'/one/private-builder'))),
                             COPY.encoded(COPY.canonical_evidence(kind,raw(kind,'/different/build-server'))))

    def test_compiler_flag_change_remains_visible(self):
        a,b=raw(),raw(); b['steps'][0]['argv'][-1] += ',-z,common-page-size=16384'
        self.assertNotEqual(COPY.encoded(COPY.canonical_evidence('graphics',a)), COPY.encoded(COPY.canonical_evidence('graphics',b)))

    def test_source_and_native_hash_changes_remain_visible(self):
        a,b=raw(),raw(); b['libraries'][0]['sha256']='e'*64
        self.assertNotEqual(COPY.encoded(COPY.canonical_evidence('graphics',a)), COPY.encoded(COPY.canonical_evidence('graphics',b)))

    def test_tool_hash_change_remains_visible(self):
        a,b=raw(),raw(); b['ndk']['toolsSha256']['llvm-strip']='e'*64
        self.assertNotEqual(COPY.encoded(COPY.canonical_evidence('graphics',a)), COPY.encoded(COPY.canonical_evidence('graphics',b)))

    def test_original_input_is_unchanged(self):
        value=raw(); before=copy.deepcopy(value); COPY.canonical_evidence('graphics',value)
        self.assertEqual(value,before)

    def test_unknown_original_field_is_rejected(self):
        value=raw();value['unknownCompilerField']='not silently discarded'
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_unknown_step_field_is_rejected(self):
        value=raw();value['steps'][0]['duration']=1
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_failed_command_is_rejected(self):
        value=raw();value['steps'][0]['exitCode']=1
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_sodium_red_and_green_results_are_preserved(self):
        value=COPY.canonical_evidence('sodium',raw('sodium'))
        self.assertEqual([s['exitCode']for s in value['steps']if s['argv']==['make','-j1','check','TESTS=core_ed25519']],[2])
        self.assertEqual([s['exitCode']for s in value['steps']if s['argv']==['make','-j4','check']],[0])
        self.assertEqual(value['upstreamGreenTestCounts']['PASS'],80)

    def test_unknown_absolute_command_path_is_rejected(self):
        value=raw();value['steps'][0]['argv'].append('-DEXTERNAL=/unexpected/private-root/input.c')
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_invalid_log_hash_is_not_silently_discarded(self):
        value=raw();value['steps'][0]['logSha256']='bad'
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_invalid_duration_is_not_silently_discarded(self):
        value=raw();value['elapsedSeconds']=float('nan')
        with self.assertRaises(ValueError): COPY.canonical_evidence('graphics',value)

    def test_unknown_projected_path_role_is_rejected(self):
        value=COPY.canonical_evidence('graphics',raw());value['steps'][0]['argv'].append('$UNKNOWN_ROOT/source')
        with self.assertRaises(ValueError): COPY.validate_projection('graphics',value)

    def test_projected_operational_field_is_rejected(self):
        value=COPY.canonical_evidence('graphics',raw());value['elapsedSeconds']=0
        with self.assertRaises(ValueError): COPY.validate_projection('graphics',value)

    def test_new_role_is_explicit_and_raw_is_not_accepted(self):
        value=COPY.canonical_evidence('graphics',raw())
        self.assertEqual((value['schema'],value['kind'],value['projectionVersion']),(2,'canonical-page-native-producer-evidence',1))
        self.assertFalse(value['rawProducerReceiptPackaged'])
        with self.assertRaises(ValueError): COPY.validate_projection('graphics',raw())

    def test_consumer_keeps_hash_checks_and_new_evidence_role(self):
        component='graphics'; original=raw(); inputs={'files':{name:pin for name,pin in (
            ('graphics-path-1.0.1.aar',CONFIG[component]['originalAar']['sha256']),)}}
        input_bytes=json.dumps(inputs,sort_keys=True).encode(); original['producerInputManifestSha256']=digest(input_bytes)
        evidence=COPY.encoded(COPY.canonical_evidence(component,original))
        files={'derived-native/graphics/producer-evidence.json':evidence,
               'derived-native/graphics/producer-inputs.json':input_bytes}
        with patch.object(AGG,'verified_notice',side_effect=lambda archive,notices,name:files[name]):
            result=AGG.derived_native_component('libandroidx.graphics.path.so', 'lib/armeabi-v7a/libandroidx.graphics.path.so', DATA, None, None, ROOT)
        props={r['name']:r['value']for r in result['properties']}
        self.assertEqual(props['foxhole:producerEvidenceSha256'],digest(evidence))
        self.assertNotIn('foxhole:producerReceiptSha256',props)
        with patch.object(AGG,'verified_notice',side_effect=lambda archive,notices,name:files[name]):
            with self.assertRaises(ValueError): AGG.derived_native_component('libandroidx.graphics.path.so','lib/armeabi-v7a/libandroidx.graphics.path.so',DATA+b'changed',None,None,ROOT)

    def test_existing_raw_verify_runs_before_packaged_output(self):
        with tempfile.TemporaryDirectory()as temporary:
            root=Path(temporary);repo=root/'repo';(repo/'scripts').mkdir(parents=True)
            (repo/'scripts/verify-page-aligned-native.py').write_text("def verify(*args):\n raise ValueError('synthetic full raw verifier refusal')\n")
            output=root/'output';output.mkdir()
            with patch.object(sys,'argv',['copy-derived-native-licenses.py','--repo',str(repo),'--native',str(root/'native'),'--output',str(output),'--abis','armeabi-v7a']):
                with self.assertRaisesRegex(ValueError,'full raw verifier refusal'): COPY.main()
            self.assertFalse((output/'derived-native').exists())


if __name__=='__main__': unittest.main()
