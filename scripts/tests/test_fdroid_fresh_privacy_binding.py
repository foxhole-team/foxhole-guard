"""Synthetic ELF fixtures exercise receipt validation, not native production."""
from pathlib import Path
import copy,hashlib,importlib.util,json,os,shutil,sys,unittest

sys.dont_write_bytecode=True
H=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(H))
import test_fdroid_fresh_source_native_binding as native_fixture

spec=importlib.util.spec_from_file_location('privacy_binding_proposal',H/'scripts/native-source-license-material.py')
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
GUARD=H

class FreshPrivacyBindingTest(unittest.TestCase):
    def setUp(self):
        native_fixture.FreshSourceBindingTest.setUp(self)
        helper=self.app/'scripts/current-source-legal-consumer.py'
        helper.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        shutil.copyfile(H/'scripts/current-source-legal-consumer.py',helper)
        helper.chmod(0o600)
        self.source={}
        for name in M.SOURCE_KEYS:
            p=self.app/name;p.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
            raw=(GUARD/name).read_bytes();p.write_bytes(raw);p.chmod(0o600)
            self.source[name]=hashlib.sha256(raw).hexdigest()
        self.privacy=self.app/'app/build/reports/fdroid/privacy-source-build.json'
        native=[]
        for abi in M.ABIS:
            for name in ['lyrebird','conjure-client','libi2pd.so']:
                bits,machine={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi]
                raw=bytearray(64);raw[:4]=b'\x7fELF';raw[4]=bits;raw[5]=1;raw[18:20]=machine.to_bytes(2,'little')
                p=M.asset_path(self.app,abi,name);p.parent.mkdir(mode=0o700,parents=True,exist_ok=True);p.write_bytes(raw);p.chmod(0o600)
                native.append({'abi':abi,'name':name,'bytes':64,'sha256':hashlib.sha256(raw).hexdigest()})
        self.reference='9'*64
        self.privacy_record={'schema':1,'kind':'normal-fdroid-privacy-source-build',
            'coreNativeManifestSHA256':hashlib.sha256(self.manifest.read_bytes()).hexdigest(),
            'referenceMaterialManifestSHA256':self.reference,'referenceProducerReceipts':M.PRODUCERS,
            'sourceInputs':self.source,'abis':list(M.ABIS),'nativeLibraries':native}
        self.save_privacy()

    def save(self):native_fixture.FreshSourceBindingTest.save(self)

    def save_privacy(self):
        self.privacy.write_text(json.dumps(self.privacy_record));self.privacy.chmod(0o600)

    def validate(self):
        self.before={}
        return M.source_privacy_provenance(self.manifest,self.app,self.core,self.sentinel,self.reference,self.source,self.before)

    def full_material(self):
        reference=H/'third_party/licenses/native-source-reference'
        target=self.app/'reference-material'
        shutil.copytree(reference,target)
        self.reference=hashlib.sha256((target/'manifest.json').read_bytes()).hexdigest()
        self.privacy_record['referenceMaterialManifestSHA256']=self.reference;self.save_privacy()
        binding=self.app/'config/native-source-material-bindings.json'
        binding.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        shutil.copyfile(GUARD/'config/native-source-material-bindings.json',binding);binding.chmod(0o600)
        return target

    def load_material(self,target):
        return M.load(target/'manifest.json',target/'payload',self.app,M.ABIS,
                      self.manifest,self.core,self.sentinel)

    def test_exact_source_jni_and_twelve_privacy_assets(self):
        self.assertEqual(self.validate(),self.privacy_record)
        self.assertGreaterEqual(len(self.before),22)

    def test_wrong_reference_manifest(self):
        self.privacy_record['referenceMaterialManifestSHA256']='0'*64;self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_wrong_reference_producer(self):
        self.privacy_record['referenceProducerReceipts']={**M.PRODUCERS,'go':'0'*64};self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_changed_source_input(self):
        p=self.app/'scripts/build-tor-transports.sh';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaises(ValueError):self.validate()

    def test_changed_jni_even_when_privacy_receipt_unchanged(self):
        p=self.core/'x86/libfoxhole_native.so';p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaises(ValueError):self.validate()

    def test_missing_abi_and_duplicate_product(self):
        self.privacy_record['nativeLibraries'][-1]=self.privacy_record['nativeLibraries'][0];self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_changed_privacy_bytes(self):
        p=M.asset_path(self.app,'x86','lyrebird');p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaises(ValueError):self.validate()

    def test_wrong_elf_machine_even_with_updated_hash(self):
        p=M.asset_path(self.app,'x86','lyrebird');raw=bytearray(p.read_bytes());raw[18:20]=(183).to_bytes(2,'little');p.write_bytes(raw)
        row=next(r for r in self.privacy_record['nativeLibraries']if(r['abi'],r['name'])==('x86','lyrebird'))
        row['sha256']=hashlib.sha256(raw).hexdigest();self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_wrong_core_manifest_identity(self):
        self.privacy_record['coreNativeManifestSHA256']='0'*64;self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_boolean_size(self):
        self.privacy_record['nativeLibraries'][0]['bytes']=True;self.save_privacy()
        with self.assertRaises(ValueError):self.validate()

    def test_linked_privacy_asset(self):
        p=M.asset_path(self.app,'x86','lyrebird');other=self.app/'foreign';other.write_bytes(p.read_bytes());p.unlink();p.symlink_to(other)
        with self.assertRaises(ValueError):self.validate()

    def test_full_source_mode_keeps_reference_originals_and_labels_fresh_bytes(self):
        target=self.full_material();material=self.load_material(target)
        self.assertEqual(len(material['files']),183)
        self.assertEqual(material['sourcePrivacyProvenance'],self.privacy_record)
        descriptor=json.loads(material['files']['FDROID-SOURCE-PRIVACY.json'])
        self.assertFalse(descriptor['referenceNativeBytesClaimedAsFresh'])
        M.recheck(material)

    def test_recheck_preserves_large_admitted_core_and_sentinel_roles(self):
        for kind, root, name in [('core', self.core, 'libfoxhole_native.so'),
                                 ('sentinel', self.sentinel, 'libfoxhole_sentinel_yara.so')]:
            for row in self.record['nativeLibraries'][kind].values():
                path = root / row['abi'] / name
                body = path.read_bytes() + b'\0' * 1048576
                path.write_bytes(body)
                row.update(bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
        self.save()
        self.privacy_record['coreNativeManifestSHA256'] = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        self.save_privacy()
        target = self.full_material()
        M.recheck(self.load_material(target))

    def test_metadata_name_cannot_select_a_native_read_bound(self):
        path = self.app / 'metadata' / 'libi2pd.so'
        path.parent.mkdir(mode=0o700)
        body = b'x' * (1048576 + 1)
        path.write_bytes(body)
        path.chmod(0o600)
        empty = self.app / 'empty-payload'
        empty.mkdir(mode=0o700)
        before = {}
        with self.assertRaises(ValueError):
            M.remember(before, path, body)
            M.recheck({'payloadRoot': empty, 'payloadFiles': (), 'before': before})

    def test_native_read_bound_still_refuses_oversize_without_reading_body(self):
        path = self.app / 'oversize-native.so'
        with path.open('wb') as stream:
            stream.truncate(128 * 1048576 + 1)
        path.chmod(0o600)
        with self.assertRaises(ValueError):
            M.read(path, 128 * 1048576, native=True)

    def test_normal_mode_rejects_source_privacy_without_explicit_manifest(self):
        target=self.full_material()
        with self.assertRaises(ValueError):
            M.load(target/'manifest.json',target/'payload',self.app,M.ABIS)

    def test_recheck_rejects_changed_fresh_jni(self):
        target=self.full_material();material=self.load_material(target)
        p=self.core/'x86/libfoxhole_native.so';p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaises(ValueError):M.recheck(material)

    def test_recheck_rejects_changed_fresh_privacy_receipt(self):
        target=self.full_material();material=self.load_material(target)
        self.privacy.write_bytes(self.privacy.read_bytes()+b'\n')
        with self.assertRaises(ValueError):M.recheck(material)

    def test_source_build_writer_emits_the_validated_actual_identity(self):
        target=self.full_material()
        canonical=self.app/'third_party/licenses/native-source-reference'
        canonical.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        shutil.move(target,canonical)
        shutil.copyfile(H/'scripts/native-source-license-material.py',self.app/'scripts/native-source-license-material.py')
        helper_path=H/'scripts/fdroid-source-build.py'
        spec=importlib.util.spec_from_file_location('privacy_source_writer_unit',helper_path)
        helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
        self.privacy.unlink()
        result=helper.guard_privacy_source_manifest(self.app,self.manifest)
        self.assertEqual(result,self.privacy)
        self.assertEqual(json.loads(result.read_bytes()),self.privacy_record)

    def test_source_build_writer_refuses_existing_receipt(self):
        target=self.full_material()
        canonical=self.app/'third_party/licenses/native-source-reference'
        canonical.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        shutil.move(target,canonical)
        shutil.copyfile(H/'scripts/native-source-license-material.py',self.app/'scripts/native-source-license-material.py')
        spec=importlib.util.spec_from_file_location('privacy_existing_writer_unit',H/'scripts/fdroid-source-build.py')
        helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
        with self.assertRaises(ValueError):helper.guard_privacy_source_manifest(self.app,self.manifest)

if __name__=='__main__':
    os.umask(0o077);unittest.main()
