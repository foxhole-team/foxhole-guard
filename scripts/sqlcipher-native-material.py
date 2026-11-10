"""Closed SQLCipher native-child notices, associated after the strict Maven loader."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat

MANIFEST_SHA256 = '9adfad85c779ed718fe4b032f2c894671e87d59c9ccdbb28777950da3aaf21eb'
AAR_SHA256 = '1e57857adfd29e2f5ddb4686e1e5ec6659920220d4bf182fdb5ef085b5577100'
COORDINATE = 'net.zetetic:sqlcipher-android:4.20.0'
PARENT = dict(coordinate=COORDINATE,purl='pkg:maven/net.zetetic/sqlcipher-android@4.20.0?type=aar',bytes=4050842,sha256=AAR_SHA256)
WITNESS = 'ec7ff2a5907fcca66d4092e8993bc14c65860130386b94f1fd3b148ffbbde586'
FILES = ('MANIFEST.json','INVENTORY.json','bom.cdx.json','NOTICE.txt')
SOURCE = {'LibTomCrypt':('1.18.2','476a9579ae94f32b9ea9e2747bfb04b302370259'),
          'SQLite':('3.54.1','355d4db92d3404e71faa8088d071a71d767807f4')}
NATIVE = {
    'arm64-v8a':(2110992,'46a06f81da976f690c18c79e58f7742a2b1aa144301a35a0bb81ffa32da73756',64,183),
    'armeabi-v7a':(1057676,'fa761c03dac08249a2b663a1a2a2c19d6a3433c6c03a063f5ee4427ca2d4a827',32,40),
    'x86':(2260832,'309726bc1287683cf33fdb88e11f4079d0d718dc5f7c567029b5d74905dc40e5',32,3),
    'x86_64':(2249352,'ec1e8a082116f386951309b1a1cd40a01bf1acb8518f67f3be10d03023f3f5d0',64,62),
}
COUNTS = dict(components=176,artifacts=124,pom_files=171,edges=990)
SCOPES = {'runtime':dict(components=174,artifacts=122,pom_files=169,edges=988),
          'desugaring':dict(components=3,artifacts=2,pom_files=2,edges=2)}
F9 = ('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')


def need(value,message):
    if not value: raise ValueError('SQLCipher native material: '+message)


def digest(raw): return hashlib.sha256(raw).hexdigest()
def facts(s): return tuple(getattr(s,key) for key in F9)
def canonical(value): return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()


def unique(pairs):
    result = {}
    for key,value in pairs:
        need(key not in result,'duplicate JSON key')
        result[key] = value
    return result


def nonfinite(value): raise ValueError('Nonfinite SQLCipher companion number')
def finite_float(value):
    number = float(value)
    need(math.isfinite(number),'nonfinite JSON float')
    return number


def document(raw): return json.loads(raw,object_pairs_hook=unique,parse_constant=nonfinite,parse_float=finite_float)


def public_bytes(raw):
    text = raw.decode('utf-8')
    need('\0' not in text and not any(x in text for x in
        ('/Users/','/srv/','/private/','/home/','/var/folders/','file://','archive/ops/','ROOT-PLAN','AGENTS.md')),
        'host/private path in public companion')
    need(not re.search(r'(?i)[a-z]:[\\/](?:Users|home)[\\/]',text),'Windows host path in public companion')
    return text


def read(path,before,maximum=1048576,expected=None,size=None):
    path = Path(path)
    need(path.is_absolute() and not any(p.is_symlink() for p in (path,*path.parents)),'linked or relative input')
    s = path.lstat()
    parent = path.parent.lstat()
    need(stat.S_ISREG(s.st_mode) and s.st_nlink == 1 and 0 < s.st_size <= maximum and
         not (s.st_mode | parent.st_mode) & 0o022,'bounded protected regular input required')
    need(size is None or s.st_size == size,'exact material size differs')
    fd = os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        need(facts(os.fstat(fd)) == facts(s),'input open identity changed')
        raw = b''
        while len(raw) < s.st_size:
            part = os.read(fd,min(65536,s.st_size-len(raw)))
            need(bool(part),'short input')
            raw += part
        need(not os.read(fd,1) and facts(os.fstat(fd)) == facts(s),'input F9 changed')
    finally: os.close(fd)
    need(facts(path.lstat()) == facts(s) and facts(path.parent.lstat()) == facts(parent),'input/parent F9 changed')
    need(expected is None or digest(raw) == expected,'material digest differs')
    need(path not in before or before[path] == facts(s),'input F9 changed since prior read')
    need(path.parent not in before or before[path.parent] == facts(parent),'input parent F9 changed since prior read')
    before[path] = facts(s)
    before[path.parent] = facts(parent)
    return raw


def properties(rows):
    need(all(set(x)=={'name','value'} and isinstance(x['name'],str) and isinstance(x['value'],str) for x in rows),'invalid property')
    result = {x['name']:x['value'] for x in rows}
    need(len(result)==len(rows),'duplicate property')
    return result


def validate_documents(manifest,inventory,cdx,notice):
    need(set(manifest)=={'schema','kind','parentAAR','nativeMembers','payloadFiles','sourceBackportWitnessSHA256','licenseMaterial','counts','scope'} and
         manifest['schema']==1 and manifest['kind']=='sqlcipher420-native-subcomponents-material','manifest schema')
    need(manifest['parentAAR']==inventory['parentAAR']==PARENT,'exact parent AAR required')
    need(manifest['nativeMembers']==inventory['nativeMembers'] and len(manifest['nativeMembers'])==4,'native member scope')
    members = {x['abi']:x for x in manifest['nativeMembers']}
    need(set(members)==set(NATIVE) and len(members)==4,'exact Android4 member set')
    for abi,(size,sha,bits,machine) in NATIVE.items():
        need(members[abi]==dict(abi=abi,path='jni/'+abi+'/libsqlcipher.so',bytes=size,sha256=sha,
            elfClass=bits,eMachine=machine,neededLibraries=['libdl.so','liblog.so','libc.so','libm.so']), 'native member identity')
    need(manifest['counts']==dict(nativeLibraryComponents=2,combinedNativeELFMembers=4,payloadFiles=3,companionFilesIncludingManifest=4),'companion counts')
    need(inventory['schema']==1 and inventory['kind']=='sqlcipher420-native-subcomponents-inventory','inventory schema')
    publisher = inventory['publisherSource']
    need(publisher['commit']=='94bdf2589942b11a53b7d9a005d5c1e66920d4e8' and
         publisher['SQLCipherGitlink']==SOURCE['SQLite'][1] and publisher['LibTomCryptGitlink']==SOURCE['LibTomCrypt'][1], 'publisher source association')
    need(inventory['claims']==dict(fourNativeELFMemberHashesObserved=True,sourceRecipeAndEmbeddedProviderCorroborated=True,
         reproducedBuild=False,fullSCA=False,globalClean=False,runtimeQualified=False),'unsupported broader claim')
    witness = inventory['sourceBackportWitness']
    need(digest(canonical(witness))==manifest['sourceBackportWitnessSHA256']==inventory['sourceBackportWitnessSHA256']==WITNESS and
         witness['cve']=='CVE-2019-17362' and witness['addedLinesMatchUpstreamFix'] is True and
         witness['forkSourceCommit']==SOURCE['LibTomCrypt'][1] and
         witness['upstreamFixCommit']=='25c26a3b7a9ad8192ccc923e15cf62bf0108ef94','source backport witness')
    components = {x['name']:x for x in inventory['components']}
    need(set(components)==set(SOURCE) and len(inventory['components'])==2,'exact native library source set')
    need(set(manifest['licenseMaterial'])=={'SQLCipher','LibTomCrypt','SQLite'},'complete original notice set')
    for name,row in manifest['licenseMaterial'].items():
        header = ('===== '+row['noticeSection']+' =====\nSource: '+row['sourceURL']+
                  '\n\n----- BEGIN VERBATIM UPSTREAM NOTICE -----\n').encode()
        need(notice.count(header)==1,'exact original notice section missing')
        start = notice.index(header)+len(header)
        end = notice.find(b'----- END VERBATIM UPSTREAM NOTICE -----',start)
        need(end>=start and len(notice[start:end])==row['bytes'] and digest(notice[start:end])==row['sha256'],'verbatim original notice differs')
    need(inventory['parentLicense']['notice']==manifest['licenseMaterial']['SQLCipher'],'parent license origin differs')
    for name,(version,commit) in SOURCE.items():
        need(components[name]['version']==version and components[name]['sourceCommit']==commit and
             components[name]['licenseNotice']==manifest['licenseMaterial'][name],'native source/license differs')
    need(cdx['bomFormat']=='CycloneDX' and cdx['specVersion']=='1.5' and cdx['version']==1,'standalone companion CDX1.5 required')
    parent = cdx['metadata']['component']
    need(parent['bom-ref']=='maven:'+COORDINATE and parent['purl']==PARENT['purl'] and
         parent['type']=='library' and parent['group']=='net.zetetic' and parent['name']=='sqlcipher-android' and parent['version']=='4.20.0' and
         parent['hashes']==[dict(alg='SHA-256',content=AAR_SHA256)],'companion CDX parent differs')
    meta = properties(cdx['metadata']['properties'])
    need(meta['foxhole:source-backport-witness-sha256']==WITNESS and all(meta[x]=='false' for x in
         ('foxhole:runtime-claim','foxhole:reproduced-build-claim','foxhole:global-clean-claim')),'CDX scope claims differ')
    nodes = {x['bom-ref']:x for x in cdx['components']}
    need(len(nodes)==len(cdx['components'])==6,'exact six standalone components required')
    libraries = set()
    for name,(version,commit) in SOURCE.items():
        ref = 'native:'+name.lower()+':'+version+':'+commit
        libraries.add(ref)
        node = nodes.get(ref,{})
        need(node.get('type')=='library' and node.get('name')==name and node.get('version')==version and
             node.get('purl')==components[name]['purl'] and 'hashes' not in node,'whole ELF hash cannot identify a native library alone')
        prop = properties(node['properties'])
        need(prop['foxhole:source-commit']==commit and prop['foxhole:notice-file']=='NOTICE.txt' and
             prop['foxhole:license-source-sha256']==manifest['licenseMaterial'][name]['sha256'],'CDX native source/notice differs')
        need(node['licenses']==[{'license':{'name':components[name]['licenseName'],
             'url':manifest['licenseMaterial'][name]['sourceURL']}}] and
             node['externalReferences']==[{'type':'vcs','url':components[name]['sourceURL']}],'CDX native license/source attribution differs')
        if name=='LibTomCrypt':
            need(prop['foxhole:source-backport:CVE-2019-17362']==commit and prop['foxhole:source-backport-witness-sha256']==WITNESS,
                 'CDX backport association differs')
    file_refs = set()
    for abi,member in members.items():
        ref = 'native-file:sqlcipher:4.20.0:'+abi
        file_refs.add(ref)
        node = nodes.get(ref,{})
        need(node.get('type')=='file' and node.get('name')==member['path'] and
             node.get('hashes')==[dict(alg='SHA-256',content=member['sha256'])],'combined ELF file scope differs')
        prop = properties(node['properties'])
        need(prop=={'foxhole:abi':abi,'foxhole:member-bytes':str(member['bytes']),
                    'foxhole:hash-scope':'combined SQLCipher/SQLite/LibTomCrypt ELF member'},'file hash scope differs')
    expected = {parent['bom-ref']:file_refs,**{x:libraries for x in file_refs},**{x:set() for x in libraries}}
    edges = {}
    for row in cdx['dependencies']:
        need(set(row)=={'ref','dependsOn'} and row['ref'] not in edges and len(set(row['dependsOn']))==len(row['dependsOn']),'duplicate/invalid graph edge')
        edges[row['ref']] = set(row['dependsOn'])
    need(edges==expected and set(nodes)==file_refs|libraries,'closed containment/source association graph differs')


def associate_maven(maven_root,before):
    # The caller must run the existing strict Maven loader first; this adds only the child association.
    raw = read(maven_root/'maven-runtime.cdx.json',before)
    producer = document(read(maven_root/'PRODUCER.json',before))
    need(producer['counts']==COUNTS and {k:v['counts'] for k,v in producer['scopes'].items()}==SCOPES,'existing Maven124/POM171 scope changed')
    payload = [x for x in producer['payloadFiles'] if x['path']=='maven-runtime.cdx.json']
    need(len(payload)==1 and payload[0]['sha256']==digest(raw) and payload[0]['bytes']==len(raw),'copied Maven CDX payload binding differs')
    cdx = document(raw)
    need(cdx['bomFormat']=='CycloneDX' and cdx['specVersion']=='1.6','existing Maven CDX scope differs')
    parent = [x for x in cdx['components'] if x.get('group')=='net.zetetic' and x.get('name')=='sqlcipher-android']
    need(len(parent)==1 and parent[0]['version']=='4.20.0' and parent[0]['bom-ref']==COORDINATE,'SQLCipher420 Maven parent missing')
    prop = properties(parent[0]['properties'])
    need(prop.get('foxhole:resolved-archive:sqlcipher-android-4.20.0.aar:sha256')==AAR_SHA256,'Maven parent AAR differs')
    need(sum(1 for node in cdx['components'] for p in node.get('properties',[]) if
             p['name'].startswith('foxhole:resolved-archive:') and p['name'].endswith(':sha256'))==124,'Maven archive scope differs')
    return digest(raw)


def copy_material(repo,maven_root,output):
    repo,maven_root,output = Path(repo),Path(maven_root),Path(output)
    need(repo.is_absolute() and not any(p.is_symlink() for p in (repo,*repo.parents)),'linked repository input')
    bank = repo/'third_party/sqlcipher-native/4.20.0'
    need(not bank.is_symlink() and {x.name for x in bank.iterdir()}==set(FILES),'closed four-file bank required')
    before = {}
    bodies = {'MANIFEST.json':read(bank/'MANIFEST.json',before,65536,MANIFEST_SHA256,3476)}
    manifest = document(bodies['MANIFEST.json'])
    rows = manifest['payloadFiles']
    need(len(rows)==3 and {x['path'] for x in rows}==set(FILES)-{'MANIFEST.json'},'exact three payload roles required')
    for row in rows:
        need(set(row)=={'path','bytes','sha256'} and type(row['bytes']) is int and 0<row['bytes']<=65536,'payload role schema/budget')
        bodies[row['path']] = read(bank/row['path'],before,65536,row['sha256'],row['bytes'])
    for raw in bodies.values(): public_bytes(raw)
    validate_documents(manifest,document(bodies['INVENTORY.json']),document(bodies['bom.cdx.json']),bodies['NOTICE.txt'])
    maven_cdx = associate_maven(maven_root,before)
    target = output/'android/sqlcipher-native'
    need(output.is_dir() and not any(p.is_symlink() for p in (output,*output.parents)) and not target.exists() and
         not target.is_symlink(),'fresh companion output required')
    need(not (output/'android').is_symlink(),'linked output parent')
    target.mkdir(mode=0o700,parents=True)
    for name,raw in bodies.items():
        fd = os.open(target/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream: stream.write(raw)
    need({x.name for x in bank.iterdir()}==set(FILES) and all(facts(path.lstat())==f9 for path,f9 in before.items()),'material/Maven F9 changed')
    return dict(schema=1,kind='sqlcipher-native-companion-copy-association',manifestSHA256=MANIFEST_SHA256,
        parentAARSHA256=AAR_SHA256,mavenCDXSHA256=maven_cdx,copiedFiles=4,
        completeAppOrRuntimeQualification=False)
