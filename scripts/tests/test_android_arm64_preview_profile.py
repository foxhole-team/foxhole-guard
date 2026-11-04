import copy
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[2]

def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / relative)
    result = importlib.util.module_from_spec(spec); sys.modules[name] = result
    spec.loader.exec_module(result)
    return result

DELIVERY = module('arm64_preview_delivery', 'android-delivery-profile.py')
CORE = module('arm64_preview_core', 'selected-license-sbom.py')
SENTINEL = module('arm64_preview_sentinel', 'selected-sentinel-sbom.py')
SNAPSHOT = module('arm64_preview_snapshot', 'source-legal-snapshot.py')
RETAINED = module('arm64_preview_retained', 'retained-sentinel-arm64.py')
PROFILE = {'androidDeliveryProfile':'android-arm64','abis':['arm64-v8a'],'targets':['aarch64-linux-android']}

class Arm64PreviewProfileTest(unittest.TestCase):
    def test_all_shape_authorities_select_exactly_one_target(self):
        self.assertEqual(PROFILE, DELIVERY.profile_shape('android-arm64'))
        self.assertEqual({'aarch64-linux-android':'arm64-v8a'}, CORE.delivery_targets('android-arm64'))
        self.assertEqual(CORE.delivery_targets('android-arm64'), SENTINEL.delivery_targets('android-arm64'))
        self.assertEqual(CORE.delivery_targets('android-arm64'), SNAPSHOT.SHAPES['android-arm64'])

    def test_original_android4_authorities_stay_four_targets(self):
        self.assertEqual(4, len(CORE.delivery_targets('android4')))
        self.assertEqual(4, len(SENTINEL.delivery_targets('android4')))
        self.assertEqual(4, len(SNAPSHOT.SHAPES['android4']))

    def test_old_four_abi_receipt_cannot_claim_arm64_profile(self):
        old = {'androidDeliveryProfile':'android-arm64','abis':['arm64-v8a','armeabi-v7a','x86','x86_64'],
               'targets':['aarch64-linux-android','armv7-linux-androideabi','i686-linux-android','x86_64-linux-android']}
        with self.assertRaises(ValueError): CORE.validate_delivery_profile(old, 'android-arm64')
        with self.assertRaises(ValueError): SENTINEL.require_delivery_profile(old, 'android-arm64')

    def test_source_configuration_and_selected_abi_must_agree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root/'config').mkdir()
            (root/'config/foxcore-android-delivery.json').write_text(json.dumps({'schema':1,**PROFILE}))
            (root/'config/foxcore-source-manifest.json').write_text(json.dumps(PROFILE))
            self.assertEqual('android-arm64', DELIVERY.generator_profile(
                SimpleNamespace(core_delivery_profile='android-arm64',android_abis='arm64-v8a'),root))
            for abis in ('arm64-v8a x86_64','armeabi-v7a','arm64-v8a arm64-v8a'):
                with self.assertRaises(ValueError): DELIVERY.generator_profile(
                    SimpleNamespace(core_delivery_profile='android-arm64',android_abis=abis),root)

    def test_missing_preview_authority_does_not_use_old_producers(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises((ValueError,FileNotFoundError)):
                RETAINED.authority(Path(directory).resolve(),'1'*40)

    def fixture(self, root):
        baseline = ROOT/'third_party/licenses/source-legal/reference-android4/snapshot.json'
        destination = root/'third_party/licenses/source-legal/android4/snapshot.json'
        destination.parent.mkdir(parents=True); shutil.copyfile(baseline,destination)
        (root/'config').mkdir(); (root/'scripts').mkdir()
        shutil.copyfile(ROOT/'scripts/selected-sentinel-sbom.py',root/'scripts/selected-sentinel-sbom.py')
        old = json.loads(baseline.read_text())
        authority = {'schema':1,'kind':'retained-android4-sentinel-arm64-preview','status':'BOUND_ACTUAL',
            'consumerCoreCommit':'1'*40,'originalCoreCommit':old['coreCommit'],
            'referenceSnapshotSha256':RETAINED.digest(baseline.read_bytes()),'coreProducerReceiptSha256':'2'*64,
            'currentCoreNativeLibrary':{'abi':'arm64-v8a','bytes':1,'sha256':'3'*64}}
        (root/'config/sentinel-arm64-preview.json').write_text(json.dumps(authority))
        payload = baseline.parent/'payload'
        for name in old['files']:
            if name.startswith('sentinel/'):
                target=destination.parent/'payload'/name
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(payload/name,target)
        def notice(name):
            return RETAINED.encoded(authority) if name=='sentinel/RETAINED-ARM64-PREVIEW.json' else (payload/name).read_bytes()
        return old,authority,notice

    def test_retained_reference_keeps_original_full_coverage_and_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();old,authority,notice=self.fixture(root)
            result=RETAINED.validate(root,'1'*40,notice,old['sentinelInputs'],authority['currentCoreNativeLibrary'])
            self.assertEqual(old['coreCommit'],result['coverage']['coreCommit'])
            self.assertEqual(4,len(result['coverage']['targets']))
            self.assertNotEqual('1'*40,result['coverage']['coreCommit'])

    def test_retained_native_cannot_be_replaced_or_given_new_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();old,authority,notice=self.fixture(root)
            with self.assertRaises(ValueError):RETAINED.validate(root,'4'*40,notice)
            bad=dict(authority['currentCoreNativeLibrary'],sha256='5'*64)
            with self.assertRaises(ValueError):RETAINED.validate(root,'1'*40,notice,current_core_native=bad)

    def test_changed_sentinel_source_refuses_retention(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();old,authority,notice=self.fixture(root)
            changed=dict(old['sentinelInputs']);changed.pop(next(iter(changed)))
            with self.assertRaises(ValueError):RETAINED.validate(root,'1'*40,notice,changed)

    def test_changed_original_notice_bytes_refuse_retention(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();old,authority,notice=self.fixture(root)
            with self.assertRaises(ValueError):RETAINED.validate(root,'1'*40,
                lambda name:b'changed' if name=='sentinel/REVISION.txt' else notice(name))

if __name__=='__main__':unittest.main()
