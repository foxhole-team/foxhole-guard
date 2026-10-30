#!/usr/bin/env python3
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(os.environ.get('I2PD_CACHE_TEST_ROOT', Path(__file__).resolve().parents[2]))
NDK = Path(os.environ.get('ANDROID_NDK_HOME', '/opt/android-ndk-r29'))


def archive(path, files, root):
    with tarfile.open(path, 'w:gz' if path.suffix == '.tgz' else 'w:bz2') as output:
        for name, content in files.items():
            data = content.encode()
            entry = tarfile.TarInfo(root + '/' + name)
            entry.mode = 0o755 if name in ('Configure', 'b2') else 0o644
            entry.size = len(data)
            output.addfile(entry, io.BytesIO(data))
    return hashlib.sha256(path.read_bytes()).hexdigest()


class I2pdBuildCacheTests(unittest.TestCase):
    def setUp(self):
        if not (NDK / 'source.properties').is_file():
            raise RuntimeError('Run in the existing Android NDK Docker image')
        self.temp = tempfile.TemporaryDirectory(prefix='foxhole-i2pd-cache-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        scripts = self.base / 'scripts'
        scripts.mkdir()
        for name in ('build-i2pd.sh', 'verify-native-pages.py', 'i2pd-build-cache.py'):
            if (ROOT / 'scripts' / name).exists():
                shutil.copy2(ROOT / 'scripts' / name, scripts / name)
        gate = scripts / 'verify-native-host-paths.sh'
        gate.write_text('#!/bin/bash\nset -eu\ntest -f "$1"\n')
        gate.chmod(0o755)
        self.vendor = self.base / 'third_party/i2pd'
        (self.vendor / 'build').mkdir(parents=True)
        (self.vendor / 'main.cpp').write_text('#include <crypto_fixture.h>\n#include <boost/fixture.h>\nextern "C" int crypto_fixture();\nint boost_fixture();\nint main() { return crypto_fixture() + boost_fixture() == 3 ? 0 : 1; }\n')
        (self.vendor / 'build/CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.22)
project(cache_fixture CXX)
add_executable(i2pd ../main.cpp)
target_include_directories(i2pd PRIVATE "${OPENSSL_ROOT_DIR}/include" "${BOOST_ROOT}/include")
target_link_libraries(i2pd "${OPENSSL_ROOT_DIR}/lib/libcrypto.a" "${BOOST_ROOT}/lib/libboost_filesystem.a")
''')
        subprocess.run(['git', 'init', '-q', str(self.vendor)], check=True)
        subprocess.run(['git', '-C', str(self.vendor), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.vendor), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '-qm', 'fixture'], check=True)
        (self.base / 'third_party/i2pd.version').write_text('ref=fixture\n')
        work = self.base / 'native'
        work.mkdir()
        self.history = self.base / 'history.jsonl'
        openssl = archive(work / 'openssl-fixture.tgz', {'Configure': '''#!/usr/bin/env python3
import json, os, pathlib, sys
api = next(x.split('=')[1] for x in sys.argv if x.startswith('-D__ANDROID_API__='))
target = sys.argv[1]
triple = {'android-x86_64':'x86_64-linux-android', 'android-arm64':'aarch64-linux-android'}[target]
bin = pathlib.Path(os.environ['ANDROID_NDK_ROOT']) / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
prefix = next(x.split('=', 1)[1] for x in sys.argv if x.startswith('--prefix='))
pathlib.Path('fixture.c').write_text('int crypto_fixture(void) { return 1; }\\n')
pathlib.Path('crypto_fixture.h').write_text('#define CRYPTO_FIXTURE_API ' + api + '\\n')
pathlib.Path('Makefile').write_text('build_libs:\\n\\t' + str(bin / (triple + api + '-clang')) + ' -c fixture.c -o crypto.o\\n\\t' + str(bin / 'llvm-ar') + ' rcs libcrypto.a crypto.o\\ninstall_dev:\\n\\tmkdir -p $(DESTDIR)' + prefix + '/lib $(DESTDIR)' + prefix + '/include\\n\\tcp libcrypto.a $(DESTDIR)' + prefix + '/lib/\\n\\tcp crypto_fixture.h $(DESTDIR)' + prefix + '/include/\\n')
with open(os.environ['FIXTURE_HISTORY'], 'a') as output: output.write(json.dumps({'kind':'openssl', 'api':api, 'ndk':os.environ['ANDROID_NDK_ROOT']}) + '\\n')
'''}, 'openssl')
        self.boost_archive = work / 'boost_fixture.tar.bz2'
        boost = archive(self.boost_archive, {'b2': '''#!/usr/bin/env python3
import json, os, pathlib, re, subprocess, sys
args = sys.argv[1:]
prefix = pathlib.Path(next(x.split('=',1)[1] for x in args if x.startswith('--prefix=')))
config = pathlib.Path(next(x.split('=',1)[1] for x in args if x.startswith('--user-config='))).read_text()
compiler = re.search(r'\\n  : (.+)\\n', config).group(1)
archiver = re.search(r'<archiver>(\\S+)', config).group(1)
source = pathlib.Path('fixture.cpp'); source.write_text('const char * boost_fixture_path = __FILE__; int boost_fixture() { return 2; }\\n')
object = pathlib.Path('fixture.o')
flags = re.findall(r'<compileflags>([^\\s]+)', config)
subprocess.run([compiler, *flags, '-c', str(source.resolve()), '-o', str(object)], check=True)
(prefix / 'lib').mkdir(parents=True)
(prefix / 'include/boost').mkdir(parents=True)
subprocess.run([archiver, 'rcs', str(prefix / 'lib/libboost_filesystem.a'), str(object)], check=True)
(prefix / 'include/boost/fixture.h').write_text('#define BOOST_FIXTURE 1\\n')
with open(os.environ['FIXTURE_HISTORY'], 'a') as output: output.write(json.dumps({'kind':'boost', 'compiler':compiler}) + '\\n')
'''}, 'boost_fixture')
        (scripts / 'native-deps.sh').write_text('''native_deps_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
i2pd_work_dir="${I2PD_WORK_DIR:-$native_deps_repo_root/native}"
openssl_ver=fixture
openssl_url=fixture
boost_ver=fixture
boost_us=boost_fixture
boost_url=fixture
openssl_sha256=''' + openssl + '\nboost_sha256=' + boost + '''
fetch_verified() { test "$(sha256_file "$2")" = "$3"; }
sha256_file() { sha256sum "$1" | cut -d' ' -f1; }
''')
        self.env = os.environ.copy()
        self.env.update({'ANDROID_NDK_HOME': str(NDK), 'I2PD_ABIS':'x86_64', 'I2PD_BUILD_JOBS':'1', 'I2PD_WORK_DIR':str(self.base / 'native'), 'I2PD_BUILD_ROOT':str(self.base / 'build/i2pd'), 'I2PD_OUTPUT_ROOT':str(self.base / 'out'), 'FIXTURE_HISTORY':str(self.history), 'TMPDIR':str(self.base)})
        self.script = scripts / 'build-i2pd.sh'
        self.build = self.base / 'build/i2pd/x86_64'

    def run_build(self):
        result = subprocess.run(['bash', str(self.script)], env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(result.returncode, 0, result.stdout)
        return result.stdout

    def object(self, build=None):
        return next((build or self.build).rglob('main.cpp.o'))

    def histories(self):
        return [json.loads(line) for line in self.history.read_text().splitlines()]

    def mark_cache(self):
        marker = self.build / 'cache-reuse-sentinel'
        marker.write_text('keep only compatible cache')
        return marker

    def test_same_inputs_reuse_compiled_object(self):
        self.run_build()
        before = self.object().stat().st_mtime_ns
        marker = self.mark_cache()
        log = self.run_build()
        self.assertTrue(marker.exists(), log)
        self.assertEqual(self.object().stat().st_mtime_ns, before, log)
        self.assertEqual(len(self.histories()), 2)

    def test_link_flags_relink_without_cpp_compile(self):
        self.run_build()
        before = self.object().stat().st_mtime_ns
        binary = (self.build / 'i2pd').read_bytes()
        self.script.write_text(self.script.read_text().replace('page-size=16384', 'page-size=32768'))
        log = self.run_build()
        self.assertEqual(self.object().stat().st_mtime_ns, before, log)
        self.assertNotEqual((self.build / 'i2pd').read_bytes(), binary)
        self.assertIn('Linking CXX executable', log)
        self.assertNotIn('Building CXX object', log)

    def test_changed_source_content_with_restored_mtime_invalidates(self):
        self.run_build()
        marker = self.mark_cache()
        source = self.vendor / 'main.cpp'
        stamp = source.stat()
        source.write_text(source.read_text().replace('== 3', '== 4'))
        os.utime(source, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.run_build()
        self.assertFalse(marker.exists())

    def test_android_api_change_invalidates_static_dependencies(self):
        self.run_build()
        marker = self.mark_cache()
        self.env['I2PD_ANDROID_API'] = '27'
        self.run_build()
        self.assertFalse(marker.exists())
        self.assertEqual(len(self.histories()), 4)
        self.assertEqual([x['api'] for x in self.histories() if x['kind'] == 'openssl'], ['26', '27'])
        cache = (self.build / 'CMakeCache.txt').read_text()
        self.assertIn('ANDROID_PLATFORM:UNINITIALIZED=android-27', cache)

    def test_ndk_path_change_invalidates_static_dependencies(self):
        self.run_build()
        replacement = self.base / 'replacement-ndk'
        replacement.mkdir()
        shutil.copy2(NDK / 'source.properties', replacement / 'source.properties')
        shutil.copytree(NDK / 'build', replacement / 'build')
        (replacement / 'toolchains').symlink_to(NDK / 'toolchains', target_is_directory=True)
        self.env['ANDROID_NDK_HOME'] = str(replacement)
        marker = self.mark_cache()
        self.run_build()
        self.assertFalse(marker.exists())
        self.assertEqual(len(self.histories()), 4)

    def test_toolchain_content_change_invalidates_cache(self):
        replacement = self.base / 'replacement-ndk'
        replacement.mkdir()
        shutil.copy2(NDK / 'source.properties', replacement / 'source.properties')
        shutil.copytree(NDK / 'build', replacement / 'build')
        (replacement / 'toolchains').symlink_to(NDK / 'toolchains', target_is_directory=True)
        self.env['ANDROID_NDK_HOME'] = str(replacement)
        self.run_build()
        marker = self.mark_cache()
        toolchain = replacement / 'build/cmake/android.toolchain.cmake'
        toolchain.write_text(toolchain.read_text() + '\n# fixture toolchain revision\n')
        self.run_build()
        self.assertFalse(marker.exists())
        self.assertEqual(len(self.histories()), 4)

    def test_abi_never_reuses_copied_foreign_cache(self):
        self.run_build()
        foreign = self.base / 'build/i2pd/arm64-v8a'
        shutil.copytree(self.build, foreign)
        self.env['I2PD_ABIS'] = 'arm64-v8a'
        self.run_build()
        self.assertNotEqual(self.object(foreign).read_bytes(), self.object().read_bytes())
        self.assertEqual(len(self.histories()), 4)

    def test_dependency_header_content_change_with_same_mtime_invalidates(self):
        self.run_build()
        marker = self.mark_cache()
        header = next((self.base / 'native').glob('boost-*/include/boost/fixture.h'))
        stamp = header.stat()
        header.write_text('#define BOOST_FIXTURE 2\n')
        os.utime(header, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.run_build()
        self.assertFalse(marker.exists())

    def test_boost_version_and_checksum_change_invalidates_static_dependency(self):
        self.run_build()
        original = self.object().read_bytes()
        with tarfile.open(self.boost_archive) as source:
            files = {entry.name.split('/', 1)[1]: source.extractfile(entry).read().decode()
                     for entry in source.getmembers() if entry.isfile()}
        files['b2'] = files['b2'].replace('return 2;', 'return 5;')
        updated = self.base / 'native/boost_fixture2.tar.bz2'
        digest = archive(updated, files, 'boost_fixture2')
        dependencies = self.base / 'scripts/native-deps.sh'
        with dependencies.open('a') as output:
            output.write('boost_ver=fixture2\nboost_us=boost_fixture2\nboost_sha256=' + digest + '\n')
        self.run_build()
        self.assertEqual(len(self.histories()), 3)
        cache = (self.build / 'CMakeCache.txt').read_text()
        self.assertIn('boost-fixture2-', cache)
        library = next((self.base / 'native').glob('boost-fixture2-*/lib/libboost_filesystem.a'))
        self.assertTrue(library.is_file())
        self.assertEqual(self.object().read_bytes(), original)

    def test_openssl_version_and_checksum_change_invalidates_static_dependency(self):
        self.run_build()
        current = self.base / 'native/openssl-fixture.tgz'
        with tarfile.open(current) as source:
            files = {entry.name.split('/', 1)[1]: source.extractfile(entry).read().decode()
                     for entry in source.getmembers() if entry.isfile()}
        files['Configure'] = files['Configure'].replace('return 1;', 'return 7;')
        updated = self.base / 'native/openssl-fixture2.tgz'
        digest = archive(updated, files, 'openssl_fixture2')
        dependencies = self.base / 'scripts/native-deps.sh'
        with dependencies.open('a') as output:
            output.write('openssl_ver=fixture2\nopenssl_sha256=' + digest + '\n')
        self.run_build()
        self.assertEqual(len(self.histories()), 3)
        self.assertIn('openssl-fixture2-', (self.build / 'CMakeCache.txt').read_text())

    def test_boost_keys_and_bytes_are_shared_across_unrelated_i2pd_snapshots(self):
        self.run_build()
        boost = next((self.base / 'native').glob('boost-*/lib/libboost_filesystem.a'))
        first_archive = boost.read_bytes()
        boost_object = next((self.base / 'native').glob('boost_fixture-*/fixture.o'))
        first_object = boost_object.read_bytes()
        private = self.base / 'another-application-checkout'
        (private / 'third_party').mkdir(parents=True)
        shutil.copytree(self.base / 'scripts', private / 'scripts')
        shutil.copy2(self.base / 'third_party/i2pd.version', private / 'third_party/i2pd.version')
        snapshot = private / 'third_party/unrelated-snapshot'
        shutil.copytree(self.vendor, snapshot)
        self.script = private / 'scripts/build-i2pd.sh'
        self.env.update({'I2PD_SOURCE_DIR': str(snapshot), 'I2PD_WORK_DIR': str(self.base / 'native')})
        self.run_build()
        self.assertEqual(len(self.histories()), 2)
        (boost.parent.parent / '.foxhole-build-complete').unlink()
        self.run_build()
        self.assertEqual(len(self.histories()), 3)
        self.assertEqual(boost.read_bytes(), first_archive)
        self.assertEqual(boost_object.read_bytes(), first_object)
        config = (self.base / 'native/user-config-x86_64.jam').read_text()
        self.assertNotIn(str(self.vendor), config)
        self.assertNotIn(str(snapshot), config)
        self.assertNotIn(str(private), config)
        self.assertIn('/usr/src/i2pd-deps', config)
        self.assertIn('/usr/src/android-ndk', config)

    def test_foreign_dependency_completion_stamps_force_rebuild(self):
        self.run_build()
        stamps = list((self.base / 'native').glob('*/.foxhole-build-complete'))
        self.assertEqual(len(stamps), 2)
        for stamp in stamps:
            stamp.write_text('foreign-input-key\n')
        self.run_build()
        self.assertEqual(len(self.histories()), 4)

    def test_source_path_change_does_not_repoint_cmake_cache(self):
        self.run_build()
        replacement = self.base / 'other-source'
        shutil.copytree(self.vendor, replacement)
        marker = self.mark_cache()
        self.env['I2PD_SOURCE_DIR'] = str(replacement)
        self.run_build()
        self.assertFalse(marker.exists())
        self.assertIn(str(replacement / 'build'), (self.build / 'CMakeCache.txt').read_text())

    def test_user_modified_cmake_cache_is_discarded(self):
        self.run_build()
        marker = self.mark_cache()
        cache = self.build / 'CMakeCache.txt'
        cache.write_text(cache.read_text() + '\nFOREIGN_CACHE_ENTRY:STRING=unexpected\n')
        self.run_build()
        self.assertFalse(marker.exists())
        self.assertNotIn('FOREIGN_CACHE_ENTRY', cache.read_text())


if __name__ == '__main__':
    unittest.main()
