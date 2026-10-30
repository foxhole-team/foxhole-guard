#!/usr/bin/env python3
"""Keep a pinned delivery checkout separate from the unchanged compiled producer."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,os,re,stat,subprocess
FIELDS={'schema','sourceCommit','sourceTree','producerCommit','compiledSourceManifestSha256'}
FIXED={'.cargo/config.toml','Cargo.toml','Cargo.lock','rust-toolchain.toml','scripts/android-build.sh','scripts/android-elf-gate.sh','scripts/android-path-leaks.py','config/foxcore-android-delivery.json','config/source-forks/rustls-0.23.46.json','config/source-forks/rustls-0.23.46.upstream-files.json','config/source-forks/rustls-0.23.46.NOTICE.md','patches/rustls-0.23.46-chrome151.patch'}
ROOTS=('crates/','fingerprints/','fixtures/','vendor/rustls/')
# The historical T producer predates one separately fenced integration test.
SOURCE_ONLY_TEST_INPUTS={'crates/foxcore-tun/tests/tun_descriptor_lifetime.rs':'a8caaf8265f3984d1f298d9b62672f3335a1f479761f7dbbaf56bc5d179c9e86'}
F=('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')
def need(value,message):
 if not value:raise ValueError(message)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def source_read(path):
 path=Path(path);need(not any(p.is_symlink()for p in(path,*path.parents)),'Core source links refused');s=path.lstat();pa=path.parent.lstat();facts=lambda x:tuple(getattr(x,k)for k in F)
 need(stat.S_ISREG(s.st_mode)and s.st_nlink==1 and not s.st_mode&0o022 and 0<s.st_size<=1048576,'Bounded regular Core source required');fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
 try:need(facts(os.fstat(fd))==facts(s),'Core source inode changed');raw=os.read(fd,s.st_size+1);need(len(raw)==s.st_size and facts(os.fstat(fd))==facts(s),'Core source changed')
 finally:os.close(fd)
 need(facts(path.lstat())==facts(s)and facts(path.parent.lstat())==facts(pa),'Core source metadata changed');return raw
def validate(binding,compiled_raw,producer):
 need(isinstance(binding,dict)and set(binding)==FIELDS and type(binding.get('schema'))is int and binding['schema']==1,'Exact Core checkout binding required')
 for key in('sourceCommit','sourceTree','producerCommit'):need(isinstance(binding[key],str)and re.fullmatch('[0-9a-f]{40}',binding[key]),'Full Core commit/tree required')
 need(binding['producerCommit']==producer and digest(compiled_raw)==binding['compiledSourceManifestSha256'],'Core producer/source-manifest binding differs');compiled=json.loads(compiled_raw);rows=compiled.get('files');need(isinstance(rows,list),'Compiled Core manifest required');mapping={r['path']:r['sha256']for r in rows}
 need(0<len(rows)==len(mapping)<=4096 and compiled.get('commit')==producer,'Exact compiled producer inventory required')
 for name,pin in mapping.items():
  p=PurePosixPath(name);need(isinstance(name,str)and p.as_posix()==name and not p.is_absolute()and'..'not in p.parts and isinstance(pin,str)and re.fullmatch('[0-9a-f]{64}',pin),'Safe compiled Core input required')
 return mapping
def git(root,*args):
 env={**os.environ,'GIT_OPTIONAL_LOCKS':'0'};r=subprocess.run(['git','-C',str(root),*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=30);need(r.returncode==0 and len(r.stdout)<=1048576 and len(r.stderr)<=1048576,'Pinned Core Git metadata unavailable');return r.stdout
def resolve(app_root,core_root,producer,git_reader=git):
 app_root,core_root=Path(app_root),Path(core_root);path=app_root/'config/foxcore-source-checkout.json'
 if not path.exists():return{'sourceCommit':producer,'sourceTree':None,'producerCommit':producer,'legacy':True}
 binding=json.loads(source_read(path));mapping=validate(binding,source_read(app_root/'config/foxcore-source-manifest.json'),producer)
 need(git_reader(core_root,'rev-parse','HEAD').decode().strip()==binding['sourceCommit'],'Core checkout commit differs');need(git_reader(core_root,'rev-parse','HEAD^{tree}').decode().strip()==binding['sourceTree'],'Core checkout tree differs');need(not git_reader(core_root,'status','--porcelain=v1','--untracked-files=normal').strip(),'Core checkout is not clean')
 source_only=SOURCE_ONLY_TEST_INPUTS if producer=='543efe47cb002af1aa768b9c482a2adc79b323f1'else{}
 names=git_reader(core_root,'ls-tree','-r','--name-only','HEAD').decode().splitlines();need(len(names)==len(set(names)),'Duplicate Core Git paths');selected={p for p in names if p in FIXED or p.startswith(ROOTS)};need(FIXED<=selected and sum(p.startswith('fingerprints/')for p in selected)==9 and not(set(mapping)&set(source_only))and selected==set(mapping)|set(source_only),'Git-derived Core producer and source-only test inventory differs')
 for name,pin in mapping.items():need(digest(source_read(core_root/name))==pin,'Compiled Core input differs: '+name)
 for name,pin in source_only.items():need(digest(source_read(core_root/name))==pin,'Source-only Core regression input differs: '+name)
 return{**binding,'legacy':False,'producerDeclaredInputCount':len(mapping),'sourceOnlyTestInputs':dict(source_only)}
def main():
 p=argparse.ArgumentParser();p.add_argument('--app-root',type=Path,required=True);p.add_argument('--core-root',type=Path,required=True);p.add_argument('--producer-revision',required=True);p.add_argument('--print-source-commit',action='store_true');a=p.parse_args();value=resolve(a.app_root,a.core_root,a.producer_revision)
 print(value['sourceCommit']if a.print_source_commit else json.dumps(value,sort_keys=True))
if __name__=='__main__':main()
