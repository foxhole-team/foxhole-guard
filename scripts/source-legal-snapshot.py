"""Validate canonical license data against the pinned source and freshly built JNI files."""
from pathlib import Path,PurePosixPath
from types import MappingProxyType
import hashlib,importlib.util,json,os,re,stat,subprocess,sys,tomllib

HASH=re.compile(r'[0-9a-f]{64}');REVISION=re.compile(r'[0-9a-f]{40}')
SHAPES={
 'android4':{'aarch64-linux-android':'arm64-v8a','armv7-linux-androideabi':'armeabi-v7a','i686-linux-android':'x86','x86_64-linux-android':'x86_64'},
 'android64':{'aarch64-linux-android':'arm64-v8a','x86_64-linux-android':'x86_64'},
 'android-armv7':{'armv7-linux-androideabi':'armeabi-v7a'},
 'android3':{'aarch64-linux-android':'arm64-v8a','armv7-linux-androideabi':'armeabi-v7a','x86_64-linux-android':'x86_64'}}
LOCKS=('Cargo.lock','native/sentinel-yara/Cargo.lock','rust-toolchain.toml')
RUST_NOTICES={'COPYRIGHT-library.html':'0a65bb747c49c7bb816cbc7188319bd6e4e8d08091c1190b8a3c0971c47968ed','LICENSE-APACHE':'62c7a1e35f56406896d7aa7ca52d0cc0d272ac022b5d2796e7d6905db8a3636a','LICENSE-MIT':'b71bd43a069ca0641a9ecfe585ca7b3c53b5cc1608f8b68321168698e28b5ea1'}
FIELDS=('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')
def need(value,message):
 if not value:raise ValueError(message)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def pairs(rows):
 out={}
 for key,value in rows:need(key not in out,'Duplicate legal snapshot key');out[key]=value
 return out
def document(raw):return json.loads(raw,object_pairs_hook=pairs)
def relative(name):
 need(isinstance(name,str)and'\\'not in name,'Invalid legal path');p=PurePosixPath(name)
 need(name==str(p)and not p.is_absolute()and p.parts and all(v not in('.','..','.git','AGENTS.md')for v in p.parts),'Unsafe legal path');return p
def read(path,limit=1048576,expected=None,source_empty=False,nlinks=(1,)):
 path=Path(path);need(not any(p.is_symlink()for p in(path,*path.parents)),'Legal input symlink')
 before,parent=path.lstat(),path.parent.lstat();facts=lambda s:tuple(getattr(s,k)for k in FIELDS)
 need(stat.S_ISREG(before.st_mode)and before.st_nlink in nlinks and not before.st_mode&0o022 and 0<=before.st_size<=limit and(before.st_size>0 or source_empty),'Invalid legal input file')
 need(stat.S_ISDIR(parent.st_mode)and not parent.st_mode&0o022,'Writable legal input parent')
 pd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try:
  need(facts(os.fstat(pd))==facts(parent),'Legal parent changed before open');fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=pd)
  try:
   need(facts(os.fstat(fd))==facts(before)and facts(os.fstat(pd))==facts(parent),'Legal input changed before read')
   with os.fdopen(fd,'rb',closefd=False)as stream:raw=stream.read(limit+1)
   need(facts(os.fstat(fd))==facts(before)and facts(os.fstat(pd))==facts(parent),'Legal input changed while reading')
  finally:os.close(fd)
 finally:os.close(pd)
 need(len(raw)==before.st_size and facts(path.lstat())==facts(before)and facts(path.parent.lstat())==facts(parent),'Legal input changed after read')
 need(expected is None or digest(raw)==expected,'Canonical legal input digest differs');return raw
def git(root,*args):
 result=subprocess.run(['git','-C',str(root),*args],check=True,capture_output=True)
 need(len(result.stdout)<=1048576,'Oversized source identity');return result.stdout
def properties(rows):
 need(isinstance(rows,list),'Invalid legal properties');out={}
 for row in rows:
  need(isinstance(row,dict)and set(row)=={'name','value'}and isinstance(row['name'],str)and isinstance(row['value'],str)and row['name']not in out,'Duplicate legal property');out[row['name']]=row['value']
 return out
def core_coverage(files,revision,profile,version):
 shape=SHAPES[profile];coverage=document(files['foxcore/ABI-COVERAGE.json'])
 need(coverage.get('schema')==1 and coverage.get('coreCommit')==revision and coverage.get('consumer')=='guard'and coverage.get('features')=='shipped'and coverage.get('androidDeliveryProfile')==profile and coverage.get('abis')==list(shape.values())and coverage.get('coverage')=='complete'and coverage.get('primaryTarget')==next(iter(shape)),'Core canonical coverage identity differs')
 authority=coverage.get('authorities');need(isinstance(authority,dict)and set(authority)=={'producerReceipt','sourceManifest','sourceArchive','captureReceipt','cargoLockSha256'}and all(isinstance(v,str)and HASH.fullmatch(v)for v in authority.values()),'Core canonical authority hashes missing')
 need(set(coverage.get('targets',{}))==set(shape),'Core target coverage incomplete');nodes={};edges=set();target_hashes={}
 for target,abi in shape.items():
  raw=files['foxcore/foxcore-'+target+'.cdx.json'];bom=document(raw);row=coverage['targets'][target];root=bom.get('metadata',{}).get('component',{});props=properties(bom.get('metadata',{}).get('properties',[]))
  need(bom.get('bomFormat')=='CycloneDX'and bom.get('specVersion')=='1.6'and root.get('name')=='foxcore-android'and root.get('version')==version,'Core selected inventory root differs')
  need(props.get('foxhole:native:core-revision')==revision and props.get('foxhole:native:target')==target and props.get('foxhole:native:cargo-lock-sha256')==authority['cargoLockSha256']and props.get('foxhole:native:android-delivery-profile')==profile,'Core inventory source/profile differs')
  refs=set()
  for node in[root]+bom.get('components',[]):
   ref=node.get('bom-ref');need(isinstance(ref,str)and ref not in refs and node.get('licenses')and isinstance(node.get('purl'),str),'Core selected package has no identity/license');need(ref not in nodes or nodes[ref]==node,'Core target package collision');nodes[ref]=node;refs.add(ref)
  graph={}
  for edge in bom.get('dependencies',[]):
   ref,children=edge.get('ref'),edge.get('dependsOn');need(ref in refs and ref not in graph and isinstance(children,list)and len(children)==len(set(children))and all(v in refs for v in children),'Core dependency edge differs');graph[ref]=set(children)
  need(set(graph)==refs,'Core dependency graph incomplete');reachable=set();pending=[root['bom-ref']]
  while pending:
   ref=pending.pop()
   if ref not in reachable:reachable.add(ref);pending.extend(graph[ref])
  need(reachable==refs and row.get('abi')==abi and row.get('deliverySbomSha256')==digest(raw)and set(row.get('componentRefs',[]))==refs and row.get('components')==len(refs)and row.get('edges')==sum(map(len,graph.values())),'Core target graph coverage differs')
  edges|={(ref,v)for ref,children in graph.items()for v in children};target_hashes[target]=digest(raw)
 raw=files['foxcore/cargo-notices/MANIFEST.json'];notice=document(raw)
 need(coverage.get('noticeManifestSha256')==digest(raw)and notice.get('schema_version')==2 and notice.get('coverage')=='complete'and notice.get('uncovered')==[]and notice.get('target_sboms')==target_hashes and set(notice.get('component_refs',[]))==set(nodes),'Core notice union differs')
 seen=set();owners=set()
 for row in notice.get('files',[]):
  name=row.get('path');relative(name);need(name not in seen and row.get('owners')and len(row['owners'])==len(set(row['owners']))and set(row['owners'])<=set(nodes),'Core notice ownership differs');need(digest(files['foxcore/cargo-notices/'+name])==row.get('sha256'),'Core original notice differs');seen.add(name);owners.update(row['owners'])
 need(seen and owners==set(nodes)and coverage.get('unionComponents')==len(nodes)and coverage.get('unionEdges')==len(edges)and set(coverage.get('unionComponentRefs',[]))==set(nodes),'Core notice coverage incomplete')
 need({n.removeprefix('foxcore/cargo-notices/')for n in files if n.startswith('foxcore/cargo-notices/')}==seen|{'MANIFEST.json'},'Extra Core original notice');return coverage
def source_checkout(app_root,core_root,revision):
 helper=Path(__file__).with_name('core-source-binding.py');read(helper);spec=importlib.util.spec_from_file_location('exact_Core_source_checkout_binding',helper);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module.resolve(app_root,core_root,revision)
def load(snapshot_path,app_root,core_root,revision,profile,core_native,sentinel_native,source_native_manifest=None,source_native_staging=False):
 app_root,core_root=Path(app_root).resolve(),Path(core_root).resolve();path=Path(snapshot_path).absolute();need(profile in SHAPES and isinstance(revision,str)and REVISION.fullmatch(revision),'Unknown canonical source/profile');raw=read(path);record=document(raw);shape=SHAPES[profile]
 need(record.get('schema')==1 and record.get('kind')=='canonical-source-legal-delivery'and record.get('coreCommit')==revision and record.get('androidDeliveryProfile')==profile and record.get('abis')==list(shape.values())and record.get('targets')==list(shape),'Canonical source/profile identity differs')
 source_producer=record.get('sourceProducerProfile')
 need((source_producer=='android3'and set(shape)<=set(SHAPES['android3']))or(source_producer=='android64'and profile=='android64')or(source_producer=='android4'and profile in('android4','android-armv7')),'Canonical producer profile differs');checkout=source_checkout(app_root,core_root,revision);source_revision=checkout['sourceCommit'];need(git(core_root,'rev-parse','HEAD').decode().strip()==source_revision,'Core checkout differs from canonical source')
 compiled=document(read(app_root/'config/foxcore-source-manifest.json'));rows=compiled.get('files');need(isinstance(rows,list),'Compiled source inventory missing');compiled_map={row['path']:row['sha256']for row in rows}
 expected_inputs=522 if checkout.get('legacy')else checkout['producerDeclaredInputCount']
 need(len(compiled_map)==len(rows)==expected_inputs and compiled.get('commit')==revision and compiled.get('androidDeliveryProfile')==profile and compiled.get('abis')==list(shape.values())and compiled.get('targets')==list(shape)and record.get('compiledInputs')==compiled_map,'Canonical compiled source manifest differs')
 sentinels=record.get('sentinelInputs');need(isinstance(sentinels,dict)and sentinels,'Sentinel source inventory missing');names=git(core_root,'ls-tree','-r','--name-only',source_revision,'native/sentinel-yara').decode().splitlines();need(len(names)==len(set(names))and set(names)==set(sentinels),'Whole Sentinel source tree differs')
 source={**compiled_map,**sentinels};need(all(n.startswith('native/sentinel-yara/')for n in sentinels),'Sentinel source scope differs')
 for name,pin in source.items():relative(name);need(isinstance(pin,str)and HASH.fullmatch(pin),'Invalid source digest');read(core_root/name,expected=pin,source_empty=pin==digest(b''))
 locks=record.get('locks');need(isinstance(locks,dict)and set(locks)==set(LOCKS)and all(locks[n]==digest(read(core_root/n))for n in LOCKS),'Canonical source lock differs')
 version=tomllib.loads(read(core_root/'Cargo.toml').decode())['workspace']['package']['version'];need(record.get('version')==version,'Canonical Core version differs')
 need(tomllib.loads(read(core_root/'rust-toolchain.toml').decode())['toolchain']['channel']=='1.97.1','Canonical Rust compiler differs')
 expected_roots={'core':app_root/'app/build/generated/foxCoreNativeLibs','sentinel':app_root/'app/build/generated/sentinelNative/jniLibs'}
 if source_native_staging:
  need(source_native_manifest is not None and profile=='android4','Staged native verification requires explicit four-ABI source provenance')
  expected_roots={'core':app_root/'app/build/fdroidNativeSource/core','sentinel':app_root/'app/build/fdroidNativeSource/sentinel/jniLibs'}
 roots={'core':Path(core_native).absolute(),'sentinel':Path(sentinel_native).absolute()};need(roots==expected_roots,'Fresh native roots must be the normal app producer outputs')
 native=record.get('nativeLibraries');need(isinstance(native,dict)and set(native)==set(roots),'Canonical native inventories missing')
 source_native=None
 if source_native_manifest is not None:
  need(profile=='android4'and Path(source_native_manifest).absolute()==app_root/'app/build/reports/fdroid/native-source-build.json','F-Droid source native provenance must use the normal four-ABI output')
  source_native=document(read(Path(source_native_manifest).absolute()))
  expected={'schema','kind','sourceCommit','sourceTree','referenceProducerCommit','compiledSourceManifestSha256','androidDeliveryProfile','abis','targets','nativeLibraries'}
  need(set(source_native)==expected and source_native['schema']==1 and source_native['kind']=='normal-fdroid-native-source-build','Unknown native source-build provenance')
  need(source_native['sourceCommit']==source_revision and source_native['sourceTree']==checkout['sourceTree']and source_native['referenceProducerCommit']==revision and source_native['compiledSourceManifestSha256']==digest(read(app_root/'config/foxcore-source-manifest.json')),'Native source-build provenance differs from the verified checkout')
  need(source_native['androidDeliveryProfile']==profile and source_native['abis']==list(shape.values())and source_native['targets']==list(shape)and set(source_native['nativeLibraries'])==set(roots),'Native source-build ABI coverage differs')
 source_native_rows=source_native['nativeLibraries']if source_native else native
 for kind,root in roots.items():
  library='libfoxhole_native.so'if kind=='core'else'libfoxhole_sentinel_yara.so';need(set(native[kind])==set(shape),'Canonical native target omitted');need({p.relative_to(root).as_posix()for p in root.rglob('*')if p.is_file()}=={abi+'/'+library for abi in shape.values()},'Fresh native file inventory differs')
  for target,abi in shape.items():
   reference_row=native[kind][target];need(set(reference_row)=={'abi','bytes','sha256'}and reference_row['abi']==abi and type(reference_row['bytes'])is int and 0<reference_row['bytes']<=128*1024**2 and isinstance(reference_row['sha256'],str)and HASH.fullmatch(reference_row['sha256']),'Invalid canonical native row');need(set(source_native_rows[kind])==set(shape),'Source-built native target omitted');row=source_native_rows[kind][target];need(set(row)=={'abi','bytes','sha256'}and row['abi']==abi and type(row['bytes'])is int and 0<row['bytes']<=128*1024**2 and isinstance(row['sha256'],str)and HASH.fullmatch(row['sha256']),'Invalid source-built native row');body=read(root/abi/library,128*1024**2,row['sha256'],nlinks=(1,2));need(len(body)==row['bytes'],'Fresh normal JNI size differs')
   elf={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi];need(len(body)>=64 and body[:4]==b'\x7fELF'and(body[4],int.from_bytes(body[18:20],'little'))==elf and body[5]==1,'Fresh normal JNI ABI differs')
 files={};manifest=record.get('files');need(isinstance(manifest,dict)and 0<len(manifest)<=4096,'Canonical license file inventory missing');need(len({n.casefold()for n in manifest})==len(manifest),'Canonical license paths collide')
 static={'foxcore/FoxHole-Core-GPL-3.0-or-later.txt','foxcore/THIRD_PARTY_NOTICES.md','foxcore/REVISION.txt','foxcore/ABI-COVERAGE.json','sentinel/LICENSE','sentinel/README.md','sentinel/vendor/PROVENANCE.json','sentinel/REVISION.txt','sentinel/ABI-COVERAGE.json','foxcore/toolchain/PROVENANCE.json'}|{'foxcore/toolchain/'+n for n in RUST_NOTICES}|{'foxcore/foxcore-'+n+'.cdx.json'for n in shape}|{'sentinel/selected-'+n+'.cdx.json'for n in shape}|{'sentinel/'+n.removeprefix('native/sentinel-yara/')for n in sentinels if n.startswith('native/sentinel-yara/vendor/patches/')}
 for name,row in manifest.items():
  relative(name);need((name in static or name.startswith(('foxcore/cargo-notices/','sentinel/cargo-notices/')))and not name.endswith(('.so','.a','.rlib','.tar','.gz','.zip','.log'))and Path(name).name not in('producer-receipt.json','selected-capture-receipt.json','source-inputs.json','source-context.json'),'Operational/binary input is not canonical legal data')
  need(isinstance(row,dict)and set(row)=={'sha256','bytes'}and isinstance(row['sha256'],str)and HASH.fullmatch(row['sha256'])and type(row['bytes'])is int and 0<row['bytes']<=1048576,'Canonical legal member declaration differs');data=read(path.parent/'payload'/name,expected=row['sha256']);need(len(data)==row['bytes'],'Canonical legal member size differs');data.decode('utf-8');need(not data.startswith((b'\x7fELF',b'PK\x03\x04',b'dex\n',b'\0asm'))and not any(v in data for v in(b'/srv/foxhole/',b'/Users/',b'-----BEGIN PRIVATE KEY-----')),'Private operational/binary input in canonical legal data');files[name]=data
 actual={p.relative_to(path.parent/'payload').as_posix()for p in(path.parent/'payload').rglob('*')if p.is_file()or p.is_symlink()};need(actual==set(files),'Canonical legal payload inventory differs')
 core=core_coverage(files,revision,profile,version);need(core['authorities']['cargoLockSha256']==locks['Cargo.lock'],'Core coverage lock differs')
 module_path=app_root/'scripts/selected-sentinel-sbom.py';read(module_path);sys.dont_write_bytecode=True;spec=importlib.util.spec_from_file_location('unchanged_packaged_sentinel_legal',module_path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.packaged_delivery(lambda n:files[n],revision,profile);sentinel=document(files['sentinel/ABI-COVERAGE.json']);need(sentinel['authorities']['cargoLockSha256']==locks['native/sentinel-yara/Cargo.lock'],'Sentinel coverage lock differs')
 if source_producer in('android64','android4'):
  source_receipts=record.get('sourceProducerReceipts')
  need(isinstance(source_receipts,dict)and set(source_receipts)=={'core','sentinel'}and all(isinstance(v,str)and HASH.fullmatch(v)for v in source_receipts.values()),'Canonical source producer receipts missing')
  need(core.get('sourceProducerProfile')==source_producer and sentinel.get('sourceProducerProfile')==('android4'if source_producer=='android4'else'android3'),'Canonical Core/Sentinel producer profile differs')
  need(all(coverage['authorities']['producerReceipt']==source_receipts[kind]for kind,coverage in(('core',core),('sentinel',sentinel))),'Canonical source producer receipt join differs')
  for target in shape:
   bom=document(files['foxcore/foxcore-'+target+'.cdx.json'])
   need(properties(bom['metadata']['properties']).get('foxhole:native:source-producer-profile')==source_producer,'Canonical Core inventory producer profile differs')
 if source_producer=='android4':
  rows=record.get('sourceProducerReceiptsByTarget')
  need(isinstance(rows,dict)and set(rows)=={'core','sentinel'},'Android4 per-ABI producer authority missing')
  for kind,coverage in(('core',core),('sentinel',sentinel)):
   need(isinstance(rows[kind],dict)and set(rows[kind])==set(shape)and all(isinstance(v,str)and HASH.fullmatch(v)for v in rows[kind].values()),'Android4 per-ABI producer set differs')
   need(all(coverage['targets'][target].get('producerReceiptSha256')==rows[kind][target]for target in shape),'Android4 per-ABI producer receipt join differs')
 for kind,coverage in(('core',core),('sentinel',sentinel)):
  need(all(coverage['targets'][target]['nativeLibrarySha256']==native[kind][target]['sha256']for target in shape),'Canonical legal/native identity differs')
 sentinel_notice=document(files['sentinel/cargo-notices/MANIFEST.json']);need({n.removeprefix('sentinel/cargo-notices/')for n in files if n.startswith('sentinel/cargo-notices/')}=={row['path']for row in sentinel_notice['files']}|{'MANIFEST.json'},'Extra Sentinel original notice')
 need(files['foxcore/REVISION.txt']==files['sentinel/REVISION.txt']==(revision+'\n').encode(),'Canonical legal revision differs')
 for name,pin in RUST_NOTICES.items():need(digest(files['foxcore/toolchain/'+name])==pin,'Original Rust standard-library notice differs')
 rust=document(files['foxcore/toolchain/PROVENANCE.json']);need(set(rust)=={'schema','rustVersion','rustcCommit','coreCommit','androidDeliveryProfile','nativeLibraries','installedRuntimeArchives','licenseInputs'}and rust['schema']==1 and rust['rustVersion']=='1.97.1'and rust['rustcCommit']=='8bab26f4f68e0e26f0bb7960be334d5b520ea452'and rust['coreCommit']==revision and rust['androidDeliveryProfile']==profile and rust['nativeLibraries']==native and rust['licenseInputs']==RUST_NOTICES and set(rust['installedRuntimeArchives'])==set(shape),'Canonical Rust attribution source differs')
 for rows in rust['installedRuntimeArchives'].values():
  need(isinstance(rows,list)and rows and len({row['name']for row in rows})==len(rows),'Rust runtime attribution inventory differs')
  for row in rows:need(set(row)=={'name','bytes','sha256'}and Path(row['name']).name==row['name']and row['name'].endswith('.rlib')and type(row['bytes'])is int and 0<row['bytes']<=128*1024**2 and isinstance(row['sha256'],str)and HASH.fullmatch(row['sha256']),'Rust runtime attribution row differs')
 need(files['foxcore/FoxHole-Core-GPL-3.0-or-later.txt']==read(core_root/'LICENSE')and files['foxcore/THIRD_PARTY_NOTICES.md']==read(core_root/'THIRD_PARTY_NOTICES.md'),'Pinned Core original notices differ')
 for name in static:
  if name.startswith(('sentinel/LICENSE','sentinel/README.md','sentinel/vendor/')):need(files[name]==read(core_root/'native/sentinel-yara'/name.removeprefix('sentinel/')),'Pinned Sentinel original material differs')
 return MappingProxyType({'files':MappingProxyType(files),'snapshotSha256':digest(raw),'coreCommit':revision,'sourceCommit':source_revision,'sourceTree':checkout['sourceTree'],'appRoot':app_root,'profile':profile,'nativeLibraries':source_native_rows,'referenceNativeLibraries':native,'sourceNativeProvenance':source_native,'sourceNativeManifestPath':Path(source_native_manifest).absolute()if source_native_manifest is not None else None,'sourceFiles':source,'locks':locks,'roots':roots,'snapshotPath':path})
def recheck(material,core_root):
 if material.get('sourceNativeProvenance')is not None:need(document(read(material['sourceNativeManifestPath']))==material['sourceNativeProvenance'],'Native source-build provenance changed during legal generation')
 checkout=source_checkout(material['appRoot'],core_root,material['coreCommit']);need(checkout['sourceCommit']==material['sourceCommit']and checkout['sourceTree']==material['sourceTree']and git(Path(core_root),'rev-parse','HEAD').decode().strip()==material['sourceCommit'],'Core checkout changed during legal generation')
 for name,pin in material['sourceFiles'].items():read(Path(core_root)/name,expected=pin,source_empty=pin==digest(b''))
 for kind,rows in material['nativeLibraries'].items():
  library='libfoxhole_native.so'if kind=='core'else'libfoxhole_sentinel_yara.so'
  for row in rows.values():read(material['roots'][kind]/row['abi']/library,128*1024**2,row['sha256'],nlinks=(1,2))
 need(digest(read(material['snapshotPath']))==material['snapshotSha256'],'Canonical snapshot changed during legal generation')
 for name,raw in material['files'].items():read(material['snapshotPath'].parent/'payload'/name,expected=digest(raw))
