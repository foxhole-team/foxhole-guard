"""Synthetic validator fixtures only: no ELF execution, build, Git, APK or runtime qualification."""
from pathlib import Path
import copy,hashlib,importlib.util,json,os,shutil,tempfile,unittest

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('normal_native_contract',ROOT/'scripts/normal-core-native.py')
M=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(M)


class NormalNativeContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name).resolve();self.app=self.base/'app';self.native=self.base/'native'
        (self.app/'config').mkdir(parents=True);(self.app/'scripts').mkdir();self.native.mkdir()
        for name in ('foxcore-native-inputs.json','foxcore-source-manifest.json','foxcore-source-checkout.json','foxcore-revision.txt'):
            shutil.copyfile(ROOT/'config'/name,self.app/'config'/name)
        shutil.copyfile(ROOT/'scripts/normal-core-native.py',self.app/'scripts/normal-core-native.py')
        self.cfg=json.loads((self.app/'config/foxcore-native-inputs.json').read_text())
        self.cfg.update(role='vpn',features='vless,wireguard')
        self.cfg['publishedCoreSDK']['containsRoleJNI']=False
        (self.app/'config/foxcore-features.txt').write_text(self.cfg['features']+'\n')
        for abi in M.ABIS:
            body=bytearray(64);body[:4]=b'\x7fELF';cls,machine={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi]
            body[4]=cls;body[5]=1;body[18:20]=machine.to_bytes(2,'little')
            target=self.native/abi/'libfoxhole_native.so';target.parent.mkdir();target.write_bytes(body)
            self.cfg['libraries'][abi]={'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()}
        self.sync()

    def save_cfg(self):
        (self.app/'config/foxcore-native-inputs.json').write_text(json.dumps(self.cfg))

    def sync(self):
        self.role={'schema':1,'kind':'ROOT_NORMALIZED_ACTUAL_C3_NORMAL_BUILDER_ROLE_ONLY','role':self.cfg['role'],
            'features':self.cfg['features'],'producerCommit':self.cfg['producerCommit'],'producerTree':self.cfg['producerTree'],
            'version':'1.0.4','ABIs':M.ABIS,'libraries':copy.deepcopy(self.cfg['libraries']),
            'normalBuilderAndELFGatePassed':True,'exitCode':0,'sourceFenceExitCode':0,'runtimeOrAPKQualification':False,
            'fixture':'SYNTHETIC_NOT_PRODUCER_OR_RUNTIME_EVIDENCE'}
        raw=json.dumps(self.role).encode();(self.native/'NORMAL-ROLE.json').write_bytes(raw)
        self.cfg['normalRoleReceiptSHA256']=hashlib.sha256(raw).hexdigest();self.save_cfg()

    def verify(self,role='vpn'):
        return M.verify(self.app,self.native,role,['arm64-v8a'])

    def test_distinct_roles_verify_all_four_abis_and_requested_subset(self):
        cfg,selected=self.verify();self.assertEqual(['arm64-v8a'],[x[0]for x in selected]);self.assertFalse(cfg['publishedCoreSDK']['containsRoleJNI'])
        self.cfg.update(role='guard',features='shipped');self.cfg['publishedCoreSDK']['containsRoleJNI']=True;self.sync()
        cfg,selected=self.verify('guard');self.assertTrue(cfg['publishedCoreSDK']['containsRoleJNI'])

    def test_guard_receipt_cannot_be_used_for_vpn(self):
        self.role.update(role='guard',features='shipped')
        (self.native/'NORMAL-ROLE.json').write_text(json.dumps(self.role))
        with self.assertRaises(ValueError):self.verify()

    def test_public_sdk_guard_role_cannot_be_claimed_as_vpn(self):
        self.cfg['publishedCoreSDK']['containsRoleJNI']=True;self.save_cfg()
        with self.assertRaises(ValueError):self.verify()

    def test_tampering_an_unrequested_abi_is_refused(self):
        (self.native/'x86/libfoxhole_native.so').write_bytes(b'changed')
        with self.assertRaises(ValueError):self.verify()

    def test_missing_extra_and_wrong_machine_abis_are_refused(self):
        extra=self.native/'arm64-v8a/extra.so';extra.write_bytes(b'not allowed')
        with self.assertRaises(ValueError):self.verify()
        extra.unlink();target=self.native/'x86/libfoxhole_native.so';body=bytearray(target.read_bytes());body[18:20]=(183).to_bytes(2,'little');target.write_bytes(body)
        self.cfg['libraries']['x86']['sha256']=hashlib.sha256(body).hexdigest();self.sync()
        with self.assertRaisesRegex(ValueError,'ABI'):self.verify()

    def test_hardlinks_and_symlink_receipts_are_refused(self):
        target=self.native/'x86/libfoxhole_native.so';os.link(target,self.base/'alias')
        with self.assertRaises(ValueError):self.verify()
        (self.base/'alias').unlink();original=self.native/'NORMAL-ROLE.json';moved=self.base/'moved.json';original.rename(moved);original.symlink_to(moved)
        with self.assertRaises(ValueError):self.verify()

    def test_duplicate_nonfinite_and_unknown_new_config_are_refused(self):
        path=self.app/'config/foxcore-native-inputs.json';valid=path.read_text()
        for body in ('{"schema":1,"schema":1}', '{"schema":NaN}',json.dumps({**self.cfg,'extra':True})):
            path.write_text(body)
            with self.assertRaises(ValueError):self.verify()
        path.write_text(valid)

    def test_publisher_cannot_replace_binary_producer(self):
        self.cfg['producerCommit']=self.cfg['sourceCommit'];self.save_cfg()
        with self.assertRaises(ValueError):self.verify()

    def test_generic_reader_remains_one_mib(self):
        path=self.base/'large';path.write_bytes(b'x'*(1048576+1))
        with self.assertRaises(ValueError):M.read(path)

    def test_copy_replaces_generated_entry_without_mutating_retained_hardlink(self):
        old=self.base/'retained-old';old.write_bytes(b'old-qualified-binary-fixture')
        destination=self.base/'generated/libfoxhole_native.so';destination.parent.mkdir();os.link(old,destination)
        M.copy_verified_library(self.native/'arm64-v8a/libfoxhole_native.so',destination,self.cfg['libraries']['arm64-v8a'])
        self.assertEqual(b'old-qualified-binary-fixture',old.read_bytes())
        self.assertNotEqual(old.stat().st_ino,destination.stat().st_ino)
        self.assertEqual(1,destination.stat().st_nlink)


if __name__=='__main__':unittest.main()
