"""Synthetic byte fixtures test source binding only, never native execution."""
from pathlib import Path
import copy,hashlib,importlib.util,json,os,sys,tempfile,unittest

sys.dont_write_bytecode=True
P=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('proposed_current_source',P/'scripts/current-source-legal-consumer.py')
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)

class FreshSourceBindingTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='guard-source-native-unit-');self.addCleanup(self.tmp.cleanup)
        self.app=Path(self.tmp.name).resolve()
        self.core=self.app/'app/build/fdroidNativeSource/core'
        self.sentinel=self.app/'app/build/fdroidNativeSource/sentinel/jniLibs'
        self.manifest=self.app/'app/build/reports/fdroid/native-source-build.json'
        native={}
        for kind,root,name in [('core',self.core,'libfoxhole_native.so'),('sentinel',self.sentinel,'libfoxhole_sentinel_yara.so')]:
            native[kind]={}
            for target,abi in M.TARGETS.items():
                bits,machine={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi]
                raw=bytearray(64);raw[:4]=b'\x7fELF';raw[4]=bits;raw[5]=1;raw[18:20]=machine.to_bytes(2,'little')
                path=root/abi/name;path.parent.mkdir(mode=0o700,parents=True);path.write_bytes(raw);path.chmod(0o600)
                native[kind][target]={'abi':abi,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        self.record={'schema':1,'kind':'normal-fdroid-native-source-build','sourceCommit':M.CHECKOUT,
                     'sourceTree':M.CHECKOUT_TREE,'referenceProducerCommit':M.CORE,
                     'compiledSourceManifestSha256':M.COMPILED,'androidDeliveryProfile':'android4',
                     'abis':list(M.TARGETS.values()),'targets':list(M.TARGETS),'nativeLibraries':native}
        self.save()

    def save(self):
        self.manifest.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        self.manifest.write_text(json.dumps(self.record));self.manifest.chmod(0o600)

    def load(self):return M.source_build_provenance(self.manifest,self.app,self.core,self.sentinel)

    def test_exact_four_abi_source_record_and_byte_hashes(self):self.assertEqual(self.load(),self.record)

    def test_wrong_checkout(self):
        self.record['sourceCommit']='0'*40;self.save()
        with self.assertRaises(ValueError):self.load()

    def test_wrong_reference_producer(self):
        self.record['referenceProducerCommit']='0'*40;self.save()
        with self.assertRaises(ValueError):self.load()

    def test_omitted_x86(self):
        del self.record['nativeLibraries']['core']['i686-linux-android'];self.save()
        with self.assertRaises(ValueError):self.load()

    def test_wrong_machine_even_when_manifest_hash_matches(self):
        p=self.core/'x86/libfoxhole_native.so';raw=bytearray(p.read_bytes());raw[18:20]=(183).to_bytes(2,'little');p.write_bytes(raw)
        self.record['nativeLibraries']['core']['i686-linux-android']['sha256']=hashlib.sha256(raw).hexdigest();self.save()
        with self.assertRaises(ValueError):self.load()

    def test_changed_native_body(self):
        p=self.sentinel/'arm64-v8a/libfoxhole_sentinel_yara.so';p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaises(ValueError):self.load()

    def test_foreign_output_root(self):
        with self.assertRaises(ValueError):M.source_build_provenance(self.manifest,self.app,self.app/'foreign',self.sentinel)

    def test_extra_library(self):
        (self.core/'x86/extra.so').write_bytes(b'foreign')
        with self.assertRaises(ValueError):self.load()

    def test_boolean_byte_count(self):
        self.record['nativeLibraries']['core']['i686-linux-android']['bytes']=True;self.save()
        with self.assertRaises(ValueError):self.load()

    def test_manifest_outside_fixed_path(self):
        p=self.app/'other.json';p.write_text(json.dumps(self.record));p.chmod(0o600)
        with self.assertRaises(ValueError):M.source_build_provenance(p,self.app,self.core,self.sentinel)

    def test_linked_native_file(self):
        p=self.core/'x86/libfoxhole_native.so';other=self.app/'outside';other.write_bytes(p.read_bytes());p.unlink();p.symlink_to(other)
        with self.assertRaises(ValueError):self.load()

if __name__=='__main__':
    os.umask(0o077)
    unittest.main()
