"""Regressions for genuine internal Gradle projects, separate from Maven coordinates."""
import argparse,copy,importlib.util,json,sys,unittest
from pathlib import Path
SCRIPTS=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('existing_delivery_fixture',SCRIPTS/'tests/test_delivery_sbom.py')
BASE=importlib.util.module_from_spec(s);s.loader.exec_module(BASE);MODULE=BASE.MODULE
RAW=SCRIPTS.parent/'third_party/maven-runtime/maven-runtime.cdx.json'
NAMES=('importer','model','network','profile','runtime','sentinel')
def project(name):
 return {'type':'library','bom-ref':'project::core:'+name,'name':name,'version':'1.0.0','licenses':[{'license':{'id':'GPL-3.0-or-later'}}]}
class InternalProjectBomTest(unittest.TestCase):
 setUp=BASE.DeliverySbomTest.setUp
 write_inputs=BASE.DeliverySbomTest.write_inputs
 aggregate=BASE.DeliverySbomTest.aggregate
 def internal_graph(self):
  self.maven['metadata']['component'].update({'bom-ref':'project::app','name':'FoxHole Guard','version':'1.0.0'})
  self.maven['dependencies'][0]['ref']='project::app'
  for name in NAMES:
   row=project(name);self.maven['components'].append(row);self.maven['dependencies'][0]['dependsOn'].append(row['bom-ref']);self.maven['dependencies'].append({'ref':row['bom-ref'],'dependsOn':['maven-dependency']})
  return self.maven
 def test_actual_collected_graph_keeps_all_project_nodes_edges_and_no_fake_purl(self):
  document=json.loads(RAW.read_bytes());before=copy.deepcopy(document)
  missing=[c for c in document['components']if 'purl'not in c];self.assertEqual(set('project::core:'+n for n in NAMES),{c['bom-ref']for c in missing})
  self.assertEqual('project::app',MODULE.validate_bom(document,'actual collected Maven BOM'))
  self.assertEqual(before,document)
 def test_full_aggregate_keeps_projects_and_real_maven_native_owner(self):
  self.internal_graph();graphics='pkg:maven/androidx.graphics/graphics-path@1.0.1'
  self.maven['components'].append({'type':'library','bom-ref':graphics,'purl':graphics,'name':'graphics-path','version':'1.0.1'})
  self.maven['dependencies'][0]['dependsOn'].append(graphics);self.maven['dependencies'].append({'ref':graphics,'dependsOn':[]})
  self.libraries['lib/arm64-v8a/libandroidx.graphics.path.so']=b'owned existing Maven graphics fixture'
  original=copy.deepcopy(self.maven);self.write_inputs();result=self.aggregate()
  entries={c['bom-ref']:c for c in result['components']};edges={r['ref']:r['dependsOn']for r in result['dependencies']}
  for name in NAMES:
   ref='project::core:'+name;self.assertEqual(project(name),entries[ref]);self.assertNotIn('purl',entries[ref]);self.assertEqual(['maven-dependency'],edges[ref]);self.assertIn(ref,edges['project::app'])
  native=next(c for c in entries.values()if c['name']=='lib/arm64-v8a/libandroidx.graphics.path.so')
  self.assertEqual([graphics],edges[native['bom-ref']]);self.assertEqual(original,self.maven)
 def test_external_missing_or_empty_purl_rejected(self):
  for value in ('ABSENT','',None,1):
   doc=copy.deepcopy(self.maven)
   if value=='ABSENT':doc['components'][0].pop('purl')
   else:doc['components'][0]['purl']=value
   with self.subTest(value=value),self.assertRaisesRegex(ValueError,'incomplete component identity'):MODULE.validate_bom(doc,'external')
 def test_unknown_project_or_mismatched_name_version_license_rejected(self):
  self.internal_graph()
  for field,value in [('bom-ref','project::core:unknown'),('name','other'),('version','2.0.0'),('licenses',[]),('type','file')]:
   doc=copy.deepcopy(self.maven);doc['components'][-1][field]=value
   with self.subTest(field=field),self.assertRaisesRegex(ValueError,'incomplete component identity'):MODULE.validate_bom(doc,'project')
 def test_external_cannot_claim_internal_name_without_project_identity(self):
  self.internal_graph();doc=copy.deepcopy(self.maven);doc['components'][-1]['bom-ref']='external:sentinel'
  with self.assertRaisesRegex(ValueError,'incomplete component identity'):MODULE.validate_bom(doc,'forged external')
 def test_project_duplicates_and_dangling_edges_still_fail(self):
  self.internal_graph();duplicate=copy.deepcopy(self.maven);duplicate['components'].append(project('sentinel'))
  with self.assertRaisesRegex(ValueError,'duplicate'):MODULE.validate_bom(duplicate,'duplicate')
  dangling=copy.deepcopy(self.maven);dangling['dependencies'][-1]['dependsOn']=['not-delivered']
  with self.assertRaisesRegex(ValueError,'dangling'):MODULE.validate_bom(dangling,'dangling')
 def test_explicit_bad_project_purl_and_wrong_application_root_rejected(self):
  self.internal_graph()
  for value in ('',None,1):
   doc=copy.deepcopy(self.maven);doc['components'][-1]['purl']=value
   with self.subTest(value=value),self.assertRaisesRegex(ValueError,'incomplete component identity'):MODULE.validate_bom(doc,'project explicit bad PURL')
  doc=copy.deepcopy(self.maven);doc['metadata']['component']['bom-ref']='external-root'
  with self.assertRaisesRegex(ValueError,'incomplete component identity'):MODULE.validate_bom(doc,'wrong root')


class DerivedNativeAggregateTest(unittest.TestCase):
    """Real two-ABI aggregate/producer checks; unrelated Core union is isolated."""
    setUp = BASE.DeliverySbomTest.setUp
    write_inputs = BASE.DeliverySbomTest.write_inputs
    aggregate = BASE.DeliverySbomTest.aggregate

    def derived_delivery(self):
        profile = {'schema': 1, 'androidDeliveryProfile': 'android64',
                   'abis': ['arm64-v8a', 'x86_64'],
                   'targets': ['aarch64-linux-android', 'x86_64-linux-android']}
        (self.root / 'config/foxcore-android-delivery.json').write_text(json.dumps(profile))
        self.inventory['MANIFEST.json'] = json.dumps({'foxcoreRevision': self.revision, **profile}).encode()
        for path, body in list(self.libraries.items()):
            self.libraries[path.replace('arm64-v8a', 'x86_64')] = body
        original = {'sodium.c': {'sha256': MODULE.sha256(b'original source')}}
        changes = {'sodium.c': {'sha256': MODULE.sha256(b'patched source')}}
        inventory = {'originalSourceInventory': original, 'compiledSourcePatchFiles': changes,
                     'actualReferenceEffectiveInventory': original | changes}
        inventory_raw = json.dumps(inventory).encode()
        inventory_path = self.root / 'config/native/sodium-source.json'
        inventory_path.parent.mkdir(parents=True)
        inventory_path.write_bytes(inventory_raw)
        source_files = {'native.cpp': {'sha256': MODULE.sha256(b'graphics source')}}
        config = {
            'graphics': {'commit': 'fixture-graphics-release', 'sourceFiles': source_files,
                         'originalAar': {'sha256': MODULE.sha256(b'graphics publisher AAR')}},
            'sodium': {'upstreamSecurityFixCommit': 'fixture-sodium-fix',
                       'patch': {'sha256': MODULE.sha256(b'sodium patch')},
                       'sourceInventory': {'path': 'config/native/sodium-source.json',
                                           'sha256': MODULE.sha256(inventory_raw)},
                       'originalAar': {'sha256': MODULE.sha256(b'sodium publisher AAR')}},
        }
        self.owners = {}
        self.derived_names = {'graphics': 'libandroidx.graphics.path.so', 'sodium': 'libsodium.so'}
        for kind, group, artifact, version, aar in (
                ('graphics', 'androidx.graphics', 'graphics-path', '1.0.1', 'graphics-path-1.0.1.aar'),
                ('sodium', 'com.goterl', 'lazysodium-android', '5.2.0', 'lazysodium-android-5.2.0.aar')):
            owner = 'pkg:maven/' + group + '/' + artifact + '@' + version
            self.owners[kind] = owner
            self.maven['components'].append({'type': 'library', 'bom-ref': owner, 'purl': owner,
                                             'name': artifact, 'version': version})
            self.maven['dependencies'][0]['dependsOn'].append(owner)
            self.maven['dependencies'].append({'ref': owner, 'dependsOn': []})
            inputs = {'files': {aar: config[kind]['originalAar']['sha256']}}
            if kind == 'sodium':
                inputs['files']['libsodium-CVE-2025-69277-upstream.patch'] = config[kind]['patch']['sha256']
            input_raw = json.dumps(inputs).encode()
            producer = {'exitCode': 0, 'sourceMutations': [],
                        'producerInputManifestSha256': MODULE.sha256(input_raw), 'libraries': []}
            for abi in profile['abis']:
                name = self.derived_names[kind]
                body = ('synthetic ' + kind + ' ' + abi).encode()
                self.libraries['lib/' + abi + '/' + name] = body
                producer['libraries'].append({'abi': abi, 'path': abi + '/' + name,
                                              'sha256': MODULE.sha256(body), 'bytes': len(body)})
            if kind == 'graphics':
                producer.update(graphicsReleaseCommit=config[kind]['commit'], graphicsOriginalSource=source_files)
            else:
                producer.update(upstreamSecurityFixCommit=config[kind]['upstreamSecurityFixCommit'],
                                upstreamGreenTestCounts={'TOTAL': 80, 'PASS': 80, 'SKIP': 0, 'XFAIL': 0,
                                                         'FAIL': 0, 'XPASS': 0, 'ERROR': 0},
                                upstreamRedRegressionExpectedExit=2, originalSodiumSource=original,
                                effectivePatchedSodiumSource=original | changes)
            prefix = 'derived-native/' + kind + '/'
            self.inventory[prefix + 'producer-inputs.json'] = input_raw
            self.inventory[prefix + 'producer-receipt.json'] = json.dumps(producer).encode()
        (self.root / 'config/native/page-aligned-native.json').write_text(json.dumps(config))
        self.write_inputs()

    def complete_aggregate(self):
        from unittest.mock import patch
        core = json.loads(self.inventory['foxcore/foxcore-aarch64-linux-android.cdx.json'])
        # Only the unrelated Core cross-target union has separate fixtures. Both real
        # derived verifiers, actual ZIP/notices, native file hashes and final graph run.
        with patch.object(MODULE, 'android64_core_union', return_value=(copy.deepcopy(core), b'{}')):
            return self.aggregate()

    def test_two_abis_share_verified_logical_libraries_and_keep_all_file_edges(self):
        self.derived_delivery()
        maven_before = copy.deepcopy(self.maven)
        result = self.complete_aggregate()
        entries = result['components']
        self.assertEqual(len(entries), len({row['bom-ref'] for row in entries}))
        edges = {row['ref']: row['dependsOn'] for row in result['dependencies']}
        for kind, filename in self.derived_names.items():
            logical = [row for row in entries if row['name'] == 'foxhole-derived-' + kind + '-native']
            self.assertEqual(1, len(logical))
            logical = logical[0]
            self.assertEqual([self.owners[kind]], edges[logical['bom-ref']])
            properties = {row['name']: row['value'] for row in logical['properties']}
            prefix = 'derived-native/' + kind + '/'
            self.assertEqual(MODULE.sha256(self.inventory[prefix + 'producer-receipt.json']),
                             properties['foxhole:producerReceiptSha256'])
            self.assertEqual(MODULE.sha256(self.inventory[prefix + 'producer-inputs.json']),
                             properties['foxhole:producerInputsSha256'])
            abi_refs = []
            for abi in ('arm64-v8a', 'x86_64'):
                path = 'lib/' + abi + '/' + filename
                files = [row for row in entries if row['name'] == path]
                self.assertEqual(1, len(files))
                entry = files[0]
                self.assertEqual('file', entry['type'])
                self.assertEqual([{'alg': 'SHA-256', 'content': MODULE.sha256(self.libraries[path])}], entry['hashes'])
                self.assertEqual([logical['bom-ref']], edges[entry['bom-ref']])
                self.assertIn(entry['bom-ref'], edges['app'])
                abi_refs.append(entry['bom-ref'])
            self.assertNotEqual(*abi_refs)
        self.assertEqual(maven_before, self.maven)
        self.assertEqual('app', MODULE.validate_bom(result, 'complete two-ABI derived native aggregate'))

    def test_conflicting_same_ref_is_rejected_without_overwrite_or_arbitrary_dedup(self):
        import zipfile
        self.derived_delivery()
        original = copy.deepcopy(self.maven)
        for kind, filename in self.derived_names.items():
            path = 'lib/arm64-v8a/' + filename
            with zipfile.ZipFile(self.apk) as archive:
                verified = MODULE.derived_native_component(filename, path, self.libraries[path],
                                                          archive, self.notices, self.root)
            for field, value in (('name', 'conflicting logical library'),
                                 ('licenses', [{'license': {'id': 'MIT'}}]),
                                 ('properties', [{'name': 'foxhole:producerReceiptSha256', 'value': '0' * 64}])):
                self.maven = copy.deepcopy(original)
                conflicting = copy.deepcopy(verified)
                conflicting[field] = value
                self.maven['components'].append(conflicting)
                before = copy.deepcopy(self.maven)
                with self.subTest(kind=kind, field=field), self.assertRaisesRegex(ValueError, 'Conflicting derived native component identity'):
                    self.complete_aggregate()
                self.assertEqual(before, self.maven)

METHODS=[n for n in InternalProjectBomTest.__dict__ if n.startswith('test_')]
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--raw-bom',type=Path);p.add_argument('--derived-native-only',action='store_true');p.add_argument('-v',action='store_true');a=p.parse_args()
 if a.raw_bom:RAW=a.raw_bom
 cases=DerivedNativeAggregateTest if a.derived_native_only else InternalProjectBomTest
 methods=[n for n in cases.__dict__ if n.startswith('test_')]
 result=unittest.TextTestRunner(verbosity=2 if a.v else 1).run(unittest.TestSuite(cases(n)for n in methods));raise SystemExit(not result.wasSuccessful())
