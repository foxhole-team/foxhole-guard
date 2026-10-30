"""Focused synthetic contract tests; these fixtures are not native/runtime evidence."""
from pathlib import Path
import copy,hashlib,importlib.util,json,os,shutil,subprocess,tempfile,unittest
REPO=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('source_legal_snapshot_under_test',REPO/'scripts/source-legal-snapshot.py');M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
def encoded(v):return(json.dumps(v,indent=2,sort_keys=True)+'\n').encode()
def put(p,raw):p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
def h(raw):return hashlib.sha256(raw).hexdigest()
def fixture(root,profile='android3',source_producer='android3',core_bom_producer=None):
 root=Path(root).resolve()
 core=root/'core';app=root/'app';core.mkdir();app.mkdir();shape=M.SHAPES[profile]
 source={'Cargo.toml':b'[workspace.package]\nversion="1.0.0"\n','Cargo.lock':b'version=4\n','rust-toolchain.toml':b'[toolchain]\nchannel="1.97.1"\n','LICENSE':b'Synthetic Core license\n','THIRD_PARTY_NOTICES.md':b'Synthetic notices\n','native/sentinel-yara/Cargo.toml':b'[package]\nname="foxhole-sentinel-yara"\nversion="0.1.0"\n','native/sentinel-yara/Cargo.lock':b'version=4\n','native/sentinel-yara/LICENSE':b'Synthetic scanner license\n','native/sentinel-yara/README.md':b'Synthetic scanner readme\n','native/sentinel-yara/vendor/PROVENANCE.json':b'{"synthetic":true}\n','native/sentinel-yara/src/lib.rs':b'// synthetic source\n'}
 for i in range(519):source['crates/synthetic/src/input'+str(i)+'.rs']=b'// synthetic compiled input\n'
 for n,raw in source.items():put(core/n,raw)
 def git(*args):return subprocess.check_output(['git','-C',str(core),*args],stderr=subprocess.DEVNULL)
 git('init','-q');git('add','.');git('-c','commit.gpgsign=false','-c','user.name=Synthetic Contract Test','-c','user.email=fixture@example.invalid','commit','-qm','Synthetic source fixture');revision=git('rev-parse','HEAD').decode().strip()
 compiled={n:h(raw)for n,raw in source.items()if n in('Cargo.toml','Cargo.lock','rust-toolchain.toml')or n.startswith('crates/')};assert len(compiled)==522
 put(app/'config/foxcore-source-manifest.json',encoded({'commit':revision,'androidDeliveryProfile':profile,'abis':list(shape.values()),'targets':list(shape),'files':[{'path':n,'sha256':v}for n,v in sorted(compiled.items())]}))
 put(app/'scripts/selected-sentinel-sbom.py',(REPO/'scripts/selected-sentinel-sbom.py').read_bytes())
 snapshot=app/'third_party/source-legal'/profile/'snapshot.json';files={};native={'core':{},'sentinel':{}}
 for kind in native:
  for target,abi in shape.items():
   cls,machine={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86_64':(2,62),'x86':(1,3)}[abi];raw=bytearray(64);raw[:6]=b'\x7fELF'+bytes([cls,1]);raw[18:20]=machine.to_bytes(2,'little');raw[-1]=1 if kind=='core'else 2
   name='libfoxhole_native.so'if kind=='core'else'libfoxhole_sentinel_yara.so';directory='foxCoreNativeLibs'if kind=='core'else'sentinelNative/jniLibs';put(app/'app/build/generated'/directory/abi/name,raw);native[kind][target]={'abi':abi,'bytes':len(raw),'sha256':h(raw)}
 for kind in('core','sentinel'):
  prefix='foxcore'if kind=='core'else'sentinel';root_name='foxcore-android'if kind=='core'else'foxhole-sentinel-yara';version='1.0.0'if kind=='core'else'0.1.0';lock=source['Cargo.lock'if kind=='core'else'native/sentinel-yara/Cargo.lock'];rootref='urn:synthetic:'+kind;depref='urn:synthetic:dependency';targets={};bom_hashes={}
  for target,abi in shape.items():
   component={'bom-ref':rootref,'name':root_name,'version':version,'purl':'pkg:generic/foxhole/'+root_name+'@'+version+'?core_commit='+revision,'licenses':[{'expression':'GPL-3.0-or-later'}]};dep={'bom-ref':depref,'name':'synthetic-dependency','version':'1.0','purl':'pkg:cargo/synthetic-dependency@1.0','licenses':[{'expression':'MIT'}]}
   bom={'bomFormat':'CycloneDX','specVersion':'1.6','metadata':{'component':component,'properties':[{'name':'foxhole:native:core-revision','value':revision},{'name':'foxhole:native:target','value':target},{'name':'foxhole:native:cargo-lock-sha256','value':h(lock)},{'name':'foxhole:native:android-delivery-profile','value':profile},{'name':'foxhole:native:source-producer-profile','value':(core_bom_producer or source_producer)if kind=='core'else('android4'if profile=='android4'else'android3')}]},'components':[dep],'dependencies':[{'ref':rootref,'dependsOn':[depref]},{'ref':depref,'dependsOn':[]}]};raw=encoded(bom);name=prefix+('/foxcore-'if kind=='core'else'/selected-')+target+'.cdx.json';files[name]=raw;bom_hashes[target]=h(raw);targets[target]={'abi':abi,'componentRefs':sorted([rootref,depref]),'components':2,'edges':1,'deliverySbomSha256':h(raw),'nativeLibrarySha256':native[kind][target]['sha256'],'originalSbomSha256':'1'*64,'noticeManifestSha256':'2'*64,'componentSubsetOfPrimary':True,**({'producerReceiptSha256':'3'*64}if profile=='android4'else{})}
  files[prefix+'/cargo-notices/root/LICENSE']=b'Synthetic root original notice\n';files[prefix+'/cargo-notices/dependency/LICENSE']=b'Synthetic dependency original notice\n';notice={'schema_version':2,'coverage':'complete','uncovered':[],'target_sboms':bom_hashes,'component_refs':sorted([rootref,depref]),'files':[{'path':'root/LICENSE','sha256':h(files[prefix+'/cargo-notices/root/LICENSE']),'owners':[rootref]},{'path':'dependency/LICENSE','sha256':h(files[prefix+'/cargo-notices/dependency/LICENSE']),'owners':[depref]}]};raw=encoded(notice);files[prefix+'/cargo-notices/MANIFEST.json']=raw
  coverage={'schema':1,'coreCommit':revision,'consumer':'guard'if kind=='core'else'sentinel','features':'shipped'if kind=='core'else'sentinel-v2','androidDeliveryProfile':profile,'abis':list(shape.values()),'primaryTarget':next(iter(shape)),'sourceProducerProfile':source_producer if kind=='core'else('android4'if profile=='android4'else'android3'),'authorities':{'producerReceipt':'3'*64,'sourceManifest':'4'*64,'sourceArchive':'5'*64,'captureReceipt':'6'*64,'cargoLockSha256':h(lock)},'targets':targets,'unionComponents':2,'unionEdges':1,'unionComponentRefs':sorted([rootref,depref]),'noticeManifestSha256':h(raw),'coverage':'complete'};files[prefix+'/ABI-COVERAGE.json']=encoded(coverage);files[prefix+'/REVISION.txt']=(revision+'\n').encode()
 files['foxcore/FoxHole-Core-GPL-3.0-or-later.txt']=source['LICENSE'];files['foxcore/THIRD_PARTY_NOTICES.md']=source['THIRD_PARTY_NOTICES.md']
 for n in('LICENSE','README.md','vendor/PROVENANCE.json'):files['sentinel/'+n]=source['native/sentinel-yara/'+n]
 for n in M.RUST_NOTICES:files['foxcore/toolchain/'+n]=(REPO/'third_party/licenses/rust-std-1.97.1'/n).read_bytes()
 files['foxcore/toolchain/PROVENANCE.json']=encoded({'schema':1,'rustVersion':'1.97.1','rustcCommit':'8bab26f4f68e0e26f0bb7960be334d5b520ea452','coreCommit':revision,'androidDeliveryProfile':profile,'nativeLibraries':native,'installedRuntimeArchives':{target:[{'name':'libstd-synthetic.rlib','bytes':1,'sha256':h(b'x')}]for target in shape},'licenseInputs':M.RUST_NOTICES})
 record={'schema':1,'kind':'canonical-source-legal-delivery','coreCommit':revision,'version':'1.0.0','androidDeliveryProfile':profile,'sourceProducerProfile':source_producer,'abis':list(shape.values()),'targets':list(shape),'compiledInputs':compiled,'sentinelInputs':{n:h(raw)for n,raw in source.items()if n.startswith('native/sentinel-yara/')},'locks':{n:h(source[n])for n in M.LOCKS},'nativeLibraries':native,'files':{n:{'sha256':h(raw),'bytes':len(raw)}for n,raw in files.items()}}
 if source_producer in('android64','android4'):record['sourceProducerReceipts']={'core':'3'*64,'sentinel':'3'*64}
 if source_producer=='android4':record['sourceProducerReceiptsByTarget']={kind:{target:'3'*64 for target in shape}for kind in('core','sentinel')}
 for n,raw in files.items():put(snapshot.parent/'payload'/n,raw)
 put(snapshot,encoded(record));return app,core,snapshot,revision,record
class CanonicalSourceLegalTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.app,self.core,self.snapshot,self.revision,self.record=fixture(Path(self.tmp.name));self.profile='android3'
 def tearDown(self):self.tmp.cleanup()
 def load(self,**kwargs):
  args={'snapshot_path':self.snapshot,'app_root':self.app,'core_root':self.core,'revision':self.revision,'profile':self.profile,'core_native':self.app/'app/build/generated/foxCoreNativeLibs','sentinel_native':self.app/'app/build/generated/sentinelNative/jniLibs'};args.update(kwargs);return M.load(**args)
 def save(self):put(self.snapshot,encoded(self.record))
 def member(self,name,value):
  raw=encoded(value)if not isinstance(value,bytes)else value;put(self.snapshot.parent/'payload'/name,raw);self.record['files'][name]={'sha256':h(raw),'bytes':len(raw)};self.save()
 def test_valid_source_and_all_six_native_files_pass(self):
  result=self.load();self.assertEqual(result['coreCommit'],self.revision);self.assertEqual(len(result['nativeLibraries']['core']),3);M.recheck(result,self.core)
 def test_android64_data_subset_is_validated_separately(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android64');result=M.load(snapshot,app,core,revision,'android64',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs');self.assertEqual(len(result['nativeLibraries']['core']),2)
 def test_actual_android64_core_with_original_android3_sentinel_is_valid(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android64','android64')
   result=M.load(snapshot,app,core,revision,'android64',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
   self.assertEqual(len(result['nativeLibraries']['core']),2)
 def test_android64_producer_cannot_claim_android3_delivery(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android3','android64')
   with self.assertRaisesRegex(ValueError,'Canonical producer profile differs'):M.load(snapshot,app,core,revision,'android3',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_android64_source_receipt_mismatch_is_rejected(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,record=fixture(Path(name),'android64','android64');record['sourceProducerReceipts']['core']='f'*64;put(snapshot,encoded(record))
   with self.assertRaisesRegex(ValueError,'source producer receipt join differs'):M.load(snapshot,app,core,revision,'android64',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_android64_core_cannot_retag_original_sentinel_as_android64(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,record=fixture(Path(name),'android64','android64');path=snapshot.parent/'payload/sentinel/ABI-COVERAGE.json';coverage=json.loads(path.read_bytes());coverage['sourceProducerProfile']='android64';raw=encoded(coverage);put(path,raw);record['files']['sentinel/ABI-COVERAGE.json']={'sha256':h(raw),'bytes':len(raw)};put(snapshot,encoded(record))
   with self.assertRaisesRegex(ValueError,'Core/Sentinel producer profile differs'):M.load(snapshot,app,core,revision,'android64',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_android64_core_bom_producer_profile_must_match(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android64','android64','android3')
   with self.assertRaisesRegex(ValueError,'Core inventory producer profile differs'):M.load(snapshot,app,core,revision,'android64',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_armv7_data_subset_is_validated_separately(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android-armv7');result=M.load(snapshot,app,core,revision,'android-armv7',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs');self.assertEqual(len(result['nativeLibraries']['core']),1)
 def test_changed_fresh_jni_is_rejected(self):
  p=self.app/'app/build/generated/foxCoreNativeLibs/armeabi-v7a/libfoxhole_native.so';p.write_bytes(p.read_bytes()+b'changed')
  with self.assertRaisesRegex(ValueError,'digest differs'):self.load()
 def test_missing_full_sentinel_source_member_is_rejected(self):
  self.record['sentinelInputs'].pop('native/sentinel-yara/src/lib.rs');self.save()
  with self.assertRaisesRegex(ValueError,'Whole Sentinel source tree'):self.load()
 def test_wrong_lock_is_rejected(self):
  self.record['locks']['Cargo.lock']='f'*64;self.save()
  with self.assertRaisesRegex(ValueError,'source lock differs'):self.load()
 def test_wrong_profile_is_rejected(self):
  with self.assertRaisesRegex(ValueError,'source/profile identity differs'):self.load(profile='android64')
 def test_missing_target_coverage_is_rejected(self):
  name='foxcore/ABI-COVERAGE.json';value=json.loads((self.snapshot.parent/'payload'/name).read_bytes());value['targets'].pop('armv7-linux-androideabi');self.member(name,value)
  with self.assertRaisesRegex(ValueError,'target coverage incomplete'):self.load()
 def test_changed_original_notice_is_rejected_even_with_updated_file_index(self):
  self.member('foxcore/cargo-notices/dependency/LICENSE',b'Changed original text\n')
  with self.assertRaisesRegex(ValueError,'original notice differs'):self.load()
 def test_operational_receipt_body_cannot_enter_data_snapshot(self):
  self.member('sentinel/producer-receipt.json',b'{"started":"synthetic"}\n')
  with self.assertRaisesRegex(ValueError,'Operational/binary input'):self.load()
 def test_native_output_cannot_be_substituted_with_an_external_directory(self):
  with self.assertRaisesRegex(ValueError,'normal app producer outputs'):self.load(core_native=self.core)
 def test_android4_exact_all_four_profile_passes(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android4','android4');result=M.load(snapshot,app,core,revision,'android4',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs');self.assertEqual(len(result['nativeLibraries']['core']),4)
 def test_android4_cannot_borrow_android64_source_profile(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,_=fixture(Path(name),'android4','android64')
   with self.assertRaisesRegex(ValueError,'producer profile differs'):M.load(snapshot,app,core,revision,'android4',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_android4_missing_x86_producer_ref_is_rejected(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,record=fixture(Path(name),'android4','android4');record['sourceProducerReceiptsByTarget']['core'].pop('i686-linux-android');put(snapshot,encoded(record))
   with self.assertRaisesRegex(ValueError,'per-ABI producer set differs'):M.load(snapshot,app,core,revision,'android4',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_android4_wrong_actual_producer_ref_is_rejected(self):
  with tempfile.TemporaryDirectory()as name:
   app,core,snapshot,revision,record=fixture(Path(name),'android4','android4');record['sourceProducerReceiptsByTarget']['sentinel']['armv7-linux-androideabi']='e'*64;put(snapshot,encoded(record))
   with self.assertRaisesRegex(ValueError,'per-ABI producer receipt join differs'):M.load(snapshot,app,core,revision,'android4',app/'app/build/generated/foxCoreNativeLibs',app/'app/build/generated/sentinelNative/jniLibs')
 def test_input_change_after_validation_is_rejected(self):
  value=self.load();p=self.app/'app/build/generated/sentinelNative/jniLibs/armeabi-v7a/libfoxhole_sentinel_yara.so';p.write_bytes(p.read_bytes()+b'changed')
  with self.assertRaisesRegex(ValueError,'digest differs'):M.recheck(value,self.core)
if __name__=='__main__':unittest.main()
