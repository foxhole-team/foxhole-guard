package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream
import java.io.IOException

internal enum class RouterInternetAccess { ONLINE, NO_INTERNET, UNKNOWN }
internal class RouterRemoteInstallBundle(val path: String, private val cleanup: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) cleanup() }
}

internal class RouterBundleInputStream(private val source: InputStream, private val total: Long,
    private val progress: (Long) -> Unit) : InputStream() {
    private var completed = 0L
    override fun read(): Int {
        if (completed == total) return -1
        val value = source.read()
        if (value < 0) throw IOException("router_bundle_truncated")
        completed++; progress(completed)
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (completed == total) return -1
        val count = source.read(bytes, offset, minOf(length.toLong(), total - completed).toInt())
        if (count <= 0) throw IOException("router_bundle_truncated")
        completed += count; progress(completed)
        return count
    }
    override fun close() { source.close() }
}

internal fun routerBundleOwner(bundle: RouterDeviceInstallBundle, facts: RouterBundleTransferFacts, nonce: String): ByteArray =
    buildJsonObject {
        put("schema", 1); put("nonce", nonce); put("bootId", facts.bootId); put("manifestSha256", bundle.plan.manifestSha256)
        put("files", JsonArray(bundle.plan.assets.map { asset -> buildJsonObject {
            put("name", asset.name); put("sha256", asset.sha256); put("bytes", asset.bytes)
        } }))
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size in 1..1048576) }

internal fun routerBundleCreateCommand(path: String, nonce: String, bootId: String, ownerHash: String, requiredRam: Long): String {
    val cleanup = routerBundleActionCommand(path, nonce, bootId, ownerHash, "cleanup")
    return """
        set -eu
        umask 077
        [ "${'$'}(id -u)" = 0 ]
        [ "${'$'}(cat /proc/sys/kernel/random/boot_id)" = ${bundleQuote(bootId)} ]
        ram=${'$'}(awk '/^MemAvailable:/ {print ${'$'}2}' /proc/meminfo)
        tmp=${'$'}(df -Pk /tmp | awk 'NR==2 {print ${'$'}4}')
        case "${'$'}ram:${'$'}tmp" in ''|*[!0-9:]*|:*|*:) exit 1 ;; esac
        [ "${'$'}ram" -ge $((($requiredRam + 1023) / 1024)) ]
        [ "${'$'}tmp" -ge $((($requiredRam + 1023) / 1024)) ]
        ucode -e ${bundleQuote(BUNDLE_CREATE)} -- ${bundleQuote(path)} ${bundleQuote(nonce)} ${bundleQuote(bootId)}
    """.trimIndent() + "\n(trap '' HUP; sleep 1800; $cleanup) </dev/null >/dev/null 2>&1 &\n"
}

internal fun routerBundleActionCommand(path: String, nonce: String, bootId: String, ownerHash: String, action: String): String {
    require(path.matches(Regex("/tmp/foxhole-device-install-[a-f0-9]{32}")) && nonce.matches(Regex("[a-f0-9]{32}")) &&
        bootId.matches(Regex("[a-f0-9-]{36}")) && ownerHash.matches(Regex("[a-f0-9]{64}")) && action in setOf("verify", "cleanup"))
    val guard = "ucode -e ${bundleQuote(BUNDLE_GUARD)} -- ${bundleQuote(path)}"
    val ownerCheck = "printf '%s  %s\\n' ${bundleQuote(ownerHash)} ${bundleQuote("$path/.owner")} | sha256sum -c - >/dev/null"
    val actionCommand = "ucode -e ${bundleQuote(BUNDLE_ACTION)} -- ${bundleQuote(path)} ${bundleQuote(nonce)} ${bundleQuote(bootId)} ${bundleQuote(action)}"
    return "$guard && $ownerCheck && " + if (action == "verify") "$actionCommand | sha256sum -c - >/dev/null" else actionCommand
}

internal fun routerBundleUploadCommand(path: String, asset: RouterBundleAsset): String {
    require(path.matches(Regex("/tmp/foxhole-device-install-[a-f0-9]{32}")))
    val target = "$path/${asset.name}"
    return "umask 077; ucode -e ${bundleQuote(BUNDLE_NEW_FILE_GUARD)} -- ${bundleQuote(path)} ${bundleQuote(asset.name)} && " +
        "cat > ${bundleQuote(target)} && [ \"${'$'}(wc -c < ${bundleQuote(target)})\" = ${bundleQuote(asset.bytes.toString())} ] && " +
        "printf '%s  %s\\n' ${bundleQuote(asset.sha256)} ${bundleQuote(target)} | sha256sum -c - >/dev/null"
}

private fun bundleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private val BUNDLE_GUARD = """
    import * as fs from 'fs';
    let d=fs.lstat(ARGV[0]),p=fs.lstat(ARGV[0]+'/.owner');
    if(d?.type!='directory'||d.uid!=0||(d.mode&07777)!=0700||p?.type!='file'||p.uid!=0||p.nlink!=1||(p.mode&07777)!=0600||p.size<=0||p.size>1048576)exit(1);
""".trimIndent()

private val BUNDLE_NEW_FILE_GUARD = """
    import * as fs from 'fs';
    let d=fs.lstat(ARGV[0]),p=fs.lstat(ARGV[0]+'/'+ARGV[1]);
    if(d?.type!='directory'||d.uid!=0||(d.mode&07777)!=0700||p?.type!='file'||p.uid!=0||p.nlink!=1||(p.mode&07777)!=0600||p.size!=0)exit(1);
""".trimIndent()

private val BUNDLE_CREATE = """
    import * as fs from 'fs';
    let path=ARGV[0],nonce=ARGV[1],boot=ARGV[2],raw=fs.readfile('/dev/stdin',1048577),v=json(raw);
    if(!match(path,/^\/tmp\/foxhole-device-install-[a-f0-9]{32}$/)||v.schema!=1||v.nonce!=nonce||v.bootId!=boot||type(v.files)!='array'||length(v.files)<1||length(v.files)>1024||fs.lstat(path))exit(1);
    let seen={};
    for(let f in v.files){if(!match(f.name,/^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$/)||seen[f.name]||!match(f.sha256,/^[a-f0-9]{64}$/)||type(f.bytes)!='int'||f.bytes<1||f.bytes>536870912)exit(1);seen[f.name]=true;}
    if(!fs.mkdir(path,0700))exit(1);
    let owner=null;
    try{
      owner=fs.open(path+'/.owner','wxe',0600);if(!owner)throw 'owner';if(owner.write(raw)!=length(raw)||!owner.flush())throw 'owner';owner.close();owner=null;
      for(let f in v.files){let fd=fs.open(path+'/'+f.name,'wxe',0600);if(!fd)throw 'file';fd.close();}
    }catch(e){
      if(owner)try{owner.close();}catch(_){}
      let names=map(v.files,f=>f.name);push(names,'.owner');
      for(let name in fs.lsdir(path))if(index(names,name)<0)exit(1);
      for(let name in names){let s=fs.lstat(path+'/'+name);if(s&&(s.type!='file'||s.uid!=0||s.nlink!=1||(s.mode&07777)!=0600))exit(1);}
      for(let name in names)if(fs.lstat(path+'/'+name))fs.unlink(path+'/'+name);
      fs.rmdir(path);exit(1);
    }
""".trimIndent()

private val BUNDLE_ACTION = """
    import * as fs from 'fs';
    let path=ARGV[0],v=json(fs.readfile(path+'/.owner',1048577));
    if(v.schema!=1||v.nonce!=ARGV[1]||v.bootId!=ARGV[2]||trim(fs.readfile('/proc/sys/kernel/random/boot_id'))!=ARGV[2]||type(v.files)!='array'||length(v.files)<1||length(v.files)>1024)exit(1);
    let known={'.owner':true};
    for(let f in v.files){if(!match(f.name,/^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$/)||known[f.name]||!match(f.sha256,/^[a-f0-9]{64}$/)||type(f.bytes)!='int'||f.bytes<1||f.bytes>536870912)exit(1);known[f.name]=true;let s=fs.lstat(path+'/'+f.name);if(s&&(s.type!='file'||s.uid!=0||s.nlink!=1||(s.mode&07777)!=0600||s.size>f.bytes||(ARGV[3]=='verify'&&s.size!=f.bytes)))exit(1);if(!s&&ARGV[3]=='verify')exit(1);}
    for(let name in fs.lsdir(path))if(!known[name])exit(1);
    if(ARGV[3]=='verify'){for(let f in v.files)printf('%s  %s/%s\n',f.sha256,path,f.name);}
    else{for(let f in v.files)if(fs.lstat(path+'/'+f.name)&&!fs.unlink(path+'/'+f.name))exit(1);if(!fs.unlink(path+'/.owner')||!fs.rmdir(path))exit(1);}
""".trimIndent()
