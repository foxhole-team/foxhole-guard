"""Focused android64 legal contract fixtures; all libraries are synthetic byte strings."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent
REVISION = 'a' * 40
SHAPE = {'androidDeliveryProfile':'android64','abis':['arm64-v8a','x86_64'],
         'targets':['aarch64-linux-android','x86_64-linux-android']}

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

def sha(raw): return hashlib.sha256(raw).hexdigest()
def encoded(value): return json.dumps(value, indent=2, sort_keys=True).encode() + b'\n'

FORK_FIXTURE = load('existing_fork_fixture', HERE/'test_rustls_fork_legal.py')
GENERATOR = load('android64_generator', SCRIPTS/'generate-license-assets.py')
AGGREGATE = load('android64_aggregate', SCRIPTS/'aggregate-delivery-sbom.py')

class Android64DeliveryTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.app = self.root/'app-source'; (self.app/'scripts').mkdir(parents=True); (self.app/'config').mkdir()
        for name in ('selected-license-sbom.py','rustls-fork-origin.py','android-delivery-profile.py'):
            shutil.copyfile(SCRIPTS/name, self.app/'scripts'/name)
        (self.app/'config/foxcore-android-delivery.json').write_bytes(encoded({'schema':1,**SHAPE}))
        self.cases = {}; self.rows = {}; self.target_data = {}
        self.library_bytes = {'arm64-v8a':b'synthetic android64 arm64 native metadata fixture\n',
                              'x86_64':b'synthetic android64 x64 native metadata fixture\n'}
        self.library_bytes['armeabi-v7a'] = b'synthetic ARMv7 native metadata fixture\n'
        self.library_bytes = {abi: self.library_bytes[abi] for abi in SHAPE['abis']}
        self.native_rows = [{'path':abi+'/libfoxhole_native.so','bytes':len(raw),'sha256':sha(raw)}
                            for abi,raw in self.library_bytes.items()]
        for target,abi in zip(SHAPE['targets'], SHAPE['abis']):
            fixture = FORK_FIXTURE.GuardForkLegalTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
            target_root = self.root/target; shutil.copytree(fixture.root,target_root)
            fixture.root, fixture.sbom, fixture.receipt_path = target_root,target_root/'selected.cdx.json',target_root/'receipt.json'
            self.cases[target] = fixture
            source = self.input_value(fixture,'sourceManifest'); source.update(SHAPE)
            fixture.put('sourceManifest', source)
            producer = self.input_value(fixture,'producerReceipt'); producer.update(SHAPE)
            producer['libraries'] = self.native_rows
            producer['sourceManifestSha256'] = fixture.inputs['sourceManifest']['sha256']
            fixture.put('producerReceipt',producer)
            capture = self.input_value(fixture,'captureReceipt'); capture.update(SHAPE)
            capture.update(producerReceiptSha256=fixture.inputs['producerReceipt']['sha256'],
                           sourceManifestSha256=fixture.inputs['sourceManifest']['sha256'],
                           steps=[{'label':'sbom-'+t,'exit_code':0} for t in SHAPE['targets']])
            fixture.put('captureReceipt',capture); fixture.put('nativeLibrary',self.library_bytes[abi])
            original = copy.deepcopy(fixture.original)
            if target == 'x86_64-linux-android':
                package = {'id':'registry+https://github.com/rust-lang/crates.io-index#synthetic-x64@2.0.0',
                           'name':'synthetic-x64','version':'2.0.0','source':'registry+https://github.com/rust-lang/crates.io-index'}
                self.extra_ref = 'urn:foxhole:cargo:'+sha(package['id'].encode())
                metadata = self.input_value(fixture,'cargoMetadata'); metadata['packages'].append(package)
                metadata['resolve']['nodes'][0]['deps'].append({'pkg':package['id'],'dep_kinds':[{'kind':None}]})
                metadata['resolve']['nodes'].append({'id':package['id'],'deps':[]})
                fixture.put('cargoMetadata',metadata)
                tree_path = fixture.root/fixture.inputs['cargoTree']['path']
                fixture.put('cargoTree',tree_path.read_bytes()+b'1|synthetic-x64 v2.0.0|\n')
                original['components'].append({'type':'library','name':'synthetic-x64','version':'2.0.0',
                    'bom-ref':self.extra_ref,'purl':'pkg:cargo/synthetic-x64@2.0.0','licenses':[{'expression':'MIT'}]})
                original['dependencies'][0]['dependsOn'].append(self.extra_ref)
                original['dependencies'].append({'ref':self.extra_ref,'dependsOn':[]})
            props = {r['name']:r['value'] for r in original['metadata']['properties']}
            props.update({'foxhole:native:target':target,'foxhole:native:tree-sha256':fixture.inputs['cargoTree']['sha256'],
                          'foxhole:native:metadata-sha256':fixture.inputs['cargoMetadata']['sha256']})
            original['metadata']['properties'] = [{'name':k,'value':v} for k,v in props.items()]
            fixture.put('originalSbom',original)
            view = copy.deepcopy(original); view['metadata']['component']['purl'] = FORK_FIXTURE.selected.local_purl('foxcore-android','1.0.0',REVISION)
            view['metadata']['properties'] += FORK_FIXTURE.selected.delivery_properties(1,fixture.inputs['originalSbom']['sha256'])
            view['metadata']['properties'] += [{'name':'foxhole:native:vendored-fork-components','value':'1'},
                                              {'name':'foxhole:native:android-delivery-profile','value':SHAPE['androidDeliveryProfile']}]
            fixture.sbom.write_bytes(encoded(view)); self.target_data[target] = view
            target_capture = self.input_value(fixture,'targetCapture'); target_capture.update(SHAPE)
            target_capture.update(target=target,source_manifest_sha256=fixture.inputs['sourceManifest']['sha256'],
                source_archive_sha256=fixture.inputs['sourceArchive']['sha256'],
                source_fork_origin_sha256=source['files'][FORK_FIXTURE.origin.ORIGIN_PATH],
                metadata={'sha256':fixture.inputs['cargoMetadata']['sha256'],'exit_code':0},
                tree={'sha256':fixture.inputs['cargoTree']['sha256'],'exit_code':0})
            fixture.put('targetCapture',target_capture)
            notice = self.input_value(fixture,'cargoNoticeManifest'); notice['sbom_sha256'] = sha(fixture.sbom.read_bytes())
            if target == 'x86_64-linux-android':
                raw = b'Synthetic x64-only legal fixture\n'; (fixture.root/'LICENSE-x64-only').write_bytes(raw)
                notice['files'].append({'path':'LICENSE-x64-only','sha256':sha(raw),'owners':[self.extra_ref]})
            fixture.put('cargoNoticeManifest',notice)
            receipt = json.loads(fixture.receipt_path.read_bytes()); receipt.update(SHAPE,target=target,sbomSha256=sha(fixture.sbom.read_bytes()),inputs=fixture.inputs)
            fixture.receipt_path.write_bytes(encoded(receipt))
            self.rows[target] = {'receipt':{'path':target+'/receipt.json','sha256':sha(fixture.receipt_path.read_bytes())},
                                 'sbom':{'path':target+'/selected.cdx.json','sha256':sha(fixture.sbom.read_bytes())}}
        primary = self.cases[SHAPE['targets'][0]]
        self.lock = primary.lock
        self.pins = {'commit':REVISION,**SHAPE,'files':[{'path':p,'sha256':sha(raw)} for p,(raw,_) in sorted(primary.files.items())]}
        (self.app/'config/foxcore-source-manifest.json').write_bytes(encoded(self.pins))
        self.sbom = self.root/'primary.cdx.json'; self.sbom.write_bytes(primary.sbom.read_bytes())
        self.union_notices = {}; owners = {}; target_rows = {}; all_refs = set(); all_edges = set()
        primary_refs = {n['bom-ref'] for n in [self.target_data[SHAPE['targets'][0]]['metadata']['component']]+self.target_data[SHAPE['targets'][0]]['components']}
        for target,case in self.cases.items():
            document = self.target_data[target]; refs = {n['bom-ref'] for n in [document['metadata']['component']]+document['components']}
            edges = {(r['ref'],d) for r in document['dependencies'] for d in r['dependsOn']}
            all_refs.update(refs); all_edges.update(edges)
            catalog = self.input_value(case,'cargoNoticeManifest')
            for row in catalog['files']:
                self.union_notices[row['path']] = (case.root/row['path']).read_bytes()
                owners.setdefault(row['path'],set()).update(row['owners'])
            target_rows[target] = {'abi':SHAPE['abis'][SHAPE['targets'].index(target)],'componentRefs':sorted(refs),
                'components':len(refs),'edges':len(edges),'originalSbomSha256':case.inputs['originalSbom']['sha256'],
                'deliverySbomSha256':sha(case.sbom.read_bytes()),'nativeLibrarySha256':case.inputs['nativeLibrary']['sha256'],
                'noticeManifestSha256':case.inputs['cargoNoticeManifest']['sha256'],'componentSubsetOfPrimary':refs<=primary_refs}
        catalog = {'schema_version':2,'coverage':'complete','uncovered':[],**SHAPE,
            'target_sboms':{t:sha(c.sbom.read_bytes()) for t,c in self.cases.items()},'component_refs':sorted(all_refs),
            'files':[{'path':p,'sha256':sha(raw),'owners':sorted(owners[p])} for p,raw in sorted(self.union_notices.items())]}
        self.union = self.root/'union.json'; self.union.write_bytes(encoded(catalog))
        authorities = {k:primary.inputs[k]['sha256'] for k in ('producerReceipt','sourceManifest','sourceArchive','captureReceipt')}
        authorities['cargoLockSha256'] = sha(self.lock)
        coverage = {'schema':1,'coreCommit':REVISION,'consumer':'guard','features':'shipped','primaryTarget':SHAPE['targets'][0],
            'authorities':authorities,'targets':target_rows,'unionComponents':len(all_refs),'unionEdges':len(all_edges),
            'unionComponentRefs':sorted(all_refs),'noticeManifestSha256':sha(self.union.read_bytes()),'coverage':'complete',
            'scope':('Two distinct android64 captured Cargo graphs, exact producer/native source and original notice union' if SHAPE['androidDeliveryProfile'] == 'android64' else 'One exact ARMv7 captured Cargo graph, producer/native source and original notice union'),
            'androidDeliveryProfile':SHAPE['androidDeliveryProfile'],'abis':SHAPE['abis']}
        self.coverage = self.root/'coverage.json'; self.coverage.write_bytes(encoded(coverage))
        self.receipt = {'schema':2,'coreCommit':REVISION,'consumer':'guard','features':'shipped','target':SHAPE['targets'][0],
            **SHAPE,'targets':self.rows,'inputs':primary.inputs,'sbomSha256':sha(self.sbom.read_bytes()),
            'abiCoverage':{'path':'coverage.json','sha256':sha(self.coverage.read_bytes())},
            'unionNoticeManifest':{'path':'union.json','sha256':sha(self.union.read_bytes())}}
        self.receipt_path = self.root/'delivery.json'; self.save_receipt()
        self.args = SimpleNamespace(foxcore_sbom=self.sbom,foxcore_sbom_receipt=self.receipt_path,android_abis=' '.join(SHAPE['abis']),core_delivery_profile=SHAPE['androidDeliveryProfile'])

    def input_value(self, case, name): return json.loads((case.root/case.inputs[name]['path']).read_bytes())
    def save_receipt(self): self.receipt_path.write_bytes(encoded(self.receipt))
    def inventory(self):
        blobs = {'Cargo.toml':b'[workspace.package]\nversion="1.0.0"\n','Cargo.lock':self.lock}
        with patch.object(GENERATOR,'REPO_ROOT',self.app), patch.object(GENERATOR,'git_blob',side_effect=lambda r,c,n:blobs[n]):
            return GENERATOR.core_inventory(self.args,self.root,REVISION)

    def delivery_fixture(self):
        fixture = FORK_FIXTURE.load('aggregate_fixture','tests/test_delivery_sbom.py').DeliverySbomTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        for name in ('foxcore-android-delivery.json','foxcore-source-manifest.json'):
            shutil.copyfile(self.app/'config'/name,fixture.root/'config'/name)
        fixture.inventory['MANIFEST.json'] = encoded({'foxcoreRevision':REVISION,**SHAPE})
        fixture.inventory['foxcore/ABI-COVERAGE.json'] = self.coverage.read_bytes()
        fixture.inventory['foxcore/cargo-notices/MANIFEST.json'] = self.union.read_bytes()
        for target,case in self.cases.items(): fixture.inventory['foxcore/foxcore-'+target+'.cdx.json'] = case.sbom.read_bytes()
        for path,raw in self.union_notices.items(): fixture.inventory['foxcore/cargo-notices/'+path] = raw
        if SHAPE['androidDeliveryProfile'] == 'android-armv7':
            fixture.libraries = {name.replace('arm64-v8a', 'armeabi-v7a'): raw for name, raw in fixture.libraries.items()}
        else:
            for path,raw in list(fixture.libraries.items()):
                if path.startswith('lib/arm64-v8a/'): fixture.libraries[path.replace('arm64-v8a','x86_64',1)] = raw
        if SHAPE['androidDeliveryProfile'] == 'android3':
            for path, raw in list(fixture.libraries.items()):
                if path.startswith('lib/arm64-v8a/'):
                    fixture.libraries[path.replace('arm64-v8a', 'armeabi-v7a', 1)] = raw
        for abi,raw in self.library_bytes.items(): fixture.libraries['lib/'+abi+'/libfoxhole_native.so'] = raw
        arm = SHAPE['androidDeliveryProfile'] == 'android-armv7'
        sentinel_module = load('aggregate_complete_sentinel_fixture', HERE / ('test_sentinel_armv7_delivery.py' if arm else 'test_sentinel_android64_delivery.py'))
        if SHAPE['androidDeliveryProfile'] == 'android3':
            sentinel_module = load('aggregate_three_sentinel_fixture', HERE / 'test_sentinel_android3_delivery.py')
            sentinel_case = sentinel_module.SentinelAndroid3DeliveryTest()
        else:
            sentinel_case = (sentinel_module.SentinelArmv7DeliveryTest() if arm else sentinel_module.SentinelAndroid64DeliveryTest())
        sentinel_case.setUp(); self.addCleanup(sentinel_case.doCleanups)
        material = sentinel_case.validate()
        fixture.inventory['sentinel/REVISION.txt'] = (REVISION + '\n').encode()
        fixture.inventory.pop('sentinel/sbom/sentinel-yara-all-targets.cdx.json')
        fixture.inventory['sentinel/ABI-COVERAGE.json'] = material['coverage']
        for target, raw in material['targets'].items():
            fixture.inventory['sentinel/selected-' + target + '.cdx.json'] = raw
            row = sentinel_case.fixture.receipt['targets'][target]
            receipt = json.loads((sentinel_case.fixture.root / row['receipt']['path']).read_bytes())
            native = receipt['inputs']['nativeLibrary']
            abi = SHAPE['abis'][SHAPE['targets'].index(target)]
            fixture.libraries['lib/' + abi + '/libfoxhole_sentinel_yara.so'] = (sentinel_case.fixture.root / native['path']).read_bytes()
        for name, raw in material['notices']:
            fixture.inventory['sentinel/cargo-notices/' + name] = raw
        shutil.copyfile(SCRIPTS / 'selected-sentinel-sbom.py', fixture.root / 'scripts/selected-sentinel-sbom.py')
        fixture.write_inputs(); return fixture

    def test_existing_generator_api_accepts_exact_android64_producer_and_distinct_union(self):
        self.assertEqual(self.sbom.read_bytes(),self.inventory())
        fixture = self.delivery_fixture(); result = fixture.aggregate()
        self.assertTrue(any(c['bom-ref']=='foxcore:'+self.extra_ref for c in result['components']))

    def test_missing_trusted_config_or_source_pin_profile_cannot_fallback(self):
        path = self.app/'config/foxcore-android-delivery.json'; raw = path.read_bytes(); path.unlink()
        with self.assertRaises(ValueError): self.inventory()
        path.write_bytes(raw)
        pins = dict(self.pins); pins.pop('androidDeliveryProfile'); (self.app/'config/foxcore-source-manifest.json').write_bytes(encoded(pins))
        with self.assertRaises(ValueError): self.inventory()

    def test_unknown_one_three_or_wrong_profile_never_selects_legacy(self):
        for abis in ('arm64-v8a','arm64-v8a x86 x86_64','arm64-v8a x86_64 x86_64'):
            self.args.android_abis = abis
            with self.assertRaises(ValueError): self.inventory()
        self.args.android_abis = 'arm64-v8a x86_64'; self.args.core_delivery_profile = 'unknown'
        with self.assertRaises(ValueError): self.inventory()

    def test_missing_or_extra_target_receipt_is_refused(self):
        saved = copy.deepcopy(self.receipt)
        self.receipt['targets'].pop('x86_64-linux-android'); self.save_receipt()
        with self.assertRaises(ValueError): self.inventory()
        self.receipt = saved; self.receipt['targets']['i686-linux-android'] = self.receipt['targets']['aarch64-linux-android']; self.save_receipt()
        with self.assertRaises(ValueError): self.inventory()

    def test_stripped_profile_in_each_native_authority_is_refused(self):
        for name in ('producerReceipt','sourceManifest','captureReceipt','targetCapture'):
            case = self.cases['aarch64-linux-android']; original = self.input_value(case,name)
            changed = dict(original); changed.pop('androidDeliveryProfile'); case.put(name,changed)
            receipt = json.loads(case.receipt_path.read_bytes()); receipt['inputs'] = case.inputs; case.receipt_path.write_bytes(encoded(receipt))
            self.receipt['targets']['aarch64-linux-android']['receipt']['sha256'] = sha(case.receipt_path.read_bytes()); self.receipt['inputs'] = case.inputs; self.save_receipt()
            with self.subTest(authority=name), self.assertRaises(ValueError): self.inventory()
            case.put(name,original)

    def test_zip_abi_set_and_each_core_elf_are_independently_bound(self):
        for change in ('missing','extra32','modified'):
            fixture = self.delivery_fixture()
            if change=='missing':
                fixture.libraries = {p:r for p,r in fixture.libraries.items() if '/x86_64/' not in p}
            elif change=='extra32': fixture.libraries['lib/x86/libfoxhole_native.so'] = b'unqualified32'
            else: fixture.libraries['lib/x86_64/libfoxhole_native.so'] = b'changed'
            fixture.write_inputs()
            with self.subTest(change=change), self.assertRaises(ValueError): fixture.aggregate()

    def test_stripping_all_metadata_cannot_hide_current_two_abi_profile(self):
        fixture = self.delivery_fixture(); (fixture.root/'config/foxcore-android-delivery.json').unlink()
        fixture.inventory['MANIFEST.json'] = encoded({'foxcoreRevision':REVISION})
        fixture.inventory.pop('foxcore/ABI-COVERAGE.json')
        fixture.write_inputs()
        with self.assertRaises(ValueError): fixture.aggregate()
        fixture.libraries = {p:r for p,r in fixture.libraries.items() if "/x86_64/" not in p}
        pins_path = fixture.root / "config/foxcore-source-manifest.json"
        pins = json.loads(pins_path.read_bytes())
        for key in SHAPE: pins.pop(key, None)
        pins_path.write_bytes(encoded(pins))
        fixture.write_inputs()
        with self.assertRaises(ValueError): fixture.aggregate()

    def test_x64_only_package_requires_its_original_union_notice(self):
        fixture = self.delivery_fixture(); catalog = json.loads(fixture.inventory['foxcore/cargo-notices/MANIFEST.json'])
        catalog['files'] = [r for r in catalog['files'] if r['path']!='LICENSE-x64-only']
        raw = encoded(catalog); fixture.inventory['foxcore/cargo-notices/MANIFEST.json'] = raw
        coverage = json.loads(fixture.inventory['foxcore/ABI-COVERAGE.json']); coverage['noticeManifestSha256'] = sha(raw)
        fixture.inventory['foxcore/ABI-COVERAGE.json'] = encoded(coverage); fixture.write_inputs()
        with self.assertRaisesRegex(ValueError,'notice union omits'): fixture.aggregate()

    def test_legacy_single_target_fixture_keeps_its_existing_loader_behavior(self):
        fixture = FORK_FIXTURE.GuardForkLegalTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        self.assertEqual(fixture.sbom.read_bytes(),fixture.validate())

if __name__=='__main__': unittest.main()
