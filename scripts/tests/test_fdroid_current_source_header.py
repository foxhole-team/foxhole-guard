from pathlib import Path
import hashlib,importlib.util,json,os,sys,tempfile,unittest

sys.dont_write_bytecode=True
P=Path(__file__).resolve().parents[2]
Q=P
SOURCE=P

class CurrentHeaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='guard181-header-');self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve()
        files=['config/foxcore-revision.txt','config/foxcore-source-manifest.json',
               'config/foxcore-source-checkout.json','config/foxcore-android-delivery.json',
               'config/source-legal-consumer-bindings.json','scripts/core-source-binding.py',
               'third_party/licenses/current-core-snt/android4/snapshot.json']
        for name in files:self.write(name,(SOURCE/name).read_bytes())
        for name in ['scripts/current-source-legal-consumer.py','scripts/fdroid-source-build.py']:
            self.write(name,(Q/name).read_bytes())
        spec=importlib.util.spec_from_file_location('current_guard_header_fixture',self.root/'scripts/fdroid-source-build.py')
        self.helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.helper)

    def write(self,name,data):
        p=self.root/name;p.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        p.write_bytes(data);p.chmod(0o600)

    def mutate_record(self,fn):
        path='third_party/licenses/current-core-snt/android4/snapshot.json'
        value=json.loads((self.root/path).read_text());fn(value)
        raw=(json.dumps(value,sort_keys=True,indent=2)+'\n').encode();self.write(path,raw)
        name='config/source-legal-consumer-bindings.json';anchor=json.loads((self.root/name).read_text())
        anchor['snapshotSHA256']=hashlib.sha256(raw).hexdigest();self.write(name,json.dumps(anchor).encode())

    def test_actual_current104_header_admitted_without_native_acceptance(self):
        ctx=self.helper.headers(self.root)
        self.assertEqual(ctx['producer'],'3fc3b70d8e9d700b3e07ec27638515c00651b013')
        self.assertEqual(ctx['snapshot']['version'],'1.0.4')
        self.assertEqual(ctx['snapshotPath'],self.root/'third_party/licenses/current-core-snt/android4/snapshot.json')

    def test_missing_reference_target_rejected_even_with_updated_snapshot_pin(self):
        self.mutate_record(lambda x:x['core']['nativeLibraries'].pop('i686-linux-android'))
        with self.assertRaises(ValueError):self.helper.headers(self.root)

    def test_different_producer_rejected_even_with_updated_snapshot_pin(self):
        self.mutate_record(lambda x:x['source'].update(producerCommit='0'*40))
        with self.assertRaises(ValueError):self.helper.headers(self.root)

    def test_wrong_join_rejected_even_with_updated_snapshot_pin(self):
        self.mutate_record(lambda x:x['inputJoinSHA256'].update(coreC3='0'*64))
        with self.assertRaises(ValueError):self.helper.headers(self.root)

    def test_unpinned_snapshot_mutation_rejected(self):
        p=self.root/'third_party/licenses/current-core-snt/android4/snapshot.json';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaises(ValueError):self.helper.headers(self.root)

if __name__=='__main__':
    os.umask(0o077)
    unittest.main()
