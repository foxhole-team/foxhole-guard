#!/usr/bin/env python3
import io, os, shlex, shutil, struct, subprocess, tarfile, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NDK = Path(os.environ.get('ANDROID_NDK_HOME','/opt/android-ndk-r29'))
TOOLBIN = NDK/'toolchains/llvm/prebuilt/linux-x86_64/bin'
ABIS = {'arm64-v8a':'aarch64-linux-android26-clang', 'armeabi-v7a':'armv7a-linux-androideabi26-clang', 'x86':'i686-linux-android26-clang', 'x86_64':'x86_64-linux-android26-clang'}
SOURCE = 'extern int puts(const char *); const char *const pointer = "page fixture"; int main(void) { return puts(pointer) < 0; }\n'


def relro_end_modulo(binary, page):
    data = binary.read_bytes()
    cls = data[4]
    h = struct.unpack_from('<HHIQQQIHHHHHH' if cls == 2 else '<HHIIIIIHHHHHH',data,16)
    for i in range(h[9]):
        ph = struct.unpack_from('<IIQQQQQQ' if cls == 2 else '<IIIIIIII',data,h[4]+i*h[8])
        if ph[0] == 0x6474e552:
            return ((ph[3]+ph[6]) if cls == 2 else (ph[2]+ph[5])) % page
    raise AssertionError('Fixture has no RELRO segment')


def fixture_dependencies(base):
    scripts = base/'scripts'
    scripts.mkdir()
    (scripts/'fixture.c').write_text(SOURCE)
    (scripts/'verify-native-host-paths.sh').write_text('#!/bin/bash\nset -eu\ntest -f "$1"\n')
    (scripts/'verify-native-host-paths.sh').chmod(0o755)
    native = base/'native'
    vendor = base/'third_party/i2pd'
    vendor.mkdir(parents=True)
    subprocess.run(['git','init','-q',str(vendor)],check=True)
    subprocess.run(['git','-C',str(vendor),'-c','user.name=Fixture','-c','user.email=fixture@example.test','commit','--allow-empty','-qm','fixture'],check=True)
    epoch=subprocess.check_output(['git','-C',str(vendor),'show','-s','--format=%ct','HEAD'],text=True).strip()
    (base/'third_party/i2pd.version').write_text('ref=fixture\n')
    (vendor/'build').mkdir()
    (vendor/'build/CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.22)\nproject(page_fixture C)\nadd_executable(i2pd '+str(scripts/'fixture.c')+')\n')
    for abi in ABIS:
        openssl=native/f'i2pd-deps/openssl-3.5.8-canonical-prefix-v1-{epoch}-{abi}/usr/local/foxhole-openssl/lib'
        openssl.mkdir(parents=True)
        (openssl/'libcrypto.a').touch()
        boost=native/f'i2pd-deps/boost-path-remap-v1-{abi}/lib'
        boost.mkdir(parents=True)
        (boost/'libboost_filesystem.a').touch()
    archives = native/'i2pd-deps'
    archives.mkdir(parents=True, exist_ok=True)
    openssl_configure = """#!/bin/bash
set -eu
prefix=/usr/local/foxhole-openssl
cat > Makefile <<EOF
build_libs:
\t:
install_dev:
\tmkdir -p \\$(DESTDIR)$prefix/lib \\$(DESTDIR)$prefix/include
\ttouch \\$(DESTDIR)$prefix/lib/libcrypto.a
EOF
"""
    boost_b2 = """#!/bin/bash
set -eu
for argument in "$@"; do
  if [[ "$argument" == --prefix=* ]]; then prefix="${argument#--prefix=}"; fi
done
mkdir -p "$prefix/lib" "$prefix/include"
touch "$prefix/lib/libboost_filesystem.a"
"""
    for name, mode, root, entry, content in [
            ('openssl-3.5.8.tgz', 'w:gz', 'openssl', 'Configure', openssl_configure),
            ('boost_1_84_0.tar.bz2', 'w:bz2', 'boost_1_84_0', 'b2', boost_b2)]:
        with tarfile.open(archives/name, mode) as output:
            data = content.encode()
            member = tarfile.TarInfo(root+'/'+entry)
            member.mode = 0o755
            member.size = len(data)
            output.addfile(member, io.BytesIO(data))
    for name in ['lyrebird','conjure','conjure-patched']:
        (native/'tor-transports'/name/'.git').mkdir(parents=True)
    (scripts/'native-deps.sh').write_text('''native_deps_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$native_deps_repo_root"
native_deps_dir="$repo_root/native"
i2pd_work_dir="$native_deps_dir/i2pd-deps"
tor_work_dir="$native_deps_dir/tor-transports"
go_root_dir="$native_deps_dir/go1.26.8"
go_mod_cache="$native_deps_dir/gomodcache"
go_version=go1.26.8
openssl_ver=3.5.8
openssl_url=fixture
openssl_sha256=fixture
boost_url=fixture
boost_sha256=fixture
boost_ver=1.84.0
boost_us=boost_1_84_0
lyrebird_commit=fixture
lyrebird_repo=fixture
lyrebird_ref=fixture
conjure_commit=fixture
conjure_repo=fixture
conjure_ref=fixture
fetch_verified() { :; }
require_offline_seed() { :; }
native_offline() { return 1; }
sha256_file() { sha256sum "$1" | cut -d' ' -f1; }
''')
    (scripts/'prepare-tor-dependency.py').write_text('pass\n')
    fakebin=base/'bin'; fakebin.mkdir()
    # The boundary doubles pass the actual emitted link flags into the real pinned NDK.
    wrapper='''#!/usr/bin/env python3
import json,os,shlex,subprocess,sys
from pathlib import Path
args=sys.argv[1:]
root=Path(os.environ['FIXTURE_ROOT'])
ndk=Path(os.environ['ANDROID_NDK_HOME'])/'toolchains/llvm/prebuilt/linux-x86_64/bin'
abis={'arm64-v8a':'aarch64-linux-android26-clang','armeabi-v7a':'armv7a-linux-androideabi26-clang','x86':'i686-linux-android26-clang','x86_64':'x86_64-linux-android26-clang'}
if Path(sys.argv[0]).name=='go':
    if args==['env','GOVERSION']:
        print('go1.26.8');sys.exit(0)
    if args[0]=='build':
        out=Path(args[args.index('-o')+1]); out.parent.mkdir(parents=True,exist_ok=True)
        ldflags=shlex.split(args[args.index('-ldflags')+1])
        flags=[]
        for i,flag in enumerate(ldflags):
            if flag=='-extldflags':flags=shlex.split(ldflags[i+1])
            elif flag.startswith('-extldflags='):flags=shlex.split(flag.split('=',1)[1])
        cc=os.environ['CC'].replace('clang++','clang')
        subprocess.run([cc,str(root/'scripts/fixture.c'),*flags,'-o',str(out)],check=True)
        (out.parent/(out.name+'.flags.json')).write_text(json.dumps(flags));sys.exit(0)
    raise SystemExit('Unexpected go invocation: '+repr(args))
'''
    for name in ['go']:
        (fakebin/name).write_text(wrapper); (fakebin/name).chmod(0o755)
    # fetch_source validates a literal source commit; keep this concern outside the linker test.
    git=fakebin/'git'
    git.write_text('''#!/bin/bash
set -eu
if [[ "$*" == *"tor-transports"* && "$*" == *"rev-parse HEAD"* ]]; then printf 'fixture\\n'; else exec /usr/bin/git "$@"; fi
''')
    git.chmod(0o755)
    (base/'tmp').mkdir()
    env=os.environ.copy()
    env.update({'TMPDIR':str(base/'tmp'),'I2PD_SOURCE_DIR':str(vendor),'I2PD_BUILD_ROOT':str(base/'build/i2pd'),'I2PD_OUTPUT_ROOT':str(base/'app/src/main/jniLibs'),'PATH':str(fakebin)+':'+env['PATH'],'FIXTURE_ROOT':str(base),'ANDROID_NDK_HOME':str(NDK),'I2PD_BUILD_JOBS':'1','GO':str(fakebin/'go')})
    return scripts,env


class NativeLinkPageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (NDK/'source.properties').is_file():
            raise RuntimeError('Run this test in the pinned Android Docker image')
        if 'Pkg.Revision = 29.0.14206865' not in (NDK/'source.properties').read_text():
            raise RuntimeError('NDK revision mismatch')

    def build_script(self,script,abi):
        with tempfile.TemporaryDirectory(prefix='foxhole-native-pages-') as temp:
            base=Path(temp); scripts,env=fixture_dependencies(base)
            shutil.copy2(ROOT/'scripts'/script,scripts/script)
            if (ROOT/'scripts/i2pd-build-cache.py').exists():
                shutil.copy2(ROOT/'scripts/i2pd-build-cache.py',scripts/'i2pd-build-cache.py')
            if (ROOT/'scripts/verify-native-pages.py').exists():
                shutil.copy2(ROOT/'scripts/verify-native-pages.py',scripts/'verify-native-pages.py')
            env['I2PD_ABIS']=abi;env['TOR_TRANSPORT_ABIS']=abi
            result=subprocess.run(['bash',str(scripts/script)],env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            self.assertEqual(result.returncode,0,result.stdout)
            output=base/f'app/src/main/jniLibs/{abi}/libi2pd.so' if script=='build-i2pd.sh' else base/f'app/src/main/assets/tor/{abi}/tor/pluggable_transports/lyrebird'
            self.assertEqual(relro_end_modulo(output,16384 if abi in ['arm64-v8a','x86_64'] else 4096),0,result.stdout)

    def test_i2pd_arm64_link_emission_aligns_relro(self):
        self.build_script('build-i2pd.sh','arm64-v8a')

    def test_i2pd_x64_link_emission_aligns_relro(self):
        self.build_script('build-i2pd.sh','x86_64')

    def test_tor_arm64_link_emission_aligns_relro(self):
        self.build_script('build-tor-transports.sh','arm64-v8a')

    def test_tor_x64_link_emission_aligns_relro(self):
        self.build_script('build-tor-transports.sh','x86_64')

    def test_i2pd_arm32_keeps_platform_alignment(self):
        self.build_script('build-i2pd.sh','armeabi-v7a')

    def test_tor_x86_keeps_platform_alignment(self):
        self.build_script('build-tor-transports.sh','x86')

    def test_i2pd_x86_keeps_platform_alignment(self):
        self.build_script('build-i2pd.sh','x86')

    def test_tor_arm32_keeps_platform_alignment(self):
        self.build_script('build-tor-transports.sh','armeabi-v7a')

    def test_page_gate_rejects_default_ndk_relro_end(self):
        with tempfile.TemporaryDirectory(prefix='foxhole-relro-reject-') as temp:
            base=Path(temp)
            source=base/'fixture.c';source.write_text(SOURCE)
            binary=base/'fixture'
            env=os.environ.copy();env['TMPDIR']=temp
            subprocess.run([str(TOOLBIN/ABIS['arm64-v8a']),str(source),'-o',str(binary)],env=env,check=True)
            self.assertEqual(relro_end_modulo(binary,16384),4096)
            result=subprocess.run(['python3',str(ROOT/'scripts/verify-native-pages.py'),'arm64-v8a',str(binary)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            self.assertEqual(result.returncode,1,result.stdout)
            self.assertIn('RELRO end',result.stdout)

if __name__=='__main__':
    unittest.main()
