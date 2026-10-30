"""Compiled manifests follow the intended Git closure; counts cannot hide new inputs."""
from pathlib import Path
import hashlib,importlib.util,json,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('dynamic_core_binding',ROOT/'scripts/core-source-binding.py');M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
class DynamicCoreBindingTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve();self.core=self.root/'core';self.app=self.root/'app';self.producer='b'*40;self.names=sorted(M.FIXED|{'fingerprints/'+str(i)+'.json'for i in range(9)}|{'crates/native/src/lib.rs','crates/native/src/lease.rs'})
  for name in self.names:
   path=self.core/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(name+'\n')
  (self.app/'config').mkdir(parents=True);self.save(self.names)
 def tearDown(self):self.tmp.cleanup()
 def save(self,names):
  rows=[{'path':n,'sha256':hashlib.sha256((self.core/n).read_bytes()).hexdigest()}for n in names];raw=(json.dumps({'commit':self.producer,'files':rows})+'\n').encode();(self.app/'config/foxcore-source-manifest.json').write_bytes(raw)
  (self.app/'config/foxcore-source-checkout.json').write_text(json.dumps({'schema':1,'sourceCommit':'a'*40,'sourceTree':'c'*40,'producerCommit':self.producer,'compiledSourceManifestSha256':hashlib.sha256(raw).hexdigest()}))
 def git(self,root,*args):
  if args==('rev-parse','HEAD'):return('a'*40+'\n').encode()
  if args==('rev-parse','HEAD^{tree}'):return('c'*40+'\n').encode()
  if args[0]=='status':return b''
  if args[0]=='ls-tree':return('\n'.join(self.names)+'\n').encode()
  raise AssertionError(args)
 def resolve(self):return M.resolve(self.app,self.core,self.producer,self.git)
 def test_current_exact_git_closure_passes_without_historical_count(self):self.assertEqual(len(self.names),self.resolve()['producerDeclaredInputCount'])
 def test_missing_declared_new_source_is_refused_even_with_rehashed_binding(self):
  self.save([n for n in self.names if n!='crates/native/src/lease.rs'])
  with self.assertRaisesRegex(ValueError,'Git-derived'):self.resolve()
 def test_added_git_source_cannot_escape_the_manifest(self):
  self.names.append('crates/native/src/new.rs');path=self.core/self.names[-1];path.write_text('// new source\n')
  with self.assertRaisesRegex(ValueError,'Git-derived'):self.resolve()
 def test_changed_source_bytes_remain_refused(self):
  (self.core/'crates/native/src/lease.rs').write_text('// changed source\n')
  with self.assertRaisesRegex(ValueError,'input differs'):self.resolve()
if __name__=='__main__':unittest.main()
