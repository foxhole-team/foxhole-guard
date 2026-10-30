"""Admit only FoxHole's exact modified rustls0.23.46 source and notice chain."""
import hashlib,json,re
from pathlib import PurePosixPath

ORIGIN_PATH='config/source-forks/rustls-0.23.46.json'
UPSTREAM_PATH='config/source-forks/rustls-0.23.46.upstream-files.json'
NOTICE_PATH='config/source-forks/rustls-0.23.46.NOTICE.md'
PATCH_PATH='patches/rustls-0.23.46-chrome151.patch'
VENDOR_PATH='vendor/rustls'
UPSTREAM_CRATE_SHA256='48e13bd8c0e9365c43cfa5c9e8f9ad49d3c8444926c9aac819e0e4dc503c8fdf'
UPSTREAM_MANIFEST_SHA256='bc389f68b1eb14f6d79bb38870640fabd05e44ec22bf12d872e479d033b81c34'
LICENSE_EXPRESSION='Apache-2.0 OR ISC OR MIT'
LICENSES={'LICENSE-APACHE':('a60eea817514531668d7e00765731449fe14d059d3249e0bc93b36de45f759f2',10847),'LICENSE-ISC':('7cfafc877eccc46c0e346ccbaa5c51bb6b894d2b818e617d970211e232785ad4',775),'LICENSE-MIT':('709e3175b4212f7b13aa93971c9f62ff8c69ec45ad8c6532a7e0c41d7a7d6f8c',1082)}
NOTICE_PREFIX='source-forks/rustls-0.23.46/'
HASH=re.compile('[0-9a-f]{64}')

def require(value,reason):
 if not value:raise ValueError(reason)

def sha(data):return hashlib.sha256(data).hexdigest()

def object_pairs(items):
 out={}
 for key,value in items:require(key not in out,'Duplicate fork metadata key');out[key]=value
 return out

def read_json(raw):return json.loads(raw,object_pairs_hook=object_pairs)

def safe_path(name):
 require(isinstance(name,str)and name and re.fullmatch(r'[A-Za-z0-9_./+-]+',name),'Unsafe fork path')
 p=PurePosixPath(name);require(str(p)==name and not p.is_absolute()and not any(x in('..','.git','AGENTS.md')for x in p.parts),'Fork path escapes source')
 return name

def reverse_patch(vendor,raw):
 """Reverse exact text hunks without fuzz, shell execution or external tools."""
 require(len(raw)<=2*1024**2,'Fork patch too large');lines=raw.decode().splitlines(True);restored=dict(vendor);changed=[];i=0
 while i<len(lines):
  require(lines[i].startswith('diff --git a/'),'Unexpected fork patch header');i+=1
  require(i+1<len(lines)and lines[i].startswith('--- ')and lines[i+1].startswith('+++ b/'),'Unsupported fork patch form')
  old=lines[i][4:].rstrip('\n');new=safe_path(lines[i+1][6:].rstrip('\n'));i+=2
  require(old in('/dev/null','a/'+new)and new in vendor and new not in changed,'Fork patch path mismatch');changed.append(new)
  actual=vendor[new].decode().splitlines(True);cursor=0;result=[];hunks=0
  while i<len(lines)and not lines[i].startswith('diff --git '):
   match=re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n',lines[i]);require(match is not None,'Malformed fork patch hunk');i+=1;hunks+=1
   old_count=int(match[2]if match[2]is not None else 1);new_count=int(match[4]if match[4]is not None else 1)
   start=int(match[3])-1 if new_count else int(match[3]);require(cursor<=start<=len(actual),'Fork patch hunk position differs');result+=actual[cursor:start];cursor=start;seen_old=seen_new=0
   old_start=int(match[1])-1 if old_count else int(match[1]);require(len(result)==old_start,'Fork patch old position differs')
   while i<len(lines)and not lines[i].startswith(('@@ ','diff --git ')):
    line=lines[i];require(line[:1]in(' ','+','-'),'Unsupported patch marker');body=line[1:];i+=1
    if line[0]in(' ','+'):
     require(cursor<len(actual)and actual[cursor]==body,'Fork patch differs from modified source');cursor+=1;seen_new+=1
    if line[0]in(' ','-'):result.append(body);seen_old+=1
   require((seen_old,seen_new)==(old_count,new_count),'Fork patch line counts differ')
  require(hunks>0,'Fork patch has no hunks');result+=actual[cursor:];body=''.join(result).encode()
  if old=='/dev/null':require(not body,'New fork file reverse is not empty');restored.pop(new)
  else:restored[new]=body
 return restored,sorted(changed)

def validate_origin(read_source,names):
 """read_source(path)->(bound source bytes, archive/file mode); names are bound source files."""
 names=set(names);require(all(safe_path(n)==n for n in names),'Invalid source names')
 def read(path,limit):
  require(path in names,'Fork source input missing: '+path);body,mode=read_source(path)
  require(type(body)is bytes and type(mode)is int and mode==0o644 and 0<len(body)<=limit,'Invalid fork source bytes/mode: '+path);return body
 raw=read(ORIGIN_PATH,128*1024);origin=read_json(raw)
 require(origin.get('schema')==1 and origin.get('status')=='FROZEN'and origin.get('crate')=='rustls'and origin.get('version')=='0.23.46','Fork origin is not frozen exact rustls0.23.46')
 require(origin.get('sourceKind')=='vendored-modified'and origin.get('vendorPath')==VENDOR_PATH and origin.get('registryCacheModified')is False and origin.get('upstreamCrateSha256')==UPSTREAM_CRATE_SHA256,'Fork source identity differs')
 require(origin.get('upstreamManifest')=={'path':UPSTREAM_PATH,'sha256':UPSTREAM_MANIFEST_SHA256},'Fork upstream manifest identity differs')
 upstream_raw=read(UPSTREAM_PATH,128*1024);require(sha(upstream_raw)==UPSTREAM_MANIFEST_SHA256,'Fork upstream manifest changed');upstream=read_json(upstream_raw)
 require(upstream.get('crate')=='rustls'and upstream.get('version')=='0.23.46'and upstream.get('upstreamCrateSha256')==UPSTREAM_CRATE_SHA256 and len(upstream['files'])==119,'Fork original inventory differs')
 original={safe_path(row['path']):row for row in upstream['files']};require(len(original)==119,'Duplicate upstream source path')
 rows=origin.get('vendorFiles');require(isinstance(rows,list)and len(rows)==120,'Exactly120 frozen rustls vendor files required')
 declared={safe_path(row['path']):row for row in rows};require(len(declared)==120 and set(declared)=={n for n in names if n.startswith(VENDOR_PATH+'/')},'Frozen vendor file set differs')
 vendor={};vendor_map={}
 for path,row in declared.items():
  require(set(row)=={'path','sha256','bytes','mode'}and HASH.fullmatch(row['sha256'])is not None,'Invalid vendor file row')
  body=read(path,2*1024**2);require((sha(body),len(body),row['mode'])==(row['sha256'],row['bytes'],0o644),'Vendor input changed: '+path)
  vendor[path[len(VENDOR_PATH)+1:]]=body;vendor_map[path]={'sha256':sha(body),'bytes':len(body),'mode':0o644}
 require(sum(len(v)for v in vendor.values())<=16*1024**2,'Vendor source exceeds byte bound')
 tree_sha=sha(json.dumps(vendor_map,sort_keys=True).encode());require(origin.get('vendorTreeSha256')==tree_sha,'Vendored tree hash differs')
 patches=origin.get('patches');require(isinstance(patches,list)and len(patches)==1 and set(patches[0])=={'path','sha256'}and patches[0]['path']==PATCH_PATH and HASH.fullmatch(patches[0]['sha256'])is not None,'Exact rustls patch required')
 patch_raw=read(PATCH_PATH,2*1024**2);require(sha(patch_raw)==patches[0]['sha256'],'Fork patch hash differs');restored,changed=reverse_patch(vendor,patch_raw)
 require(set(restored)==set(original),'Reverse fork patch does not restore original file set')
 for path,body in restored.items():
  row=original[path];require(sha(body)==row['sha256']and len(body)==row['bytes']and int(row['mode'],8)==0o644,'Reverse fork patch does not restore original bytes: '+path)
 require(set(changed)=={p for p in vendor if p not in original or sha(vendor[p])!=original[p]['sha256']},'Fork patch paths do not match actual changes')
 expected_licenses=[{'path':VENDOR_PATH+'/'+name,'sha256':value[0],'bytes':value[1]}for name,value in sorted(LICENSES.items())]
 require(origin.get('licenses')==expected_licenses,'Fork original license identities differ')
 for name,(digest,size)in LICENSES.items():require(sha(vendor[name])==digest and len(vendor[name])==size,'Original rustls license changed')
 notice=origin.get('modificationNotice');require(isinstance(notice,dict)and set(notice)=={'path','sha256'}and notice['path']==NOTICE_PATH and HASH.fullmatch(notice['sha256'])is not None,'Fork modification notice required')
 notice_raw=read(NOTICE_PATH,128*1024);require(sha(notice_raw)==notice['sha256'],'Fork modification notice changed')
 notice_files=[(NOTICE_PREFIX+'ORIGIN.json',raw),(NOTICE_PREFIX+'upstream-files.json',upstream_raw),(NOTICE_PREFIX+'chrome151.patch',patch_raw),(NOTICE_PREFIX+'FOXHOLE-MODIFICATIONS.md',notice_raw)]+[(NOTICE_PREFIX+name,vendor[name])for name in sorted(LICENSES)]
 return {'name':'rustls','version':'0.23.46','provenanceSha256':sha(raw),'vendorTreeSha256':tree_sha,'patchSha256':sha(patch_raw),'vendorManifest':VENDOR_PATH+'/Cargo.toml','noticeFiles':notice_files,'changedPaths':changed,'licenseExpression':LICENSE_EXPRESSION,'sourceBound':True}

def identity_url(origin,revision):
 require(re.fullmatch('[0-9a-f]{40}',revision)is not None and HASH.fullmatch(origin['provenanceSha256'])is not None,'Fork identity lacks actual source revision')
 return 'pkg:generic/foxhole-source-fork/rustls@0.23.46?core_commit='+revision+'&source_fork_sha256='+origin['provenanceSha256']

def component_properties(origin,revision):
 identity_url(origin,revision)
 return [{'name':name,'value':value}for name,value in [('foxhole:source-kind','vendored-modified'),('foxhole:source-fork-metadata-sha256',origin['provenanceSha256']),('foxhole:upstream-cargo-purl','pkg:cargo/rustls@0.23.46'),('foxhole:upstream-crate-sha256',UPSTREAM_CRATE_SHA256),('foxhole:source-patch-sha256',origin['patchSha256']),('foxhole:vendored-tree-sha256',origin['vendorTreeSha256']),('cargo:source-manifest',origin['vendorManifest']),('cargo:source-revision',revision)]]

def validate_component(component,origin,revision):
 require(component.get('name')=='rustls'and component.get('version')=='0.23.46'and component.get('purl')==identity_url(origin,revision),'Modified fork component identity differs')
 rows=component.get('properties',[]);values={r['name']:r['value']for r in rows};require(len(values)==len(rows),'Duplicate fork properties')
 require('cargo:workspace-manifest'not in values and all(values.get(r['name'])==r['value']for r in component_properties(origin,revision)),'Modified fork properties differ')
 licenses=component.get('licenses',[]);require(licenses in([{'expression':LICENSE_EXPRESSION}],[{'license':{'name':LICENSE_EXPRESSION}}]),'Modified fork license declaration differs')
 return True

def validate_notice_origin(read_notice,names):
 """Verify the APK's seven bound notice bytes; this does not re-admit source code."""
 required={NOTICE_PREFIX+n for n in ('ORIGIN.json','upstream-files.json','chrome151.patch','FOXHOLE-MODIFICATIONS.md',*LICENSES)}
 require({n for n in names if n.startswith(NOTICE_PREFIX)}==required,'Fork APK notice set differs')
 values={}
 for path in sorted(required):
  body=read_notice(path);require(type(body)is bytes and 0<len(body)<=2*1024**2,'Invalid fork notice bytes');values[path]=body
 raw=values[NOTICE_PREFIX+'ORIGIN.json'];origin=read_json(raw)
 require(origin.get('schema')==1 and origin.get('status')=='FROZEN'and origin.get('crate')=='rustls'and origin.get('version')=='0.23.46'and origin.get('sourceKind')=='vendored-modified'and origin.get('vendorPath')==VENDOR_PATH and origin.get('upstreamCrateSha256')==UPSTREAM_CRATE_SHA256 and origin.get('registryCacheModified')is False,'APK fork metadata identity differs')
 require(origin.get('upstreamManifest')=={'path':UPSTREAM_PATH,'sha256':UPSTREAM_MANIFEST_SHA256}and sha(values[NOTICE_PREFIX+'upstream-files.json'])==UPSTREAM_MANIFEST_SHA256,'APK upstream notice manifest differs')
 rows=origin.get('vendorFiles');require(isinstance(rows,list)and len(rows)==120,'APK vendor inventory differs')
 declared={safe_path(row['path']):row for row in rows};require(len(declared)==120 and all(p.startswith(VENDOR_PATH+'/')for p in declared),'APK vendor inventory paths differ')
 vendor_map={}
 for path,row in declared.items():
  require(set(row)=={'path','sha256','bytes','mode'}and HASH.fullmatch(row['sha256'])is not None and type(row['bytes'])is int and 0<row['bytes']<=2*1024**2 and row['mode']==0o644,'Invalid APK vendor inventory row')
  vendor_map[path]={k:row[k]for k in ('sha256','bytes','mode')}
 tree_sha=sha(json.dumps(vendor_map,sort_keys=True).encode());require(origin.get('vendorTreeSha256')==tree_sha,'APK declared vendor digest differs')
 patch_raw=values[NOTICE_PREFIX+'chrome151.patch'];require(origin.get('patches')==[{'path':PATCH_PATH,'sha256':sha(patch_raw)}],'APK fork patch differs')
 notice_raw=values[NOTICE_PREFIX+'FOXHOLE-MODIFICATIONS.md'];require(origin.get('modificationNotice')=={'path':NOTICE_PATH,'sha256':sha(notice_raw)},'APK modification notice differs')
 expected=[{'path':VENDOR_PATH+'/'+name,'sha256':value[0],'bytes':value[1]}for name,value in sorted(LICENSES.items())];require(origin.get('licenses')==expected,'APK original license metadata differs')
 for name,(digest,size)in LICENSES.items():
  body=values[NOTICE_PREFIX+name];require(sha(body)==digest and len(body)==size,'APK original license bytes differ')
 return {'name':'rustls','version':'0.23.46','provenanceSha256':sha(raw),'vendorTreeSha256':tree_sha,'patchSha256':sha(patch_raw),'vendorManifest':VENDOR_PATH+'/Cargo.toml','noticeFiles':sorted(values.items()),'licenseExpression':LICENSE_EXPRESSION,'sourceBound':False}

def validate_packaged_origin(read_notice,source_pins):
 """Bind seven APK notice bytes and all120 declared vendor digests to the app's pinned source map."""
 require(isinstance(source_pins,dict)and all(isinstance(k,str)and isinstance(v,str)and HASH.fullmatch(v)is not None for k,v in source_pins.items()),'Invalid pinned source digest map')
 names={NOTICE_PREFIX+n for n in ('ORIGIN.json','upstream-files.json','chrome151.patch','FOXHOLE-MODIFICATIONS.md',*LICENSES)}
 descriptor=validate_notice_origin(read_notice,names)
 origin_raw=read_notice(NOTICE_PREFIX+'ORIGIN.json');origin=read_json(origin_raw)
 required={ORIGIN_PATH:sha(origin_raw),UPSTREAM_PATH:UPSTREAM_MANIFEST_SHA256,PATCH_PATH:descriptor['patchSha256'],NOTICE_PATH:sha(read_notice(NOTICE_PREFIX+'FOXHOLE-MODIFICATIONS.md'))}
 vendor={row['path']:row['sha256']for row in origin['vendorFiles']}
 require({n for n in source_pins if n.startswith(VENDOR_PATH+'/')}==set(vendor),'Pinned source vendor file set differs from packaged origin')
 required.update(vendor)
 require(all(source_pins.get(path)==digest for path,digest in required.items()),'Packaged fork metadata differs from pinned source digests')
 descriptor['sourcePinsBound']=True
 return descriptor
