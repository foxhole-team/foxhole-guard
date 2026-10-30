#!/usr/bin/env python3
"""Build the F-Droid channel from its declared source and canonical legal data."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import tomllib

CONSUMER = "guard"
CORE_URL = "https://github.com/foxhole-team/foxhole-core.git"
GO_URL = "https://github.com/golang/go.git"
GO_COMMIT = "4e8c85f5319d999bfd7617fdc191631e5634e3c5"
GO_VERSION = "go1.26.10"
BOOTSTRAP_SHA256 = "ccfe7ae822d2c2f41f4fe7c956199967a4dac22d0ee0d2b2bfdb440aab3b20c5"
BOOTSTRAP_BYTES = 66936659
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]
FACTS = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
sys.dont_write_bytecode = True


def need(value, message):
    if not value:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def document(raw):
    def unique(items):
        result = {}
        for key, value in items:
            need(key not in result, "Duplicate source metadata key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def read(path, limit=1048576, expected=None, nlinks=(1,)):
    path = Path(path)
    need(not any(p.is_symlink() for p in (path, *path.parents)), "Linked source input refused")
    before, parent = path.lstat(), path.parent.lstat()
    facts = lambda value: tuple(getattr(value, name) for name in FACTS)
    need(stat.S_ISREG(before.st_mode) and before.st_nlink in nlinks and
         not (before.st_mode | parent.st_mode) & 0o022 and 0 < before.st_size <= limit,
         "Unsafe source input metadata")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        need(facts(os.fstat(fd)) == facts(before), "Source input changed before open")
        with os.fdopen(os.dup(fd), "rb") as stream:
            raw = stream.read(limit + 1)
        need(facts(os.fstat(fd)) == facts(before), "Source input changed while reading")
    finally:
        os.close(fd)
    need(len(raw) == before.st_size and facts(path.lstat()) == facts(before) and
         facts(path.parent.lstat()) == facts(parent), "Source input changed after reading")
    need(expected is None or digest(raw) == expected, "Source input digest differs")
    return raw


def module(path):
    read(path)
    spec = importlib.util.spec_from_file_location("fdroid_" + path.stem.replace("-", "_"), path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def headers(root):
    producer = read(root / "config/foxcore-revision.txt").decode().strip()
    need(re.fullmatch("[0-9a-f]{40}", producer), "Full native producer revision required")
    compiled_raw = read(root / "config/foxcore-source-manifest.json")
    compiled = document(compiled_raw)
    binding = document(read(root / "config/foxcore-source-checkout.json"))
    helper = module(root / "scripts/core-source-binding.py")
    inputs = helper.validate(binding, compiled_raw, producer)
    shape = {"androidDeliveryProfile": "android4", "abis": ABIS, "targets": TARGETS}
    need(document(read(root / "config/foxcore-android-delivery.json")) == {"schema": 1, **shape},
         "Four-ABI source delivery config required")
    need(all(compiled.get(key) == value for key, value in shape.items()),
         "Compiled source delivery differs")
    folder = "source-legal" if CONSUMER == "guard" else "vpn-source-legal"
    snapshot_path = root / "third_party/licenses" / folder / "android4/snapshot.json"
    snapshot = document(read(snapshot_path))
    kind = "canonical-source-legal-delivery" if CONSUMER == "guard" else "canonical-vpn-source-legal-delivery"
    need(snapshot.get("schema") == 1 and snapshot.get("kind") == kind and
         snapshot.get("coreCommit") == producer and snapshot.get("sourceProducerProfile") == "android4" and
         snapshot.get("compiledInputs") == inputs and all(snapshot.get(key) == value for key, value in shape.items()),
         "Canonical legal producer or four-ABI coverage differs")
    if CONSUMER == "vpn":
        need(snapshot.get("consumer") == "vpn", "VPN legal consumer differs")
    native = snapshot.get("nativeLibraries")
    need(isinstance(native, dict), "Canonical native inventory missing")
    inventories = native.values() if CONSUMER == "guard" else [native]
    if CONSUMER == "guard":
        need(set(native) == {"core", "sentinel"}, "Guard native consumers differ")
    for inventory in inventories:
        need(isinstance(inventory, dict) and set(inventory) == set(TARGETS), "Canonical native ABI omitted")
        for target, abi in zip(TARGETS, ABIS):
            row = inventory[target]
            need(isinstance(row, dict) and set(row) == {"abi", "bytes", "sha256"} and row["abi"] == abi and
                 type(row["bytes"]) is int and 0 < row["bytes"] <= 128 * 1024**2 and
                 isinstance(row["sha256"], str) and re.fullmatch("[0-9a-f]{64}", row["sha256"]),
                 "Canonical native identity differs")
    return {"producer": producer, "binding": binding, "helper": helper,
            "snapshot": snapshot, "snapshotPath": snapshot_path}


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True,
                            env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}, timeout=120)
    need(len(result.stdout) <= 1048576 and len(result.stderr) <= 1048576, "Oversized Git metadata")
    return result.stdout.decode().strip()


def checkout(root, url, revision):
    need(root.is_dir() and not root.is_symlink(), "Expected srclib checkout missing")
    need(not git(root, "status", "--porcelain=v1", "--untracked-files=normal"), "Dirty srclib checkout refused")
    git(root, "-c", "maintenance.auto=false", "-c", "gc.auto=0", "fetch", "--no-tags", "--depth=1", url, revision)
    git(root, "checkout", "--detach", revision)
    need(git(root, "rev-parse", "HEAD") == revision, "srclib checkout revision differs")


def run(argv, env, timeout=7200, cwd=None):
    subprocess.run([str(value) for value in argv], cwd=cwd, env=env, check=True, timeout=timeout)


def environment(root, core_root):
    env = dict(os.environ)
    for name in ("FOXCORE_PREBUILT_DIR", "FOXHOLE_GOROOT", "GOROOT", "GOTOOLDIR", "GOFLAGS", "GO",
                 "GOROOT_BOOTSTRAP", "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "FOXHOLE_NATIVE_OFFLINE",
                 "FOXCORE_ANDROID_FEATURES", "FOXCORE_ANDROID_ABIS", "FOXCORE_JNI_OUTPUT", "FOXCORE_VPN_JNI_ROOT"):
        env.pop(name, None)
    cache = root.parent / ("foxhole-native-deps" if CONSUMER == "guard" else "foxhole-vpn-native-deps")
    env.update(CARGO_HOME=env.get("CARGO_HOME", str(Path.home() / ".cargo")),
               CARGO_TARGET_DIR=env.get("CARGO_TARGET_DIR", str(cache / "cargo-target")),
               CARGO_BUILD_JOBS="1", CARGO_INCREMENTAL="0",
               GOTOOLCHAIN="local", GOMAXPROCS="2", GOTELEMETRY="off", I2PD_BUILD_JOBS="2",
               PYTHONDONTWRITEBYTECODE="1", ANDROID_NDK_HOME=os.environ["ANDROID_NDK_HOME"],
               FOXCORE_SOURCE_ROOT=str(core_root))
    env["PATH"] = env["CARGO_HOME"] + "/bin:" + env["PATH"]
    toolchain = tomllib.loads(read(core_root / "rust-toolchain.toml").decode())["toolchain"]["channel"]
    need(isinstance(toolchain, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", toolchain), "Pinned stable Rust required")
    env["RUSTUP_TOOLCHAIN"] = toolchain
    name = "foxhole-native-deps" if CONSUMER == "guard" else "foxhole-vpn-native-deps"
    env["FOXHOLE_NATIVE_DEPS_DIR"] = str(root.parent / name)
    return env


def stage_vpn_legal(root, context):
    snapshot = context["snapshot"]
    declared = snapshot.get("licenseFiles")
    need(isinstance(declared, dict) and 0 < len(declared) <= 4096 and "MANIFEST.json" in declared and
         len({name.casefold() for name in declared}) == len(declared), "Canonical VPN legal inventory missing")
    payload = context["snapshotPath"].parent / "payload"
    need({str(path.relative_to(payload)) for path in payload.rglob("*") if path.is_file() or path.is_symlink()} == set(declared),
         "Canonical VPN legal payload differs")
    bodies = {}
    for name, row in declared.items():
        relative = PurePosixPath(name)
        need(isinstance(name, str) and relative.as_posix() == name and not relative.is_absolute() and
             all(part not in (".", "..", ".git", "AGENTS.md") for part in relative.parts), "Unsafe legal member path")
        need(isinstance(row, dict) and set(row) == {"bytes", "sha256"} and type(row["bytes"]) is int and
             0 < row["bytes"] <= 1048576 and isinstance(row["sha256"], str) and re.fullmatch("[0-9a-f]{64}", row["sha256"]),
             "Invalid canonical legal member")
        raw = read(payload / name, expected=row["sha256"])
        need(len(raw) == row["bytes"] and not raw.startswith((b"\x7fELF", b"PK\x03\x04", b"dex\n", b"\0asm")) and
             not any(value in raw for value in (b"/Users/", b"/srv/foxhole/", b"-----BEGIN PRIVATE KEY-----")),
             "Executable or private input is not legal data")
        raw.decode("utf-8")
        bodies[name] = raw
    manifest = document(bodies["MANIFEST.json"])
    need(manifest.get("core_revision") == context["producer"] and manifest.get("androidDeliveryProfile") == "android4" and
         manifest.get("abis") == ABIS and manifest.get("targets") == TARGETS, "Canonical legal manifest identity differs")
    records = manifest.get("files")
    need(isinstance(records, list) and len(records) == len({row["path"] for row in records}) and
         {row["path"] for row in records} == set(declared) - {"MANIFEST.json"} and
         all(declared[row["path"]] == {"sha256": row["sha256"], "bytes": row["bytes"]} for row in records),
         "Canonical legal manifest entries differ")
    target = root / "app/src/main/assets/licenses"
    need(target.is_dir() and not target.is_symlink(), "Disposable source license directory required")
    original_raw = read(target / "MANIFEST.json")
    original = document(original_raw)
    expected = {row["path"]: row for row in original["files"]}
    need({str(path.relative_to(target)) for path in target.rglob("*") if path.is_file() or path.is_symlink()} ==
         set(expected) | {"MANIFEST.json"}, "Unknown source license files refused")
    for name, row in expected.items():
        path = PurePosixPath(name)
        need(path.as_posix() == name and not path.is_absolute() and ".." not in path.parts, "Unsafe source license path")
        need(len(read(target / name, expected=row["sha256"])) == row["bytes"], "Original source license member differs")
    if digest(original_raw) == declared["MANIFEST.json"]["sha256"] and set(expected) == set(declared) - {"MANIFEST.json"} and all(
        {"bytes": row["bytes"], "sha256": row["sha256"]} == declared[name] for name, row in expected.items()
    ):
        return
    temporary = Path(tempfile.mkdtemp(prefix=".fdroid-licenses-", dir=target.parent))
    try:
        for name, raw in bodies.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            path.chmod(0o644)
        shutil.rmtree(target)
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def verify_vpn_native(root, context):
    native = root / "native-libs"
    library = "libfoxhole_native.so"
    expected_paths = {abi + "/" + library for abi in ABIS}
    need({str(path.relative_to(native)) for path in native.rglob("*") if path.is_file() or path.is_symlink()} ==
         expected_paths | {"foxcore-vpn-build.json"}, "Fresh VPN native inventory differs")
    receipt = document(read(native / "foxcore-vpn-build.json"))
    need(receipt.get("core_revision") == context["producer"], "Native compiled reference revision differs")
    features = read(root / "config/foxcore-features.txt").decode().strip()
    need(receipt.get("features") == features and
         not {"tor", "i2p", "onion-service", "shipped"} & set(features.split(",")), "Native VPN features differ")
    declared = receipt.get("files")
    need(isinstance(declared, list) and len(declared) == len(ABIS) and
         all(isinstance(row, dict) and set(row) == {"path", "sha256"} for row in declared),
         "Normal native producer inventory differs")
    pins = {row["path"]: row["sha256"] for row in declared}
    need(set(pins) == expected_paths and len(pins) == len(declared) and
         all(isinstance(pin, str) and re.fullmatch("[0-9a-f]{64}", pin) for pin in pins.values()),
         "Normal native producer hashes or ABI coverage differ")
    verified = []
    for target, abi in zip(TARGETS, ABIS):
        relative = abi + "/" + library
        raw = read(native / relative, 128 * 1024**2, pins[relative], nlinks=(1, 2))
        elf_class, machine = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}[abi]
        need(len(raw) >= 64 and raw[:4] == b"\x7fELF" and raw[4] == elf_class and raw[5] == 1 and
             int.from_bytes(raw[18:20], "little") == machine, "Fresh VPN ELF identity differs")
        reference = context["snapshot"]["nativeLibraries"][target]
        verified.append({"path": relative, "abi": abi, "target": target, "bytes": len(raw), "sha256": digest(raw),
                         "matchesReferenceNative": len(raw) == reference["bytes"] and digest(raw) == reference["sha256"]})
    return {"schema": 1, "kind": "verified-fdroid-native-source-build", "consumer": "vpn",
            "sourceCommit": context["binding"]["sourceCommit"], "sourceTree": context["binding"]["sourceTree"],
            "referenceProducerCommit": context["producer"], "features": features, "files": verified,
            "referenceNativeHashesUnchanged": True, "APKReproducibilityVerified": False}



DEPENDENCY_INIT = r"""
gradle.projectsEvaluated {
    def root = gradle.rootProject
    def resolvers = root.allprojects.collect { project ->
        project.tasks.register('foxholeFdroidResolveProjectDependencies') {
            doLast {
                def selected = project.configurations.findAll { configuration ->
                    def name = configuration.name.toLowerCase()
                    configuration.canBeResolved && (name.contains('release') || name.startsWith('kotlincompiler') ||
                        name == 'kotlinbuildtoolsapiclasspath' || name.contains('androidjdkimage'))
                }
                selected.addAll(project.buildscript.configurations.findAll { it.canBeResolved })
                selected.unique().each { configuration ->
                    configuration.incoming.artifactView {
                        componentFilter { component ->
                            component instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
                        }
                    }.files.files.each { artifact ->
                        if (!artifact.isFile()) throw new GradleException('Dependency artifact is not a regular file')
                    }
                }
            }
        }
    }
    root.tasks.register('foxholeFdroidResolveDependencies') {
        dependsOn(resolvers)
    }
}
"""


def seed_maven_dependencies(root, env):
    deps = Path(env["FOXHOLE_NATIVE_DEPS_DIR"])
    fd, name = tempfile.mkstemp(prefix="fdroid-dependencies-", suffix=".init.gradle", dir=deps)
    path = Path(name)
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(DEPENDENCY_INIT)
        argv = ["gradle", "--no-daemon", "--console=plain", "--dependency-verification=strict", "--no-build-cache", "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8",
                "-Pfoxhole.splitApks=false", "-Pfoxhole.coreDocker=false", "-Pfoxhole.updateChannel=fdroid",
                "-Pfoxhole.nativeOffline=true", "-Pfoxhole.abis=" + " ".join(ABIS), "--init-script", path,
                ":foxholeFdroidResolveDependencies"]
        if CONSUMER == "vpn":
            argv.append("-Pfoxhole.nativeDocker=false")
        run(argv, env, timeout=1800, cwd=root)
    finally:
        path.unlink(missing_ok=True)


def prebuild(root, core_root, go_root, context, env):
    context["helper"].resolve(root, core_root, context["producer"])
    installed = subprocess.check_output(["rustup", "toolchain", "list"], env=env, timeout=30).decode().splitlines()
    if not any(line.split()[0].split("-")[0] == env["RUSTUP_TOOLCHAIN"] for line in installed):
        run(["rustup", "toolchain", "install", env["RUSTUP_TOOLCHAIN"], "--profile", "minimal"], env)
    installed_targets = set(subprocess.check_output(["rustup", "target", "list", "--installed", "--toolchain", env["RUSTUP_TOOLCHAIN"]], env=env, timeout=30).decode().splitlines())
    missing = [target for target in TARGETS if target not in installed_targets]
    if missing:
        run(["rustup", "target", "add", "--toolchain", env["RUSTUP_TOOLCHAIN"], *missing], env)
    # cargo-ndk is build tooling; application crates are compiled only in build.
    tool = subprocess.run(["cargo", "ndk", "--version"], env=env, capture_output=True, timeout=30)
    if tool.returncode != 0 or tool.stdout.decode().strip() != "cargo-ndk 4.1.2":
        run(["cargo", "install", "cargo-ndk", "--version", "4.1.2", "--locked"], env)
    run(["cargo", "fetch", "--locked", "--manifest-path", core_root / "Cargo.toml"], env)
    deps = Path(env["FOXHOLE_NATIVE_DEPS_DIR"])
    deps.mkdir(parents=True, exist_ok=True)
    components = ["graphics"]
    if CONSUMER == "guard":
        need(go_root is not None, "Guard Go srclib required")
        checkout(go_root, GO_URL, GO_COMMIT)
        need(read(go_root / "VERSION").decode().splitlines()[0] == GO_VERSION, "Go srclib version differs")
        env["TOR_TRANSPORT_GO"] = GO_VERSION
        run(["cargo", "fetch", "--locked", "--manifest-path", core_root / "native/sentinel-yara/Cargo.toml"], env)
        run(["bash", "scripts/fetch-native-deps.sh"], env)
        archive = deps / (GO_VERSION + ".linux-amd64.tar.gz")
        need(archive.lstat().st_size == BOOTSTRAP_BYTES, "Go bootstrap archive size differs")
        read(archive, BOOTSTRAP_BYTES, BOOTSTRAP_SHA256)
        components.append("sodium")
    else:
        need(go_root is None, "VPN does not use a Go srclib")
        stage_vpn_legal(root, context)
    seed = Path(tempfile.mkdtemp(prefix="page-seed-", dir=deps))
    for component in components:
        run(["python3", "scripts/prepare-page-aligned-native-inputs.py", "--repo", root,
             "--destination", seed / component, "--cache", deps / "page-publisher-cache", "--component", component], env)
    seed_maven_dependencies(root, env)


def guard_source_native_manifest(root, core_root, context):
    actual = context["helper"].resolve(root, core_root, context["producer"])
    native = {}
    for kind, folder, library in (
        ("core", root / "app/build/fdroidNativeSource/core", "libfoxhole_native.so"),
        ("sentinel", root / "app/build/fdroidNativeSource/sentinel/jniLibs", "libfoxhole_sentinel_yara.so"),
    ):
        need({str(path.relative_to(folder)) for path in folder.rglob("*") if path.is_file() or path.is_symlink()} ==
             {abi + "/" + library for abi in ABIS}, "Normal source native inventory differs")
        rows = {}
        for target, abi in zip(TARGETS, ABIS):
            raw = read(folder / abi / library, 128 * 1024**2, nlinks=(1, 2))
            elf_class, machine = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}[abi]
            need(len(raw) >= 64 and raw[:4] == b"\x7fELF" and raw[4] == elf_class and raw[5] == 1 and
                 int.from_bytes(raw[18:20], "little") == machine, "Normal source native ELF identity differs")
            rows[target] = {"abi": abi, "bytes": len(raw), "sha256": digest(raw)}
        native[kind] = rows
    report = root / "app/build/reports/fdroid/native-source-build.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({
        "schema": 1, "kind": "normal-fdroid-native-source-build",
        "sourceCommit": actual["sourceCommit"], "sourceTree": actual["sourceTree"],
        "referenceProducerCommit": context["producer"],
        "compiledSourceManifestSha256": digest(read(root / "config/foxcore-source-manifest.json")),
        "androidDeliveryProfile": "android4", "abis": ABIS, "targets": TARGETS, "nativeLibraries": native,
    }, indent=2) + "\n")
    return report


def build(root, core_root, go_root, context, env):
    context["helper"].resolve(root, core_root, context["producer"])
    env.update(CARGO_NET_OFFLINE="true", FOXHOLE_NATIVE_OFFLINE="1", GOPROXY="off", GOSUMDB="off")
    if CONSUMER == "guard":
        need(go_root is not None and git(go_root, "rev-parse", "HEAD") == GO_COMMIT, "Pinned Go source required")
        deps = Path(env["FOXHOLE_NATIVE_DEPS_DIR"])
        env.update(GOROOT_BOOTSTRAP=str(deps / GO_VERSION), GOCACHE=str(deps / "go-tool-source-cache"), GOFLAGS="-p=2")
        run(["bash", "make.bash"], env, cwd=go_root / "src")
        env.pop("GOFLAGS")
        env.update(FOXHOLE_GOROOT=str(go_root), GO=str(go_root / "bin/go"), TOR_TRANSPORT_GO=GO_VERSION,
                   I2PD_ABIS=" ".join(ABIS), TOR_TRANSPORT_ABIS=" ".join(ABIS), GOCACHE=str(deps / "go-native-build-cache"))
        env["PATH"] = str(go_root / "bin") + ":" + env["PATH"]
        need(subprocess.check_output([str(go_root / "bin/go"), "env", "GOVERSION"], env=env, timeout=30).decode().strip() == GO_VERSION,
             "Fresh source-built Go version differs")
    else:
        env.update(FOXCORE_ANDROID_ABIS=" ".join(ABIS), FOXCORE_VPN_JNI_ROOT=str(root / "native-libs"))
        need(not (root / "native-libs").exists(), "Fresh normal VPN native output required")
        run(["bash", "scripts/build-native.sh"], env)
        native_source = verify_vpn_native(root, context)
        report = root / "app/build/reports/fdroid/native-source-build.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(native_source, indent=2) + "\n")
    cache = root / "app/build/pageAlignedNativeWork/publisher-cache"
    cache.mkdir(parents=True, exist_ok=True)
    names = ["graphics-path-1.0.1-native.tar", "graphics-path-1.0.1.aar", "graphics-path-1.0.1-sources.jar", "graphics-source-LICENSE.base64"]
    if CONSUMER == "guard":
        names.extend(["libsodium-1.0.20.tar.gz", "lazysodium-android-5.2.0.aar"])
    for name in names:
        shutil.copyfile(Path(env["FOXHOLE_NATIVE_DEPS_DIR"]) / "page-publisher-cache" / name, cache / name)
    gradle = ["gradle", "--no-daemon", "--console=plain", "--dependency-verification=strict", "--offline", "--no-build-cache", "--max-workers=1",
              "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8",
              "-Pkotlin.compiler.execution.strategy=in-process", "-Pfoxhole.splitApks=false", "-Pfoxhole.coreDocker=false",
              "-Pfoxhole.updateChannel=fdroid", "-Pfoxhole.nativeOffline=true", "-Pfoxhole.abis=" + " ".join(ABIS), ":app:assembleRelease"]
    if CONSUMER == "vpn":
        gradle.extend(["-Pfoxhole.nativeDocker=false", ":app:verifyNativePayload"])
    else:
        # Rust fat LTO needs its memory budget before Gradle starts its JVM.
        core_env = dict(env, FOXCORE_ANDROID_ABIS=" ".join(ABIS),
                        FOXCORE_JNI_OUTPUT=str(root / "app/build/fdroidNativeSource/core"))
        run(["bash", core_root / "scripts/android-build.sh"], core_env, cwd=core_root)
        sentinel_env = dict(env, FOXCORE_SENTINEL_SOURCE_ROOT=str(core_root / "native/sentinel-yara"),
                            FOXCORE_SENTINEL_OUTPUT_ROOT=str(root / "app/build/fdroidNativeSource/sentinel"),
                            FOXCORE_SENTINEL_ABIS=" ".join(ABIS))
        run(["bash", core_root / "scripts/sentinel-android-build.sh"], sentinel_env, cwd=core_root)
        source_manifest = guard_source_native_manifest(root, core_root, context)
        gradle.insert(-1, "-Pfoxhole.sourceNativeManifest=" + str(source_manifest))
    run(gradle, env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["prebuild", "build"])
    parser.add_argument("--core-root", type=Path, required=True)
    parser.add_argument("--go-root", type=Path)
    args = parser.parse_args()
    need(CONSUMER in ("guard", "vpn"), "Consumer-specific helper required")
    root = Path.cwd()
    core_root = args.core_root.absolute()
    go_root = args.go_root.absolute() if args.go_root is not None else None
    context = headers(root)
    if args.phase == "prebuild":
        checkout(core_root, CORE_URL, context["binding"]["sourceCommit"])
    env = environment(root, core_root)
    (prebuild if args.phase == "prebuild" else build)(root, core_root, go_root, context, env)


if __name__ == "__main__":
    main()
