#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import tarfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
NATIVE_PINS = {
    "openssl": ("3.5.8", "a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2"),
    "boost": ("1.84.0", "cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454"),
    "go": ("go1.26.8", None),
    "lyrebird": ("0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1", None),
    "conjure": ("0090962226b82aa4a8fc38506f9b98de67d0781e", None),
}
I2PD_VERSION_SHA256 = "9808e3322ee2f2a31e69bcd7d4bf6b6cbfc0bdd171db5f7f999d772f926372ec"
I2PD_LICENSE_SHA256 = "eb5ac2a5ede8cd6bed9e6d93ad943119a73bfaba378f21bafa307f9b026b2034"
STATIC_HASHES = {
    "third_party/fonts/Tiny5-OFL.txt": "6fe7d64407c69d187748206265977654747d3e2fe9e38e45a62cd03ec4770df6",
    "third_party/fonts/JetBrainsMono-OFL.txt": "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad",
    "third_party/icons/Tabler-MIT.txt": "b740a1d46122672da62833e97f7e7c8a13fa85cbc7445b584b297cc00dde93db",
    "third_party/flags/flag-icons-LICENSE.txt": "8f1195d55a2fd315a07d812328470ca9ba2abb78c8d317ff19619d5125e00cea",
    "third_party/flags/app-assets.sha256": "31c4a3af0de5b33cb862081551a078ff03afd5914778d6bac7560775b3fa0544",
    "third_party/tor-config/README.md": "d34725748e5f266fef67ce39debd744cf26ef3f1fc6cef198e2ee931d0eaa071",
    "third_party/tor-config/assets.sha256": "fab5b8f1b40b1d85d2de649caacad123acc7ffc4136e51e747baf4c65d89f203",
    "third_party/licenses/SQLCipher-BSD-3-Clause.txt":
        "09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14",
    "third_party/licenses/libsodium-ISC.txt": "43964d976a6db3fb986af689d05f8ca0e9971878bccae709750dac8fdc4a99cf",
    "third_party/licenses/lazysodium-MPL-2.0.txt": "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5",
    "third_party/licenses/JNA-LICENSE.txt": "07c938b23950ab7d47a24ef35f9f5da3a05ae164278dc959ad6994135ed59ff1",
    "third_party/licenses/JNA-Apache-2.0.txt": "0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594",
    "third_party/licenses/JNA-LGPL-2.1.txt": "eea173a556abac0370461e57e12aab266894ea6be3874c2be05fd87871f75449",
}
LICENSE_FILE = re.compile(r"^(license|copying|notice)(?:[._-].*)?$", re.IGNORECASE)


def transport_binary_paths(abis):
    if not abis or len(set(abis)) != len(abis) or any(abi not in {"arm64-v8a", "armeabi-v7a", "x86_64"} for abi in abis):
        raise ValueError("Expected unique supported Android ABIs")
    return [(name, REPO_ROOT / "app/src/main/assets/tor" / abi / "tor/pluggable_transports" / name)
            for abi in abis for name in ("lyrebird", "conjure-client")]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_checked(path: Path, expected_hash: str | None = None) -> bytes:
    if not path.is_file():
        raise SystemExit(f"missing license input: {path}")
    data = path.read_bytes()
    if expected_hash and sha256(data) != expected_hash:
        raise SystemExit(f"license input hash mismatch: {path}")
    return data


def write(output: Path, relative: str, data: bytes) -> None:
    target = output / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def copy(output: Path, source: Path, relative: str, expected_hash: str | None = None) -> None:
    write(output, relative, read_checked(source, expected_hash))


def require_native_pins() -> None:
    pins = (REPO_ROOT / "scripts/native-deps.sh").read_text()
    required = (
        'openssl_ver="${I2PD_OPENSSL_VERSION:-3.5.8}"',
        'boost_ver="${I2PD_BOOST_VERSION:-1.84.0}"',
        'go_version="${TOR_TRANSPORT_GO:-go1.26.8}"',
        'lyrebird_commit="0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1"',
        'conjure_commit="0090962226b82aa4a8fc38506f9b98de67d0781e"',
    )
    missing = [value for value in required if value not in pins]
    if missing:
        raise SystemExit("native dependency pins changed; review the license bundle: " + ", ".join(missing))


def require_android_pins() -> None:
    catalog = (REPO_ROOT / "gradle/libs.versions.toml").read_text()
    required = ('sqlcipher = "4.17.0"', 'lazysodium = "5.2.0"', 'jna = "5.19.1"')
    missing = [value for value in required if value not in catalog]
    if missing:
        raise SystemExit("Android dependency pins changed; review the license bundle: " + ", ".join(missing))


def require_flag_assets() -> None:
    manifest_path = REPO_ROOT / "third_party/flags/app-assets.sha256"
    manifest_hash = STATIC_HASHES[str(manifest_path.relative_to(REPO_ROOT))]
    manifest_lines = read_checked(manifest_path, manifest_hash).decode().splitlines()
    entries: dict[str, str] = {}
    for line in manifest_lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-z0-9-]+\.png)", line)
        if match is None or match.group(2) in entries:
            raise SystemExit(f"invalid flag asset manifest line: {line}")
        entries[match.group(2)] = match.group(1)
    if len(entries) != 270:
        raise SystemExit(f"expected 270 pinned flag assets, found {len(entries)}")

    assets = REPO_ROOT / "app/src/main/assets/flags"
    actual_names = {path.name for path in assets.glob("*.png") if path.is_file()}
    if actual_names != set(entries):
        missing = sorted(set(entries) - actual_names)
        unexpected = sorted(actual_names - set(entries))
        raise SystemExit(f"flag asset inventory mismatch: missing={missing}, unexpected={unexpected}")
    for name, expected_hash in entries.items():
        data = read_checked(assets / name, expected_hash)
        if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            raise SystemExit(f"invalid PNG flag asset: {name}")
        width, height, bit_depth, colour_type, compression, filtering, interlace = struct.unpack(
            ">IIBBBBB",
            data[16:29],
        )
        actual_format = (width, height, bit_depth, colour_type, compression, filtering, interlace)
        if actual_format != (256, 192, 8, 6, 0, 0, 0):
            raise SystemExit(
                f"unexpected flag asset format for {name}: "
                f"{width}x{height}, bit_depth={bit_depth}, colour_type={colour_type}, "
                f"compression={compression}, filtering={filtering}, interlace={interlace}"
            )


def require_tor_config() -> None:
    manifest_path = REPO_ROOT / "third_party/tor-config/assets.sha256"
    manifest_hash = STATIC_HASHES[str(manifest_path.relative_to(REPO_ROOT))]
    entries: dict[str, str] = {}
    for line in read_checked(manifest_path, manifest_hash).decode().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_./-]+)", line)
        if match is None or match.group(2) in entries:
            raise SystemExit(f"invalid Tor config manifest line: {line}")
        entries[match.group(2)] = match.group(1)
    expected_members = {
        "data/torrc-defaults",
        "tor/pluggable_transports/pt_config.json",
        "tor/pluggable_transports/README.CONJURE.md",
    }
    if set(entries) != expected_members:
        raise SystemExit("Tor config manifest has an unexpected member inventory")

    versions = {
        "arm64-v8a": "tor-expert-bundle-android-aarch64-15.0.9-foxhole-allbridges3",
        "armeabi-v7a": "tor-expert-bundle-android-armv7-15.0.9-foxhole-allbridges3",
        "x86": "tor-expert-bundle-android-x86-15.0.9-foxhole-allbridges3",
        "x86_64": "tor-expert-bundle-android-x86_64-15.0.9-foxhole-allbridges3",
    }
    for abi, expected_version in versions.items():
        abi_root = REPO_ROOT / "app/src/main/assets/tor" / abi
        for name in (".version", "bundle.version"):
            version = read_checked(abi_root / name).decode().strip()
            if version != expected_version:
                raise SystemExit(f"unexpected Tor bundle marker for {abi}/{name}: {version}")
        for relative in ("data/torrc-defaults", "tor/pluggable_transports/pt_config.json"):
            read_checked(abi_root / relative, entries[relative])
    for abi in ("arm64-v8a", "armeabi-v7a"):
        read_checked(
            REPO_ROOT / "app/src/main/assets/tor" / abi / "tor/pluggable_transports/README.CONJURE.md",
            entries["tor/pluggable_transports/README.CONJURE.md"],
        )


def git_head(source: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def git_blob(source: Path, revision: str, relative: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(source), "show", f"{revision}:{relative}"],
        check=True,
        capture_output=True,
    )
    if not result.stdout:
        raise SystemExit(f"empty pinned FoxCore file: {relative}")
    return result.stdout


def extract_member(archive: Path, member_name: str, expected_hash: str) -> bytes:
    read_checked(archive, expected_hash)
    with tarfile.open(archive) as bundle:
        matches = [member for member in bundle.getmembers() if member.isfile() and member.name == member_name]
        if len(matches) != 1:
            raise SystemExit(f"expected {member_name} in {archive}, found {len(matches)}")
        stream = bundle.extractfile(matches[0])
        if stream is None:
            raise SystemExit(f"could not extract {matches[0].name} from {archive}")
        return stream.read()


def go_escape(value: str) -> str:
    return "".join(f"!{char.lower()}" if "A" <= char <= "Z" else char for char in value)


def go_modules(go_binary: Path, executable: Path) -> list[dict[str, str]]:
    result = subprocess.run(
        [str(go_binary), "version", "-m", str(executable)],
        check=True,
        capture_output=True,
        text=True,
    )
    modules: list[dict[str, str]] = []
    pending: dict[str, str] | None = None
    for line in result.stdout.splitlines():
        fields = line.split("\t")
        kind = fields[1] if len(fields) > 1 else ""
        if kind == "dep":
            if pending:
                modules.append(pending)
            pending = {
                "declaredPath": fields[2],
                "declaredVersion": fields[3],
                "path": fields[2],
                "version": fields[3],
            }
        elif kind == "=>" and pending:
            if fields[2] == "../conjure-patched":
                if pending["declaredPath"] != "github.com/refraction-networking/conjure" or pending["declaredVersion"] != "v0.9.1":
                    raise SystemExit("Unexpected patched Conjure module identity")
                pending["replacementPath"] = fields[2]
                pending["patchManifestSha256"] = sha256(read_checked(REPO_ROOT / "config/native/conjure/stun-v3-patch.json"))
                pending["patchScriptSha256"] = sha256(read_checked(REPO_ROOT / "scripts/prepare-tor-dependency.py"))
            else:
                pending["path"] = fields[2]
                pending["version"] = fields[3]
        elif pending:
            modules.append(pending)
            pending = None
    if pending:
        modules.append(pending)
    return modules


def require_lyrebird_composite_license(binary: Path) -> None:
    data = read_checked(binary)
    source_root = b"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/"
    required_packages = (
        source_root + b"transports/meeklite",
        source_root + b"internal/x25519ell2",
    )
    missing = [package.decode() for package in required_packages if package not in data]
    if missing:
        raise SystemExit(
            "lyrebird no longer contains the GPL-covered packages named by the notice: " + ", ".join(missing)
        )


def module_directory_name(path: str, version: str) -> str:
    readable = re.sub(r"[^A-Za-z0-9._+-]+", "_", f"{path}@{version}").strip("_")
    digest = sha256(f"{path}@{version}".encode())[:12]
    return f"{readable[:100]}-{digest}"


def is_license_notice(source: Path, candidate: Path) -> bool:
    relative = candidate.relative_to(source)
    return (
        candidate.is_file()
        and not candidate.is_symlink()
        and not any(part.startswith(".") for part in relative.parts)
        and LICENSE_FILE.fullmatch(candidate.name) is not None
    )


def copy_go_module_licenses(output: Path, module_cache: Path, modules: list[dict[str, object]]) -> None:
    for module in modules:
        path = str(module["path"])
        version = str(module["version"])
        source = module_cache / f"{go_escape(path)}@{go_escape(version)}"
        if module.get("replacementPath") == "../conjure-patched":
            source = module_cache.parent / "tor-transports/conjure-patched"
        if not source.is_dir():
            raise SystemExit(f"Go module cache entry is missing: {source}")
        licenses = sorted(
            candidate
            for candidate in source.rglob("*")
            if is_license_notice(source, candidate)
        )
        if not licenses:
            raise SystemExit(f"Go module has no license or notice file: {path}@{version}")
        destination = f"tor/go-modules/{module_directory_name(path, version)}"
        write(output, f"{destination}/MODULE.txt", f"{path}\n{version}\n".encode())
        for license_file in licenses:
            relative = license_file.relative_to(source).as_posix()
            copy(output, license_file, f"{destination}/{relative}")


def resolve_ndk(configured: str | None) -> Path:
    candidates = [configured, os.environ.get("ANDROID_NDK_HOME")]
    for sdk_env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(sdk_env)
        if sdk:
            candidates.append(str(Path(sdk) / "ndk/29.0.14206865"))
    for candidate in candidates:
        if candidate and (Path(candidate) / "NOTICE.toolchain").is_file():
            return Path(candidate)
    raise SystemExit("Android NDK 29.0.14206865 license notices were not found")


def generate(args: argparse.Namespace) -> None:
    output = Path(args.output).resolve()
    if output.exists() or output.is_symlink():
        raise SystemExit(f"license output must not already exist: {output}")
    output.mkdir(parents=True)
    require_native_pins()
    require_android_pins()
    require_flag_assets()
    require_tor_config()

    native_root = Path(
        args.native_deps_root
        or os.environ.get("FOXHOLE_NATIVE_DEPS_DIR", REPO_ROOT.parent / "foxhole-native-deps")
    )
    foxcore_root = Path(args.foxcore_root or os.environ.get("FOXCORE_SOURCE_ROOT", REPO_ROOT.parent / "foxhole-core"))
    ndk_root = resolve_ndk(args.ndk_root)

    copy(output, REPO_ROOT / "LICENSE", "FoxHole-Guard-GPL-3.0-or-later.txt")
    copy(output, REPO_ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")
    for relative, expected in STATIC_HASHES.items():
        destination = relative.removeprefix("third_party/")
        if destination.startswith("licenses/"):
            destination = destination.replace("licenses/", "android/", 1)
        if destination == "tor-config/README.md":
            destination = "tor-config/PROVENANCE.txt"
        copy(output, REPO_ROOT / relative, destination, expected)
    for relative in (
        "third_party/fonts/README.md",
        "third_party/icons/README.md",
        "third_party/flags/README.md",
        "third_party/licenses/README.md",
    ):
        destination = relative.removeprefix("third_party/")
        if destination == "licenses/README.md":
            destination = "android/README.md"
        copy(output, REPO_ROOT / relative, destination)

    tor_config_root = REPO_ROOT / "app/src/main/assets/tor/arm64-v8a"
    copy(
        output,
        tor_config_root / "data/torrc-defaults",
        "tor-config/upstream-members/torrc-defaults.txt",
    )
    copy(
        output,
        tor_config_root / "tor/pluggable_transports/pt_config.json",
        "tor-config/upstream-members/pt_config.json",
    )
    copy(
        output,
        tor_config_root / "tor/pluggable_transports/README.CONJURE.md",
        "tor-config/upstream-members/README.CONJURE.md",
    )

    i2pd_version = read_checked(REPO_ROOT / "third_party/i2pd.version", I2PD_VERSION_SHA256).decode()
    if "ref=2.61.0" not in i2pd_version or "commit=635b013a612ff47278ef02acf8580a28e10e26c5" not in i2pd_version:
        raise SystemExit("i2pd pin changed; review the license bundle")
    copy(
        output,
        REPO_ROOT / "third_party/i2pd/LICENSE",
        "i2pd/i2pd-BSD-3-Clause.txt",
        I2PD_LICENSE_SHA256,
    )
    write(output, "i2pd/VERSION.txt", i2pd_version.encode())

    i2pd_cache = native_root / "i2pd-deps"
    openssl_version, openssl_hash = NATIVE_PINS["openssl"]
    boost_version, boost_hash = NATIVE_PINS["boost"]
    write(
        output,
        f"i2pd/OpenSSL-{openssl_version}-Apache-2.0.txt",
        extract_member(
            i2pd_cache / f"openssl-{openssl_version}.tgz",
            f"openssl-{openssl_version}/LICENSE.txt",
            str(openssl_hash),
        ),
    )
    boost_name = f"boost_{boost_version.replace('.', '_')}"
    write(
        output,
        f"i2pd/Boost-{boost_version}-BSL-1.0.txt",
        extract_member(
            i2pd_cache / f"{boost_name}.tar.bz2",
            f"{boost_name}/LICENSE_1_0.txt",
            str(boost_hash),
        ),
    )
    ndk_properties = read_checked(ndk_root / "source.properties")
    if b"Pkg.Revision = 29.0.14206865" not in ndk_properties:
        raise SystemExit(f"unexpected Android NDK revision: {ndk_root}")
    copy(output, ndk_root / "NOTICE", "i2pd/Android-NDK-29-NOTICE.txt")
    copy(output, ndk_root / "NOTICE.toolchain", "i2pd/Android-NDK-29-NOTICE.toolchain.txt")

    tor_root = native_root / "tor-transports"
    lyrebird = tor_root / "lyrebird"
    conjure = tor_root / "conjure"
    if git_head(lyrebird) != NATIVE_PINS["lyrebird"][0] or git_head(conjure) != NATIVE_PINS["conjure"][0]:
        raise SystemExit("Tor transport source checkout does not match scripts/native-deps.sh")
    copy(output, lyrebird / "LICENSE", "tor/lyrebird-BSD-3-Clause.txt")
    copy(output, lyrebird / "LICENSE-GPL3.txt", "tor/lyrebird-GPL-3.0-or-later.txt")
    copy(output, conjure / "LICENSE", "tor/conjure-client-BSD-3-Clause.txt")

    go_version = NATIVE_PINS["go"][0]
    go_root = native_root / go_version
    go_binary = go_root / "bin/go"
    copy(output, go_root / "LICENSE", f"tor/Go-{go_version.removeprefix('go')}-BSD-3-Clause.txt")
    binary_paths = transport_binary_paths(args.android_abis.split())
    inventory: dict[tuple[str, str], dict[str, object]] = {}
    for binary_name, binary in binary_paths:
        if not binary.is_file():
            raise SystemExit(f"Tor transport binary is missing: {binary}")
        if binary_name == "lyrebird":
            require_lyrebird_composite_license(binary)
        binary_modules = go_modules(go_binary, binary)
        if not binary_modules:
            raise SystemExit(f"{binary_name} has no embedded Go dependency inventory")
        for module in binary_modules:
            key = (module["path"], module["version"])
            current = inventory.setdefault(key, {**module, "binaries": []})
            if binary_name not in current["binaries"]:
                current["binaries"].append(binary_name)
    modules = sorted(inventory.values(), key=lambda item: (str(item["path"]), str(item["version"])))
    write(output, "tor/GO-MODULES.json", (json.dumps(modules, indent=2, sort_keys=True) + "\n").encode())
    copy_go_module_licenses(output, native_root / "gomodcache", modules)
    copy(output, REPO_ROOT / "config/native/conjure/stun-v3-patch.json", "tor/CONJURE-PATCH.json")
    copy(output, REPO_ROOT / "scripts/prepare-tor-dependency.py", "tor/CONJURE-PATCH.py")

    core_revision = (REPO_ROOT / "config/foxcore-revision.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", core_revision):
        raise SystemExit("invalid FoxCore revision pin")
    core_files = {
        "LICENSE": "foxcore/FoxHole-Core-GPL-3.0-or-later.txt",
        "THIRD_PARTY_NOTICES.md": "foxcore/THIRD_PARTY_NOTICES.md",
        "sbom/foxcore-aarch64-linux-android.cdx.json": "foxcore/foxcore-aarch64-linux-android.cdx.json",
    }
    for source, destination in core_files.items():
        write(output, destination, git_blob(foxcore_root, core_revision, source))
    core_sbom = json.loads((output / core_files["sbom/foxcore-aarch64-linux-android.cdx.json"]).read_text())
    missing_core_licenses = [
        component.get("bom-ref", component.get("name", "unknown"))
        for component in core_sbom.get("components", [])
        if not component.get("licenses")
    ]
    if missing_core_licenses:
        raise SystemExit("FoxCore SBOM contains components without licenses")
    write(output, "foxcore/REVISION.txt", f"{core_revision}\n".encode())

    readme = (
        "FoxHole Guard bundled license material\n\n"
        "THIRD_PARTY_NOTICES.md is the human-readable inventory. This directory also contains "
        "the complete upstream license and NOTICE files for bundled native code, fonts, icons, "
        "flags and Android native dependencies. FoxCore, Tor transport dependencies and the "
        "Tor Project integration configuration are pinned to the exact release inputs. "
        "SHA256SUMS authenticates every file in this bundle.\n"
    )
    write(output, "README.txt", readme.encode())
    files = sorted(path for path in output.rglob("*") if path.is_file())
    manifest = {
        "schema": 1,
        "foxcoreRevision": core_revision,
        "files": [
            {
                "path": path.relative_to(output).as_posix(),
                "sha256": sha256(path.read_bytes()),
                "size": path.stat().st_size,
            }
            for path in files
        ],
    }
    write(output, "MANIFEST.json", (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode())
    sums = "".join(
        f"{sha256(path.read_bytes())}  {path.relative_to(output).as_posix()}\n"
        for path in sorted(path for path in output.rglob("*") if path.is_file())
    )
    write(output, "SHA256SUMS", sums.encode())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--foxcore-root")
    parser.add_argument("--native-deps-root")
    parser.add_argument("--ndk-root")
    parser.add_argument("--android-abis", default="arm64-v8a")
    generate(parser.parse_args())


if __name__ == "__main__":
    main()
