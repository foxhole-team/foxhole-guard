"""Synthetic consumer-policy fixtures; no native execution or build qualification."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
M=module('retained_sentinel_fixture',SCRIPTS/'retained-sentinel-role.py')
G=module('retained_generator_fixture',SCRIPTS/'generate-license-assets.py')


class RetainedSentinelRoleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);(self.root/'config').mkdir()
        self.blobs={n:('synthetic '+n).encode() for n in M.FIXED|{'native/sentinel-yara/Cargo.lock'}}
        self.files={n:M.sha(v)for n,v in self.blobs.items()}
        proofs={n:M.sha(n.encode())for n in M.PROOFS}
        self.libraries={'lib/'+abi+'/libfoxhole_sentinel_yara.so':('original-sentinel-'+abi).encode()for abi in M.ABIS}
        self.libraries.update({'lib/'+abi+'/libfoxhole_native.so':('current-core-'+abi).encode()for abi in M.ABIS})
        original=[{'path':abi+'/libfoxhole_sentinel_yara.so','sha256':M.sha(('original-sentinel-'+abi).encode()),'bytes':len(('original-sentinel-'+abi).encode())}for abi in M.FOUR.values()]
        current=[{'path':abi+'/libfoxhole_native.so','sha256':M.sha(self.libraries['lib/'+abi+'/libfoxhole_native.so']),'bytes':len(self.libraries['lib/'+abi+'/libfoxhole_native.so'])}for abi in M.ABIS]
        self.notices={};rows={}
        root={'type':'library','name':'foxhole-sentinel-yara','version':'0.1.0','bom-ref':'sentinel-root','purl':'pkg:generic/foxhole/foxhole-sentinel-yara@0.1.0?core_commit='+M.EA}
        for target,abi in M.FOUR.items():
            children=[{'type':'library','name':'common','version':'1','bom-ref':'common','purl':'pkg:cargo/common@1'}]
            if abi=='x86_64':children.append({'type':'library','name':'x64-only','version':'1','bom-ref':'x64-only','purl':'pkg:cargo/x64-only@1'})
            if abi=='x86':children.append({'type':'library','name':'x32-only','version':'1','bom-ref':'x32-only','purl':'pkg:cargo/x32-only@1'})
            doc={'metadata':{'component':root,'properties':[{'name':'foxhole:native:core-revision','value':M.EA}]},'components':children,
                 'dependencies':[{'ref':'sentinel-root','dependsOn':[c['bom-ref']for c in children]}]+[{'ref':c['bom-ref'],'dependsOn':[]}for c in children]}
            raw=M.encoded(doc);self.notices['sentinel/selected-'+target+'.cdx.json']=raw
            row=next(r for r in original if r['path']==abi+'/libfoxhole_sentinel_yara.so')
            rows[target]={'deliverySbomSha256':M.sha(raw),'nativeLibrarySha256':row['sha256']}
        proofs['sentinel_selected_sbom']=rows['aarch64-linux-android']['deliverySbomSha256']
        legal=b'Synthetic original-notice fixture, not an upstream license claim.'
        self.notices['sentinel/cargo-notices/fixture.txt']=legal
        union=M.encoded({'files':[{'path':'fixture.txt','sha256':M.sha(legal)}]});self.notices['sentinel/cargo-notices/MANIFEST.json']=union
        coverage=M.encoded({'coreCommit':M.EA,'targets':rows,'noticeManifestSha256':M.sha(union),'authorities':{
            'producerReceipt':proofs['sentinel_producer'],'sourceManifest':proofs['sentinel_source_manifest'],'captureReceipt':proofs['sentinel_capture']}})
        self.notices['sentinel/ABI-COVERAGE.json']=coverage
        self.config={'schema':1,'kind':'pinned-retained-original-sentinel-authority','status':'BOUND_ACTUAL_ROOT_ROLE','originalCoreCommit':M.EA,'consumerCoreCommit':M.CORE,
            'androidDeliveryProfile':'android64','abis':M.ABIS,'targets':M.TARGETS,'originalProofs':proofs,
            'currentProducerReceiptSha256':M.sha(b'actual-current-producer-fixture'),'currentSourceManifestSha256':M.sha(b'full-current-source-fixture'),
            'currentStageReceiptSha256':M.sha(b'actual-current-stage-fixture'),'currentSourceArchiveSha256':M.sha(b'current-archive-fixture'),
            'unchangedInputFileCount':len(self.files),'unchangedInputFilesSha256':M.sha(M.encoded(self.files)),
            'originalLibraries':original,'currentLibraries':current,'originalCoverageSha256':M.sha(coverage),'originalNoticeUnionSha256':M.sha(union)}
        self.role={k:copy.deepcopy(self.config[k])for k in ('originalCoreCommit','consumerCoreCommit','androidDeliveryProfile','abis','originalProofs',
            'currentProducerReceiptSha256','currentSourceManifestSha256','currentStageReceiptSha256','currentSourceArchiveSha256','originalLibraries')}
        self.role.update(schema=1,kind='retained-original-sentinel-current-source-input-equivalence',unchangedInputFiles=self.files,
            selectedLibraryPaths=[abi+'/libfoxhole_sentinel_yara.so'for abi in M.ABIS],newNativeBuildClaimed=False,oldJvmResultClaimed=False)
        self.current_coverage={'authorities':{'producerReceipt':self.config['currentProducerReceiptSha256'],
            'sourceManifest':self.config['currentSourceManifestSha256'],'sourceArchive':self.config['currentSourceArchiveSha256']}}
        self.bind()

    def bind(self):
        raw=M.encoded(self.role);self.config['roleSha256']=M.sha(raw);config_raw=M.encoded(self.config)
        (self.root/'config/sentinel-retained-authority.json').write_bytes(config_raw)
        self.notices['sentinel/RETAINED-ROLE.json']=raw
        self.notices['sentinel/RETAINED-CONSUMER.json']=M.consumer_binding(self.role,self.config,config_raw)

    def packaged(self):
        return M.packaged(self.root,M.CORE,'android64',self.notices.__getitem__,self.libraries.__getitem__,self.current_coverage)

    def test_two_selected_original_graphs_keep_EA_identity_and_x64_only_dependency(self):
        result,role_sha=self.packaged();refs={x['bom-ref']for x in result['components']}
        self.assertIn('x64-only',refs);self.assertNotIn('x32-only',refs)
        self.assertIn(M.EA,result['metadata']['component']['purl']);self.assertEqual(role_sha,self.config['roleSha256'])

    def test_actual_new_core_jni_must_match_even_when_role_and_coverage_are_unchanged(self):
        self.libraries['lib/x86_64/libfoxhole_native.so']=b'wrong-current-Core'
        with self.assertRaisesRegex(ValueError,'actual new Guard'):self.packaged()

    def test_original_x64_sentinel_slice_must_match(self):
        self.libraries['lib/x86_64/libfoxhole_sentinel_yara.so']=b'wrong-retained-Sentinel'
        with self.assertRaisesRegex(ValueError,'Sentinel slice'):self.packaged()

    def test_role_removal_or_changed_role_with_fresh_internal_binding_is_refused(self):
        self.role['newNativeBuildClaimed']=True;self.bind()
        with self.assertRaisesRegex(ValueError,'build claim'):self.packaged()
        del self.notices['sentinel/RETAINED-ROLE.json']
        with self.assertRaises(KeyError):self.packaged()

    def test_stripped_profile_is_refused_after_role_hash_is_updated(self):
        del self.role['androidDeliveryProfile'];self.bind()
        with self.assertRaisesRegex(ValueError,'source identity'):self.packaged()

    def test_current_core_producer_cannot_be_substituted_by_original_sentinel_producer(self):
        self.current_coverage['authorities']['producerReceipt']=self.config['originalProofs']['sentinel_producer']
        with self.assertRaisesRegex(ValueError,'current Core'):self.packaged()

    def test_original_four_target_coverage_and_notice_bytes_remain_pinned(self):
        self.notices['sentinel/ABI-COVERAGE.json']=b'changed'
        with self.assertRaisesRegex(ValueError,'four-target'):self.packaged()

    def test_draft_or_missing_independent_config_fails_closed(self):
        self.config['status']='DRAFT_PENDING_ACTUAL_ROOT_ROLE';self.bind()
        with self.assertRaisesRegex(ValueError,'draft'):self.packaged()

    def test_full_projection_rejects_missing_lock_changed_git_and_extra_sentinel_source(self):
        names=lambda:[n for n in self.files if n.startswith('native/sentinel-yara/')]
        M.validate_projection(self.role,self.files,self.files,self.blobs.__getitem__,names)
        for change in ('missing','changed','extra'):
            files=dict(self.files);blobs=dict(self.blobs)
            if change=='missing':del files['native/sentinel-yara/Cargo.lock']
            if change=='changed':blobs['native/sentinel-yara/Cargo.lock']=b'other-lock'
            if change=='extra':files['native/sentinel-yara/new.rs']=M.sha(b'new')
            with self.assertRaises(ValueError):M.validate_projection(self.role,self.files,files,blobs.__getitem__,names)

    def test_generator_cannot_fall_back_to_legacy_when_current_role_flags_are_stripped(self):
        with self.assertRaisesRegex(ValueError,'explicit original Sentinel role'):
            G.copy_sentinel_material(self.root,SimpleNamespace(),self.root,M.CORE)

    def test_duplicate_role_members_are_refused(self):
        with self.assertRaisesRegex(ValueError,'Duplicate'):M.read_json(b'{"schema":1,"schema":1}')


if __name__=='__main__':unittest.main()
