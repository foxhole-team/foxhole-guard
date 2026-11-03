"""Strict ARM64 preview consumer of the unchanged, qualified Android4 Sentinel bank."""
from pathlib import Path
import hashlib, importlib.util, json, re, stat

TARGET='aarch64-linux-android'
HASH=re.compile(r'[a-f0-9]{64}')
REV=re.compile(r'[a-f0-9]{40}')
FIXED={
 '.cargo/config.toml':'7bffa1ce3ff53a1f59f465b34012ddeb1b49c08d9c8a18b47bdffd64ac1ef3db',
 'rust-toolchain.toml':'8e390d6a0838315f972690f46ef8bae8b7ecc9ee6c1ed70140ef852869c2482e',
 'scripts/sentinel-android-build.sh':'90854f98900fa7f658b39e2cad36e1e209c8c78a86abbbbb95d3bffa0db4983a',
}
def need(v,r):
 if not v:raise ValueError(r)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def encoded(value):return(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n').encode()
def pairs(rows):
 out={}
 for k,v in rows:need(k not in out,'Duplicate retained preview member');out[k]=v
 return out
def read(path):
 path=Path(path);need(not any(p.is_symlink()for p in(path,*path.parents)),'Retained preview symlink refused');s=path.lstat();parent=path.parent.lstat()
 need(stat.S_ISREG(s.st_mode)and s.st_nlink==1 and not s.st_mode&0o022 and not parent.st_mode&0o022 and 0<s.st_size<=1048576,'Protected retained preview input required')
 raw=path.read_bytes();after=path.lstat();need((s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns)and len(raw)==s.st_size,'Retained preview changed');return raw
def document(raw):return json.loads(raw,object_pairs_hook=pairs)
def authority(root,revision,profile="android-arm64"):
 root=Path(root);need(profile in ('android-arm64','android4'),'Unknown retained Sentinel profile');full=profile=='android4'
 raw=read(root/'config'/('sentinel-retained-native.json'if full else'sentinel-arm64-preview.json'));a=document(raw)
 need(set(a)=={'schema','kind','status','consumerCoreCommit','originalCoreCommit','referenceSnapshotSha256','coreProducerReceiptSha256',('currentCoreNativeLibraries'if full else'currentCoreNativeLibrary')},'Exact retained preview authority required')
 need(a['schema']==1 and a['kind']==('retained-android4-sentinel-current-consumer'if full else'retained-android4-sentinel-arm64-preview')and a['status']=='BOUND_ACTUAL'and a['consumerCoreCommit']==revision and REV.fullmatch(revision)and REV.fullmatch(a['originalCoreCommit']),'Actual retained preview identity required')
 need(HASH.fullmatch(a['referenceSnapshotSha256'])and HASH.fullmatch(a['coreProducerReceiptSha256']),'Actual retained preview digests required')
 reference=root/'third_party/licenses/source-legal/reference-android4';baseline=(reference if reference.is_dir()else root/'third_party/licenses/source-legal/android4')/'snapshot.json';old_raw=read(baseline);need(digest(old_raw)==a['referenceSnapshotSha256'],'Qualified Android4 reference changed');old=document(old_raw)
 need(old.get('schema')==1 and old.get('kind')=='canonical-source-legal-delivery'and old.get('androidDeliveryProfile')=='android4'and old.get('sourceProducerProfile')=='android4'and old.get('coreCommit')==a['originalCoreCommit'],'Genuine original four-ABI Sentinel reference required')
 rows=a['currentCoreNativeLibraries']if full else{TARGET:a['currentCoreNativeLibrary']}
 shape={'aarch64-linux-android':'arm64-v8a','armv7-linux-androideabi':'armeabi-v7a','i686-linux-android':'x86','x86_64-linux-android':'x86_64'}if full else{TARGET:'arm64-v8a'}
 need(set(rows)==set(shape),'Exact retained Core native target set required')
 for target,abi in shape.items():
  row=rows[target];need(set(row)=={'abi','bytes','sha256'}and row['abi']==abi and type(row['bytes'])is int and row['bytes']>0 and HASH.fullmatch(row['sha256']),'Actual current Core native identity required')
 return a,old,baseline.parent/'payload'
def validate(root,revision,read_notice,current_sentinel_inputs=None,current_core_native=None,profile="android-arm64"):
 a,old,payload=authority(root,revision,profile);full=profile=="android4"
 if current_sentinel_inputs is not None:need(current_sentinel_inputs==old['sentinelInputs'],'Retained Sentinel source bytes changed')
 if current_core_native is not None:need(current_core_native==a['currentCoreNativeLibraries'if full else'currentCoreNativeLibrary'],'Retained consumer Core native differs')
 names={n for n in old['files']if n.startswith('sentinel/')}
 need(names and all(not n.endswith(('.so','.a','.rlib'))for n in names),'Original legal-only Sentinel inventory required')
 for name in names:
  raw=read(payload/name);row=old['files'][name];need(len(raw)==row['bytes']and digest(raw)==row['sha256']and read_notice(name)==raw,'Original Sentinel legal material was relabelled or changed')
 binding=encoded(a);need(read_notice('sentinel/RETAINED-NATIVE.json'if full else'sentinel/RETAINED-ARM64-PREVIEW.json')==binding,'Packaged retained preview authority differs')
 spec=importlib.util.spec_from_file_location('original_android4_sentinel_preview',Path(root)/'scripts/selected-sentinel-sbom.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 bom,_=m.packaged_delivery(read_notice,a['originalCoreCommit'],'android4')
 coverage=document(read_notice('sentinel/ABI-COVERAGE.json'))
 targets=old['nativeLibraries']['sentinel']if full else{TARGET:old['nativeLibraries']['sentinel'][TARGET]}
 need(all(coverage['targets'][target]['nativeLibrarySha256']==row['sha256']for target,row in targets.items()),'Original Sentinel native identity differs')
 return {'authority':a,'reference':old,'coverage':coverage,'bom':bom,'authoritySHA256':digest(binding),'legalPaths':names}
def verify_current_tools(core_root):
 for name,pin in FIXED.items():need(digest(read(Path(core_root)/name))==pin,'Retained Sentinel toolchain/config changed')
