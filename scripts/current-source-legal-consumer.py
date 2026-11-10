"""Current C3/Core and original C1/Sentinel input binding. No runtime or exact-unit-graph claim."""
from pathlib import Path,PurePosixPath
from types import MappingProxyType
import hashlib,importlib.util,json,os,re,stat

KIND='current-guard-source-native-legal-consumer-v1'
CORE='3fc3b70d8e9d700b3e07ec27638515c00651b013'
TREE='1fd25d80359fd8b12765baec7c6d3093e92f89db'
CHECKOUT='90ae3e0d60de3d35e88e24558c99e771d9ca0bcf'
CHECKOUT_TREE='ab7dbd0fb7dec20685dc4c279497b2abc78df8d2'
COMPILED='1cc9afa207f2d89b46e4dd0ea4e12bd3a2409bd39a4b1875829daf32c05fba4a'
SNT='8e152b83650022a3350d555783eb98f1bd978d9f'
SNT_TREE='f048fce743818d0a2e5715773c9ca04b2cc6ac0c'
CORE_HEADER='4c9eed23073b43b1cf803b7b34c5821403673293a642f44a6558ff966ef9fed4'
CORE_JOIN='33b5863464fcb82b560c46ab07c5d3c22beb725da65c3d18ca223c1e9c72875e'
SNT_NOTICE='3248517bc892ab37dafcdcb491c5831ec35cf5a730d5d4bdf99f53967fc8423c'
SNT_LEGAL='d8fc7e76dfc25630912188eef8f532b8e9a8442db5d803fff723789245f12899'
CORE_ROLE='5126e7b4d99485bb1719875918f76add73dbc0825b08bd4f7f89566980471065'
SNT_ROLE='66cd84c17384063a6a3a52fc220e33be637971fca8b3b26b38a64d3528e12be1'
TARGETS={'aarch64-linux-android':'arm64-v8a','armv7-linux-androideabi':'armeabi-v7a','i686-linux-android':'x86','x86_64-linux-android':'x86_64'}
HASH=re.compile('[a-f0-9]{64}')
F=('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')
OUTSIDE_SNT={'.cargo/config.toml','Cargo.lock','Cargo.toml','rust-toolchain.toml','scripts/android-elf-gate.sh','scripts/android-native-pages.py','scripts/android-path-leaks.py','scripts/sentinel-android-build.sh'}
CORE_CDX=['7c60c075ff4004f674201c21cc8193c1cc408ca8eb21060a1efcb4c467b7261e','3cf7f8cf9c4c7e625b887f60c4f8fe064282ba5bf6b91d4ebf230529753c4d0d','e13dd711aafd72678691527b82ed13765dc2ee20aef7c00ac331151961f4652f','aa8cebba7bb6f25ac5cbd10583c51255d4a70850a0c1d31e8e0456270d3f34e8']
SNT_CDX=['bf6fddd293ee2bfadf4d2df098bea7d5ca0bbbce0167082efce51953a9220131','768f814b57999f0822c4af78f7a581c0b1b49fa89ae52e4b2a6170ebcb51e2f3','8870a1f1857b4659cec14a78fafd95f6dd7e9176b6014f02244b4a61fbc928de','62b3f8bb42640a139f9ab61e4093ebf5eade8bcc1168cfdbe9018e1243ffad44']
SNT_NOTICES=['087a15ec5159a0668c8ab5a0ccc5c0e30922dc6657e776d4cf55ffbd6463eeec','ab42619d4459b2d4f1e10c58be4bd02c620eb98bd2ecd3b356b5d492afbacaef','cb6cc8e689f2ccf572129c06d166e09720a7195b130af012f898513319edf4d0','586a630f220957df9c2a14871eba421053fe8c50e53bc8ebde1306a4fead4f86']
REQUIRED={'foxcore/FoxHole-Core-GPL-3.0-or-later.txt','foxcore/THIRD_PARTY_NOTICES.md','foxcore/REVISION.txt','foxcore/ABI-COVERAGE.json','foxcore/CURRENT-CONSUMER.json','foxcore/cargo-notices/MANIFEST.json',
 'foxcore/toolchain/LICENSE-APACHE','foxcore/toolchain/LICENSE-MIT','foxcore/toolchain/COPYRIGHT-library.html','foxcore/toolchain/PROVENANCE.json',
 'sentinel/LICENSE','sentinel/README.md','sentinel/vendor/PROVENANCE.json','sentinel/REVISION.txt','sentinel/ABI-COVERAGE.json','sentinel/CURRENT-CONSUMER.json'}


def need(value,message):
    if not value:raise ValueError(message)


def digest(raw):return hashlib.sha256(raw).hexdigest()
def encoded(value):return(json.dumps(value,sort_keys=True,indent=2)+'\n').encode()


def document(raw):
    def unique(rows):
        out={}
        for k,v in rows:need(k not in out,'Duplicate current consumer key');out[k]=v
        return out
    def invalid(_):raise ValueError('Nonfinite current consumer JSON')
    return json.loads(raw,object_pairs_hook=unique,parse_constant=invalid)


def relative(name):
    need(isinstance(name,str)and not any(ord(c)<32 or ord(c)==127 or c=='\\'for c in name),'Unsafe consumer member')
    p=PurePosixPath(name);need(str(p)==name and not p.is_absolute()and p.parts and all(c.casefold()not in('.','..','.git','agents.md')for c in p.parts),'Unsafe consumer member')
    return p


def read(path,limit=1048576,expected=None,size=None,empty=False):
    path=Path(path);need(not any(p.is_symlink()for p in(path,*path.parents)),'Current consumer link refused')
    before,parent=path.lstat(),path.parent.lstat();facts=lambda s:tuple(getattr(s,k)for k in F)
    need(stat.S_ISREG(before.st_mode)and before.st_nlink==1 and not(before.st_mode|parent.st_mode)&0o022 and 0<=before.st_size<=limit
         and(before.st_size>0 or empty)and(size is None or before.st_size==size),'Protected current consumer input required')
    pd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        need(facts(os.fstat(pd))==facts(parent),'Current consumer parent changed');fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=pd)
        try:
            need(facts(os.fstat(fd))==facts(before),'Current consumer inode changed')
            with os.fdopen(os.dup(fd),'rb')as stream:raw=stream.read(limit+1)
            need(facts(os.fstat(fd))==facts(before)and facts(os.fstat(pd))==facts(parent),'Current consumer changed during read')
        finally:os.close(fd)
    finally:os.close(pd)
    need(len(raw)==before.st_size and facts(path.lstat())==facts(before)and facts(path.parent.lstat())==facts(parent),'Current consumer changed after read')
    need(expected is None or digest(raw)==expected,'Current consumer digest differs');return raw


def helper(path,name):
    read(path);spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


def claims(value):
    need(set(value)=={'runtimeQualified','APKQualified','completeConsumerLegalQualification','exactCompiledUnitGraphClaim','perUnitFeatureTopology'}
         and value.get('runtimeQualified')is False and value.get('APKQualified')is False and value.get('completeConsumerLegalQualification')is False
         and value.get('exactCompiledUnitGraphClaim')is False and value.get('perUnitFeatureTopology')=='UNKNOWN_NOT_CLAIMED','Current consumer scope must not be promoted')


def validate_record(record):
    need(isinstance(record,dict)and set(record)=={'schema','kind','coverage','consumer','profile','version','source','core','sentinel','retainedSourceInputs','files','inputJoinSHA256','claims'}
         and type(record['schema'])is int and record['schema']==1 and record['kind']==KIND and record['coverage']=='complete'
         and record['consumer']=='guard'and record['profile']=='android4'and record['version']=='1.0.4','Exact qualified current Guard snapshot required')
    need(record['source']=={'producerCommit':CORE,'producerTree':TREE,'checkoutCommit':CHECKOUT,'checkoutTree':CHECKOUT_TREE,'compiledManifestSHA256':COMPILED},'Current C3 source binding differs')
    c=record['core'];s=record['sentinel']
    need(set(c)=={'role','features','normalReceiptSHA256','legalHeaderSHA256','legalJoinSHA256','format','boundedPackageUnionQualified','nativeLibraries'}
         and set(s)=={'producerCommit','producerTree','normalReceiptSHA256','noticeValidatorSHA256','legalResultSHA256','retainedInputCount','nativeLibraries'},'Closed current producer/source/legal roles required')
    need(c['role']=='guard'and c['features']=='shipped'and c['normalReceiptSHA256']==CORE_ROLE and c['legalHeaderSHA256']==CORE_HEADER and c['legalJoinSHA256']==CORE_JOIN
         and c['format']=='foxhole-source-legal-package-union-qualification-v3'and c['boundedPackageUnionQualified']is True,'Guard C3 normal/source legal role differs')
    need(s['producerCommit']==SNT and s['producerTree']==SNT_TREE and s['normalReceiptSHA256']==SNT_ROLE and s['noticeValidatorSHA256']==SNT_NOTICE
         and s['legalResultSHA256']==SNT_LEGAL and s['retainedInputCount']==1486,'Original C1 Sentinel role differs')
    for row in(c,s):
        need(set(row['nativeLibraries'])==set(TARGETS),'Current native four targets required')
        for target,abi in TARGETS.items():
            lib=row['nativeLibraries'][target]
            need(set(lib)=={'abi','bytes','sha256'}and lib['abi']==abi and type(lib['bytes'])is int and 0<lib['bytes']<=128*1048576
                 and isinstance(lib['sha256'],str)and HASH.fullmatch(lib['sha256']),'Current native row differs')
    source=record['retainedSourceInputs'];need(isinstance(source,dict)and len(source)==1486 and {n for n in source if not n.startswith('native/sentinel-yara/')}==OUTSIDE_SNT,'Exact retained Sentinel source closure required')
    for name,row in source.items():
        relative(name);need(set(row)=={'bytes','sha256'}and type(row['bytes'])is int and 0<=row['bytes']<=1048576 and isinstance(row['sha256'],str)and HASH.fullmatch(row['sha256']),'Retained source declaration differs')
        need(row['bytes']>0 or row['sha256']==digest(b''),'Unbound empty source refused')
    need(set(record['inputJoinSHA256'])=={'coreC3','sentinel'}and all(isinstance(h,str)and HASH.fullmatch(h)for h in record['inputJoinSHA256'].values()),'Actual input-join hashes required')
    files=record['files'];need(isinstance(files,dict)and 0<len(files)<=4096 and len({n.casefold()for n in files})==len(files),'Closed current legal file inventory required')
    for name,row in files.items():
        relative(name);need(name.startswith(('foxcore/','sentinel/'))and not name.endswith(('.so','.a','.rlib','.tar','.gz','.zip','.log'))
             and Path(name).name not in('producer-receipt.json','NORMAL-ROLE.json','source-inputs.json','capture-manifest.json'), 'Operational/native data cannot be public legal material')
        need(set(row)=={'bytes','sha256'}and type(row['bytes'])is int and 0<row['bytes']<=1048576 and isinstance(row['sha256'],str)and HASH.fullmatch(row['sha256']),'Legal declaration differs')
    expected=set(REQUIRED)
    for i,target in enumerate(TARGETS):
        cp='foxcore/foxcore-'+target+'.cdx.json';sp='sentinel/selected-'+target+'.cdx.json';np='sentinel/cargo-notices/'+target+'/MANIFEST.json'
        expected.update((cp,sp,np))
        need(files.get(cp,{}).get('sha256')==CORE_CDX[i]and files.get(sp,{}).get('sha256')==SNT_CDX[i]and files.get(np,{}).get('sha256')==SNT_NOTICES[i],'Original current target legal data differs')
    need(expected<=set(files)and files['foxcore/cargo-notices/MANIFEST.json']['sha256']=='3b9e8a88bfb0f82116e34e222a63ead730d5507c2792c56ba162f2f498b3d2fa','Complete original current legal payload required')
    need(files['foxcore/CURRENT-CONSUMER.json']['sha256']==record['inputJoinSHA256']['coreC3']
         and files['sentinel/CURRENT-CONSUMER.json']['sha256']==record['inputJoinSHA256']['sentinel'],'Public consumer projections differ from actual input joins')
    claims(record['claims']);return record


def verify_source(app,core,record):
    m=helper(Path(app)/'scripts/core-source-binding.py','current_source_binding')
    binding=m.resolve(app,core,CORE)
    need(binding['sourceCommit']==CHECKOUT and binding['sourceTree']==CHECKOUT_TREE and binding['producerDeclaredInputCount']==563
         and binding['compiledSourceManifestSha256']==COMPILED,'Current clean publisher checkout differs')
    paths=m.git(Path(core),'ls-tree','-r','--name-only','HEAD','native/sentinel-yara').decode().splitlines()
    need(len(paths)==len(set(paths))and set(paths)=={n for n in record['retainedSourceInputs']if n.startswith('native/sentinel-yara/')},'Whole original Sentinel source tree differs')
    for name,row in record['retainedSourceInputs'].items():read(Path(core)/name,expected=row['sha256'],size=row['bytes'],empty=row['bytes']==0)
    return binding


def verify_native(root,rows):
    root=Path(root);expected={abi+'/libfoxhole_sentinel_yara.so'for abi in TARGETS.values()}
    need({p.relative_to(root).as_posix()for p in root.rglob('*')if p.is_file()or p.is_symlink()}==expected,'Exact current Sentinel bank required')
    for target,abi in TARGETS.items():
        row=rows[target];raw=read(root/abi/'libfoxhole_sentinel_yara.so',128*1048576,row['sha256'],row['bytes'])
        bits,machine={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi]
        need(raw[:4]==b'\x7fELF'and len(raw)>=64 and raw[4]==bits and raw[5]==1 and int.from_bytes(raw[18:20],'little')==machine,'Original Sentinel static ELF identity differs')


def load(snapshot,app,core,revision,profile,core_native,sentinel_native,source_native_manifest=None):
    need(revision==CORE and profile=='android4'and source_native_manifest is None,'New current mode accepts only separately qualified retained normal Guard4')
    app,core=Path(app).resolve(),Path(core).resolve();snapshot=Path(snapshot).absolute()
    anchor=document(read(app/'config/source-legal-consumer-bindings.json'))
    need(set(anchor)=={'schema','kind','coverage','snapshotSHA256','inputJoinSHA256'}and type(anchor['schema'])is int and anchor['schema']==1
         and anchor['kind']=='pinned-current-guard-source-native-legal-bindings'and anchor['coverage']=='complete'
         and isinstance(anchor['snapshotSHA256'],str)and HASH.fullmatch(anchor['snapshotSHA256']),'Actual qualified current snapshot trust anchor required')
    raw=read(snapshot,expected=anchor['snapshotSHA256']);record=validate_record(document(raw))
    need(record['inputJoinSHA256']==anchor['inputJoinSHA256'],'Current source/native joins differ')
    verify_source(app,core,record)
    normal=helper(app/'scripts/normal-core-native.py','current_normal_core')
    cfg=normal.config(app,'guard')
    need(cfg['normalRoleReceiptSHA256']==CORE_ROLE and cfg['features']=='shipped','Current Core normal role differs')
    for target,abi in TARGETS.items():
        row=record['core']['nativeLibraries'][target];need(cfg['libraries'][abi]=={'bytes':row['bytes'],'sha256':row['sha256']},'Current Core native/code binding differs')
        normal.read(Path(core_native)/abi/'libfoxhole_native.so',128*1048576,row['bytes'],row['sha256'])
    need({p.relative_to(Path(core_native)).as_posix()for p in Path(core_native).rglob('*.so')}=={a+'/libfoxhole_native.so'for a in TARGETS.values()},'Extra or missing current Core JNI refused')
    verify_native(sentinel_native,record['sentinel']['nativeLibraries'])
    payload=snapshot.parent/'payload';need({p.relative_to(payload).as_posix()for p in payload.rglob('*')if p.is_file()or p.is_symlink()}==set(record['files']),'Current legal payload set differs')
    files={}
    for name,row in record['files'].items():
        body=read(payload/name,expected=row['sha256'],size=row['bytes']);body.decode('utf-8')
        need(not any(token in body for token in(b'/Users/',b'/srv/foxhole/',b'-----BEGIN PRIVATE KEY-----'))and not body.startswith((b'\x7fELF',b'PK\x03\x04',b'dex\n',b'\0asm')),'Private or binary material in current legal payload')
        files[name]=body
    return MappingProxyType({'files':MappingProxyType(files),'snapshotSha256':digest(raw),'coreCommit':CORE,'sourceCommit':CHECKOUT,'sourceTree':CHECKOUT_TREE,
        'appRoot':app,'profile':'android4','currentMode':KIND,'record':record,'snapshotPath':snapshot,
        'roots':{'core':Path(core_native),'sentinel':Path(sentinel_native)},'nativeLibraries':{'core':record['core']['nativeLibraries'],'sentinel':record['sentinel']['nativeLibraries']},
        'referenceNativeLibraries':{'core':record['core']['nativeLibraries'],'sentinel':record['sentinel']['nativeLibraries']},'sourceNativeProvenance':None})


def recheck(material,core):
    current=load(material['snapshotPath'],material['appRoot'],core,CORE,'android4',material['roots']['core'],material['roots']['sentinel'])
    need(current['snapshotSha256']==material['snapshotSha256'],'Current consumer snapshot changed')
