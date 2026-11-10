"""Verify a retained normal JNI role against public app pins; never relabel a build or SDK profile."""
from pathlib import Path
import hashlib,json,os,re,stat,uuid

ABIS=['arm64-v8a','armeabi-v7a','x86','x86_64']
TARGETS=['aarch64-linux-android','armv7-linux-androideabi','i686-linux-android','x86_64-linux-android']
F=('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')
CONFIG_FIELDS={'schema','kind','coreVersion','androidDeliveryProfile','role','features','abis','targets','producerCommit','producerTree',
 'sourceCommit','sourceTree','normalRoleReceiptSHA256','libraries','publishedCoreSDK','runtimeOrAPKQualificationClaimed','compiledSourceManifestSHA256'}


def need(value,message):
    if not value:raise ValueError(message)


def digest(raw):return hashlib.sha256(raw).hexdigest()


def pairs(rows):
    out={}
    for key,value in rows:
        need(key not in out,'Duplicate normal JNI key');out[key]=value
    return out


def document(raw):
    def invalid(_):raise ValueError('Nonfinite normal JNI JSON')
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)


def read(path,maximum=1048576,expected_size=None,expected_hash=None):
    path=Path(path)
    need(not any(p.is_symlink()for p in(path,*path.parents)),'Normal JNI path linked')
    before,parent=path.lstat(),path.parent.lstat();facts=lambda s:tuple(getattr(s,k)for k in F)
    need(stat.S_ISREG(before.st_mode)and before.st_nlink==1 and not before.st_mode&0o022 and 0<before.st_size<=maximum
         and stat.S_ISDIR(parent.st_mode)and not parent.st_mode&0o022,'Protected normal JNI input required')
    need(expected_size is None or before.st_size==expected_size,'Normal JNI exact size differs')
    pd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        need(facts(os.fstat(pd))==facts(parent),'Normal JNI parent changed');fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=pd)
        try:
            need(facts(os.fstat(fd))==facts(before),'Normal JNI inode changed')
            with os.fdopen(os.dup(fd),'rb')as stream:body=stream.read(maximum+1)
            need(len(body)==before.st_size and facts(os.fstat(fd))==facts(before)and facts(os.fstat(pd))==facts(parent),'Normal JNI changed while reading')
        finally:os.close(fd)
    finally:os.close(pd)
    need(facts(path.lstat())==facts(before)and facts(path.parent.lstat())==facts(parent),'Normal JNI changed after read')
    need(expected_hash is None or digest(body)==expected_hash,'Normal JNI digest differs')
    return body


def config(app,role):
    app=Path(app);value=document(read(app/'config/foxcore-native-inputs.json'))
    need(isinstance(value,dict)and set(value)==CONFIG_FIELDS and type(value['schema'])is int and value['schema']==1
         and value['kind']=='pinned-qualified-normal-core-jni'and value['role']==role and role in('guard','vpn')
         and value['coreVersion']=='1.0.4'and value['androidDeliveryProfile']=='android4'and value['abis']==ABIS and value['targets']==TARGETS
         and value['runtimeOrAPKQualificationClaimed']is False,'Exact normal JNI role/profile required')
    for key in('producerCommit','producerTree','sourceCommit','sourceTree'):
        need(isinstance(value[key],str)and re.fullmatch('[a-f0-9]{40}',value[key]),'Full normal JNI source identity required')
    for key in('normalRoleReceiptSHA256','compiledSourceManifestSHA256'):
        need(isinstance(value[key],str)and re.fullmatch('[a-f0-9]{64}',value[key]),'Normal JNI digest required')
    need(value['producerCommit']==read(app/'config/foxcore-revision.txt').decode().strip(),'Normal JNI producer differs')
    compiled=read(app/'config/foxcore-source-manifest.json');binding=document(read(app/'config/foxcore-source-checkout.json'))
    manifest=document(compiled)
    need(manifest.get('commit')==value['producerCommit']and manifest.get('androidDeliveryProfile')=='android4'
         and manifest.get('abis')==ABIS and manifest.get('targets')==TARGETS and isinstance(manifest.get('files'),list)
         and len(manifest['files'])==len({r['path']for r in manifest['files']})==563,'Exact current Core source inventory required')
    need(digest(compiled)==value['compiledSourceManifestSHA256']==binding['compiledSourceManifestSha256']
         and binding['producerCommit']==value['producerCommit']and binding['sourceCommit']==value['sourceCommit']and binding['sourceTree']==value['sourceTree'],
         'Normal JNI producer/publisher source binding differs')
    need(value['features']==('shipped'if role=='guard'else read(app/'config/foxcore-features.txt').decode().strip()),'Normal JNI features differ')
    if role=='vpn':need(not {'shipped','tor','i2p','onion-service'}&set(value['features'].split(',')),'Guard JNI cannot satisfy VPN-only role')
    sdk=value['publishedCoreSDK']
    need(isinstance(sdk,dict)and set(sdk)=={'name','version','bytes','sha256','publisherCommit','publisherTree','tag','tagObject','containsRoleJNI','SDKJNIProfile'}
         and sdk['name']=='foxhole-core-native-v1.0.4.tar.gz'and sdk['version']=='1.0.4'and type(sdk['bytes'])is int and sdk['bytes']>0
         and re.fullmatch('[a-f0-9]{64}',sdk['sha256'])and re.fullmatch('[a-f0-9]{40}',sdk['tagObject'])
         and sdk['publisherCommit']==value['sourceCommit']and sdk['publisherTree']==value['sourceTree']and sdk['tag']=='v1.0.4'
         and type(sdk['containsRoleJNI'])is bool and sdk['containsRoleJNI']==(role=='guard')and sdk['SDKJNIProfile']=='guard/shipped',
         'Actual published Core SDK/profile identity differs')
    need(set(value['libraries'])==set(ABIS),'Complete normal four-ABI inventory required')
    for row in value['libraries'].values():
        need(set(row)=={'bytes','sha256'}and type(row['bytes'])is int and 0<row['bytes']<=128*1048576
             and isinstance(row['sha256'],str)and re.fullmatch('[a-f0-9]{64}',row['sha256']),'Normal JNI library declaration differs')
    return value


def verify(app,native,role,abis):
    app,native=Path(app),Path(native);value=config(app,role)
    need(abis and len(abis)==len(set(abis))and set(abis)<=set(ABIS),'Unique supported requested native ABIs required')
    raw=read(native/'NORMAL-ROLE.json',expected_hash=value['normalRoleReceiptSHA256']);receipt=document(raw)
    need(type(receipt['schema'])is int and receipt['schema']==1 and receipt['kind']=='ROOT_NORMALIZED_ACTUAL_C3_NORMAL_BUILDER_ROLE_ONLY'
         and receipt['role']==role and receipt['features']==value['features']and receipt['producerCommit']==value['producerCommit']
         and receipt['producerTree']==value['producerTree']and receipt['version']==value['coreVersion']and receipt['ABIs']==ABIS
         and receipt['libraries']==value['libraries']and receipt['normalBuilderAndELFGatePassed']is True
         and receipt['exitCode']==receipt['sourceFenceExitCode']==0 and receipt['runtimeOrAPKQualification']is False,
         'Qualified original normal role differs')
    expected={abi+'/libfoxhole_native.so'for abi in ABIS}
    need({p.relative_to(native).as_posix()for p in native.rglob('*.so')}==expected,'Normal JNI file inventory differs')
    verified=[]
    for abi in ABIS:
        row=value['libraries'][abi];path=native/abi/'libfoxhole_native.so'
        body=read(path,128*1048576,row['bytes'],row['sha256'])
        elf={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86':(1,3),'x86_64':(2,62)}[abi]
        need(len(body)>=64 and body[:4]==b'\x7fELF'and body[5]==1 and(body[4],int.from_bytes(body[18:20],'little'))==elf,'Normal JNI ELF ABI differs')
        if abi in abis:verified.append((abi,path))
    return value,verified


def copy_verified_library(source,destination,row):
    destination=Path(destination)
    need(not destination.is_symlink()and not any(p.is_symlink()for p in destination.parents),'Normal JNI output linked')
    body=read(source,128*1048576,row['bytes'],row['sha256'])
    destination.parent.mkdir(parents=True,exist_ok=True)
    parent=destination.parent.lstat()
    need(stat.S_ISDIR(parent.st_mode)and not parent.st_mode&0o022,'Normal JNI output parent writable')
    pd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    temporary='.normal-jni-'+uuid.uuid4().hex
    created=False
    try:
        opened=os.fstat(pd);need((opened.st_dev,opened.st_ino)==(parent.st_dev,parent.st_ino),'Normal JNI output parent changed')
        fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o644,dir_fd=pd);created=True
        with os.fdopen(fd,'wb')as stream:stream.write(body)
        read(destination.parent/temporary,128*1048576,row['bytes'],row['sha256'])
        # Replacing a directory entry preserves any retained old hardlink's original inode.
        os.replace(temporary,destination.name,src_dir_fd=pd,dst_dir_fd=pd);created=False
        read(destination,128*1048576,row['bytes'],row['sha256'])
    finally:
        if created:os.unlink(temporary,dir_fd=pd)
        os.close(pd)
