#!/usr/bin/env python3

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import tarfile
import tomllib
from types import MappingProxyType
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
NATIVE_PINS = {
    "openssl": ("3.5.8", "a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2"),
    "boost": ("1.84.0", "cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454"),
    "go": ("go1.26.10", None),
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
RUST_STD_HASHES = {
    "LICENSE-APACHE": "62c7a1e35f56406896d7aa7ca52d0cc0d272ac022b5d2796e7d6905db8a3636a",
    "PROVENANCE.json": "d65e1b9ca0ed1834097b67ea77ebe7dbcf1aaf6b598be2123aff1dd40a77a4d0",
    "COPYRIGHT-library.html": "0a65bb747c49c7bb816cbc7188319bd6e4e8d08091c1190b8a3c0971c47968ed",
    "LICENSE-MIT": "b71bd43a069ca0641a9ecfe585ca7b3c53b5cc1608f8b68321168698e28b5ea1"
}

RUST_STD_ANDROID4_PROVENANCE_SHA256 = '63fbae7ee5a594ac86bb32db2044ecc2e9464a47f6e75864746a29893f4f0533'  # Filled only by the actual android4 input factory.

RUST_STD_ARMV7_PROVENANCE_SHA256 = "6324f8e27a3d6b461283d4713360f0c7dcc8b7648398a57deaf01cc288215874"

LICENSE_FILE = re.compile(r"^(license|copying|notice)(?:[._-].*)?$", re.IGNORECASE)


def transport_binary_paths(abis):
    if not abis or len(set(abis)) != len(abis) or any(abi not in {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"} for abi in abis):
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
        'go_version="${TOR_TRANSPORT_GO:-go1.26.10}"',
        'lyrebird_commit="0b10edbb61e0ca6fb70c7d57aeaabf315f1fade1"',
        'conjure_commit="0090962226b82aa4a8fc38506f9b98de67d0781e"',
    )
    missing = [value for value in required if value not in pins]
    if missing:
        raise SystemExit("native dependency pins changed; review the license bundle: " + ", ".join(missing))


def require_android_pins() -> None:
    catalog = (REPO_ROOT / "gradle/libs.versions.toml").read_text()
    required = ('sqlcipher = "4.19.1"', 'lazysodium = "5.2.0"', 'jna = "5.19.1"')
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


def copy_rust_std_material(args, output: Path, source: Path, revision: str) -> None:
    # Cargo inventories omit the toolchain's standard-library attribution.
    root = REPO_ROOT / "third_party/licenses/rust-std-1.97.1"
    material = {name: read_checked(root / name, digest) for name, digest in RUST_STD_HASHES.items()}
    if core_delivery_profile(args) == "android-armv7":
        material["PROVENANCE.json"] = read_checked(root / "PROVENANCE.armv7.json", RUST_STD_ARMV7_PROVENANCE_SHA256)
    if core_delivery_profile(args) == "android4":
        if not RUST_STD_ANDROID4_PROVENANCE_SHA256:
            raise ValueError("Actual android4 Rust attribution is not bound")
        material["PROVENANCE.json"] = read_checked(root / "PROVENANCE.android4.json", RUST_STD_ANDROID4_PROVENANCE_SHA256)
    provenance = json.loads(material["PROVENANCE.json"])
    channel = tomllib.loads(git_blob(source, revision, "rust-toolchain.toml").decode())["toolchain"]["channel"]
    if channel != provenance["rustVersion"] or channel != "1.97.1":
        raise ValueError("Pinned compiler differs from the original Rust standard-library attribution")
    if core_delivery_profile(args) == "android4":
        targets = ("aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android")
        producers = provenance.get("nativeProducersByTarget")
        if not isinstance(producers, dict) or set(producers) != set(targets):
            raise ValueError("Actual android4 per-ABI Rust attribution is incomplete")
        for target, producer in producers.items():
            tool = producer["toolchain"]
            if producer["coreCommit"] != revision or producer["features"] != "shipped" or not re.fullmatch(r"[a-f0-9]{64}", producer["producerReceiptSha256"]):
                raise ValueError("Android4 attribution does not bind the actual current producer")
            if tool["rustVersion"] != "1.97.1" or tool["rustcCommit"] != "8bab26f4f68e0e26f0bb7960be334d5b520ea452" or tool["ndkVersion"] != "29.0.14206865":
                raise ValueError("Android4 actual producer toolchain differs")
            if tool["hostKind"] == "docker":
                if tool["imageId"] != "sha256:42937d2c1a0128612cb31a1119c24af835f36ddf2f49073fcca4beb948cb775b":
                    raise ValueError("Android4 actual Docker producer image differs")
            elif tool["hostKind"] != "darwin-arm64" or tool["imageId"] is not None:
                raise ValueError("Android4 actual Mac producer must not claim a Docker image")
        selected = getattr(args, "foxcore_sbom_receipt", None)
        if selected:
            receipt = json.loads(Path(selected).read_bytes())
            expected = {target: row["producerReceiptSha256"] for target, row in producers.items()}
            if receipt.get("coreCommit") != revision or receipt.get("inputs", {}).get("producerReceiptsByTarget") != expected:
                raise ValueError("Controlled android4 per-ABI producers differ from Rust attribution")
        for name, data in material.items():
            write(output, "foxcore/toolchain/" + name, data)
        return
    producer = provenance["nativeProducer"]
    if producer["coreCommit"] != revision or producer["features"] != "shipped":
        raise ValueError("Rust attribution does not bind the pinned shipped Core producer")
    if producer["imageId"] != "sha256:42937d2c1a0128612cb31a1119c24af835f36ddf2f49073fcca4beb948cb775b":
        raise ValueError("Rust attribution producer image differs")
    selected = getattr(args, "foxcore_sbom_receipt", None)
    if selected:
        receipt = json.loads(Path(selected).read_bytes())
        if receipt.get("coreCommit") != revision or receipt.get("inputs", {}).get("producerReceipt", {}).get("sha256") != producer["producerReceiptSha256"]:
            raise ValueError("Controlled Core producer differs from the actual Rust attribution source")
    for name, data in material.items():
        write(output, "foxcore/toolchain/" + name, data)


def git_head(source: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def git_blob(source: Path, revision: str, relative: str, *, allow_empty: bool = False) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(source), "show", f"{revision}:{relative}"],
        check=True,
        capture_output=True,
    )
    if not result.stdout and not allow_empty:
        raise SystemExit(f"empty pinned FoxCore file: {relative}")
    return result.stdout


def core_delivery_profile(args):
    path = REPO_ROOT / "scripts/android-delivery-profile.py"
    spec = importlib.util.spec_from_file_location("guard_app_delivery_profile", path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module.generator_profile(args, REPO_ROOT)


def core_inventory(args, source: Path, revision: str) -> bytes:
    selected = getattr(args, "foxcore_sbom", None)
    receipt = getattr(args, "foxcore_sbom_receipt", None)
    if bool(selected) != bool(receipt):
        raise ValueError("selected FoxCore SBOM and its producer receipt must be provided together")
    manifest = tomllib.loads(git_blob(source, revision, "Cargo.toml").decode())
    version = manifest["workspace"]["package"]["version"]
    if selected:
        path = REPO_ROOT / "scripts/selected-license-sbom.py"
        spec = importlib.util.spec_from_file_location("selected_license_sbom", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.load_selected_sbom(selected, receipt, revision,
                                         git_blob(source, revision, "Cargo.lock"), version, core_delivery_profile(args))
    if core_delivery_profile(args) == "android-armv7":
        raise ValueError("ARMv7 requires its selected native producer inventory and receipt")
    data = git_blob(source, revision, "sbom/foxcore-aarch64-linux-android.cdx.json")
    document = json.loads(data)
    if document.get("metadata", {}).get("component", {}).get("version") != version:
        raise ValueError("committed FoxCore SBOM version differs from the pinned source; regenerate it")
    return data


def copy_selected_cargo_notices(output: Path, args, sbom_data: bytes, source: Path, revision: str) -> None:
    if not getattr(args, "foxcore_sbom", None):
        return
    path = REPO_ROOT / "scripts/selected-license-sbom.py"
    spec = importlib.util.spec_from_file_location("selected_license_notice_files", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    manifest = tomllib.loads(git_blob(source, revision, "Cargo.toml").decode())
    material = module.load_selected_delivery(args.foxcore_sbom, args.foxcore_sbom_receipt, revision,
                                            git_blob(source, revision, "Cargo.lock"),
                                            manifest["workspace"]["package"]["version"], core_delivery_profile(args))
    primary = module.primary_target(core_delivery_profile(args)) if core_delivery_profile(args) == "android-armv7" else module.PRIMARY_TARGET
    if material["targets"][primary] != sbom_data:
        raise ValueError("Selected Core primary inventory differs from validated delivery")
    for target, body in material["targets"].items():
        write(output, "foxcore/foxcore-" + target + ".cdx.json", body)
    write(output, "foxcore/ABI-COVERAGE.json", material["coverage"])
    for relative, data in material["notices"]:
        write(output, "foxcore/cargo-notices/" + relative, data)



def copy_core_material(output, args, source, revision):
    selected = getattr(args, "foxcore_sbom", None)
    receipt = getattr(args, "foxcore_sbom_receipt", None)
    if bool(selected) != bool(receipt):
        raise ValueError("selected FoxCore SBOM and its producer receipt must be provided together")
    material = None
    if selected:
        path = REPO_ROOT / "scripts/selected-license-sbom.py"
        spec = importlib.util.spec_from_file_location("selected_core_generate_once", path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        fence = module.delivery_input_fence(selected, receipt)
        manifest = tomllib.loads(git_blob(source, revision, "Cargo.toml").decode())
        loaded = module.load_selected_delivery(selected, receipt, revision, git_blob(source, revision, "Cargo.lock"),
                                               manifest["workspace"]["package"]["version"], core_delivery_profile(args))
        if any(type(body) is not bytes for body in loaded["targets"].values()) or type(loaded["coverage"]) is not bytes:
            raise ValueError("Selected delivery must retain immutable raw bytes")
        if any(type(name) is not str or type(body) is not bytes for name, body in loaded["notices"]):
            raise ValueError("Selected notices must retain immutable raw bytes")
        material = MappingProxyType({"targets": MappingProxyType(dict(loaded["targets"])),
                                     "notices": tuple((name, body) for name, body in loaded["notices"]), "coverage": loaded["coverage"]})
    primary = module.primary_target(core_delivery_profile(args)) if material is not None and core_delivery_profile(args) == "android-armv7" else "aarch64-linux-android"
    core_files = {
        "LICENSE": "foxcore/FoxHole-Core-GPL-3.0-or-later.txt",
        "THIRD_PARTY_NOTICES.md": "foxcore/THIRD_PARTY_NOTICES.md",
        "sbom/foxcore-aarch64-linux-android.cdx.json": "foxcore/foxcore-" + primary + ".cdx.json",
    }
    for name, destination in core_files.items():
        data = ((material["targets"][primary] if material is not None else core_inventory(args, source, revision))
                if name == "sbom/foxcore-aarch64-linux-android.cdx.json" else git_blob(source, revision, name))
        write(output, destination, data)
    if material is not None:
        for target, data in material["targets"].items(): write(output, "foxcore/foxcore-" + target + ".cdx.json", data)
        write(output, "foxcore/ABI-COVERAGE.json", material["coverage"])
        for relative, data in material["notices"]: write(output, "foxcore/cargo-notices/" + relative, data)
    core_sbom = json.loads((output / core_files["sbom/foxcore-aarch64-linux-android.cdx.json"]).read_text())
    if any(not node.get("licenses") for node in core_sbom.get("components", [])):
        raise SystemExit("FoxCore SBOM contains components without licenses")
    write(output, "foxcore/REVISION.txt", f"{revision}\n".encode())
    if material is not None: module.recheck_delivery_input_fence(fence)


def copy_sentinel_material(output: Path, args, foxcore_root: Path, core_revision: str) -> None:
    selected_sentinel = getattr(args, "sentinel_sbom", None)
    selected_sentinel_receipt = getattr(args, "sentinel_sbom_receipt", None)
    if bool(selected_sentinel) != bool(selected_sentinel_receipt):
        raise ValueError("Selected Sentinel inventory and receipt must be provided together")
    retained = None
    retained_args = [getattr(args, name, None) for name in
        ("sentinel_retained_role", "sentinel_current_source_manifest", "sentinel_current_producer", "sentinel_current_stage")]
    if core_revision == "be0ff0ed269341b4c4ee6b1f6d9976344ecbd3f1" or any(retained_args):
        if not selected_sentinel or not selected_sentinel_receipt or not all(retained_args):
            raise ValueError("Current be0 Guard requires the explicit original Sentinel role and current native proofs")
        path = REPO_ROOT / "scripts/retained-sentinel-role.py"
        spec = importlib.util.spec_from_file_location("retained_sentinel_delivery", path)
        retained_module = importlib.util.module_from_spec(spec); spec.loader.exec_module(retained_module)
    if selected_sentinel:
        path = REPO_ROOT / "scripts/selected-sentinel-sbom.py"
        spec = importlib.util.spec_from_file_location("selected_sentinel_inventory", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        manifests = {"foxhole-sentinel-yara": "native/sentinel-yara/Cargo.toml",
                     "yara-x": "native/sentinel-yara/vendor/yara-x/Cargo.toml",
                     "yara-x-parser": "native/sentinel-yara/vendor/yara-x-parser/Cargo.toml"}
        selected_revision = core_revision
        if core_revision == "be0ff0ed269341b4c4ee6b1f6d9976344ecbd3f1" or any(retained_args):
            def sentinel_git_names():
                return subprocess.run(["git", "-C", str(foxcore_root), "ls-tree", "-r", "--name-only",
                    core_revision, "native/sentinel-yara"], check=True, capture_output=True, text=True).stdout.splitlines()
            retained = retained_module.generation(REPO_ROOT, args, core_revision, module,
                lambda name: git_blob(foxcore_root, core_revision, name, allow_empty=True), sentinel_git_names)
            selected_revision = retained["revision"]
        material = module.load_selected_delivery(selected_sentinel, selected_sentinel_receipt, selected_revision,
                                        git_blob(foxcore_root, core_revision, "native/sentinel-yara/Cargo.lock"),
                                        {name: git_blob(foxcore_root, core_revision, value) for name, value in manifests.items()},
                                        "legacy4" if retained is not None else core_delivery_profile(args))
        for target, data in material["targets"].items():
            write(output, "sentinel/selected-" + target + ".cdx.json", data)
        write(output, "sentinel/ABI-COVERAGE.json", material["coverage"])
        if retained is not None:
            if sha256(material["coverage"]) != retained["config"]["originalCoverageSha256"] or sha256(material["notices"][0][1]) != retained["config"]["originalNoticeUnionSha256"]:
                raise ValueError("Original retained Sentinel coverage/notice union changed")
            write(output, "sentinel/RETAINED-ROLE.json", retained["role"])
            write(output, "sentinel/RETAINED-CONSUMER.json", retained["binding"])
        for relative, body in material["notices"]:
            write(output, "sentinel/cargo-notices/" + relative, body)
        for name in ("LICENSE", "README.md", "vendor/PROVENANCE.json"):
            write(output, "sentinel/" + name,
                  git_blob(foxcore_root, core_revision, "native/sentinel-yara/" + name))
        patches = subprocess.run(
            ["git", "-C", str(foxcore_root), "ls-tree", "-r", "--name-only", core_revision,
             "native/sentinel-yara/vendor/patches"], check=True, capture_output=True, text=True,
        ).stdout.splitlines()
        for name in patches:
            if not name.startswith("native/sentinel-yara/vendor/patches/") or Path(name).name == "AGENTS.md":
                raise ValueError("Invalid pinned Sentinel patch material")
            write(output, "sentinel/" + name.removeprefix("native/sentinel-yara/"),
                  git_blob(foxcore_root, core_revision, name))
    else:
        locked = tomllib.loads(git_blob(foxcore_root, core_revision, "native/sentinel-yara/Cargo.lock").decode())
        legacy = json.loads(git_blob(foxcore_root, core_revision, "native/sentinel-yara/third-party/licenses.json"))
        locked_packages = {(item["name"], item["version"]) for item in locked["package"]
                           if item["name"] != "foxhole-sentinel-yara"}
        legacy_packages = {(item["name"], item["version"]) for item in legacy}
        if len(legacy_packages) != len(legacy) or legacy_packages != locked_packages:
            raise ValueError("Sentinel legacy legal inventory is stale; supply genuine selected Sentinel inputs")
        scanner_files = subprocess.run(
            ["git", "-C", str(foxcore_root), "ls-tree", "-r", "--name-only", core_revision,
             "native/sentinel-yara/LICENSE", "native/sentinel-yara/README.md", "native/sentinel-yara/third-party",
             "native/sentinel-yara/vendor/PROVENANCE.json", "native/sentinel-yara/vendor/patches",
             "sbom/sentinel-yara-all-targets.cdx.json"],
            check=True, capture_output=True, text=True,
        ).stdout.splitlines()
        if not scanner_files or any(Path(name).name == "AGENTS.md" for name in scanner_files):
            raise SystemExit("invalid pinned scanner notice set")
        for source in scanner_files:
            relative = source.removeprefix("native/sentinel-yara/")
            write(output, "sentinel/" + relative, git_blob(foxcore_root, core_revision, source))
    return retained["revision"] if retained is not None else core_revision


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


def go_environment(go_root: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment.pop("GOTOOLDIR", None)
    environment.update(GOROOT=str(go_root), GOTOOLCHAIN="local")
    return environment


def go_modules(go_binary: Path, executable: Path) -> list[dict[str, str]]:
    result = subprocess.run(
        [str(go_binary), "version", "-m", str(executable)],
        env=go_environment(go_binary.parent.parent),
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
    canonical = None
    canonical_module = None
    if getattr(args, "source_legal_snapshot", None):
        if any(getattr(args, name, None) for name in (
            "foxcore_sbom", "foxcore_sbom_receipt", "sentinel_sbom", "sentinel_sbom_receipt",
            "sentinel_retained_role", "sentinel_current_source_manifest",
            "sentinel_current_producer", "sentinel_current_stage",
        )):
            raise ValueError("Canonical source legal data cannot relabel raw producer inputs")
        module_path = REPO_ROOT / "scripts/source-legal-snapshot.py"
        spec = importlib.util.spec_from_file_location("canonical_source_legal_snapshot", module_path)
        canonical_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(canonical_module)
        canonical = canonical_module.load(
            args.source_legal_snapshot, REPO_ROOT, foxcore_root,
            (REPO_ROOT / "config/foxcore-revision.txt").read_text().strip(),
            core_delivery_profile(args), args.fresh_core_native_root, args.fresh_sentinel_native_root, getattr(args, "source_native_manifest", None),
        )
    else:
        copy_rust_std_material(args, output, foxcore_root, (REPO_ROOT / "config/foxcore-revision.txt").read_text().strip())

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
    go_root = Path(os.environ.get("FOXHOLE_GOROOT") or native_root / go_version).resolve(strict=True)
    go_binary = go_root / "bin/go"
    actual_go_version = subprocess.run(
        [str(go_binary), "env", "GOVERSION"], env=go_environment(go_root),
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    if actual_go_version != go_version:
        raise ValueError("Go license/build-info tool differs from the pinned source version")
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
    if canonical is not None:
        canonical_module.recheck(canonical, foxcore_root)
        for relative, data in canonical["files"].items():
            write(output, relative, data)
        source_provenance = canonical.get("sourceNativeProvenance")
        if source_provenance is not None:
            write(output, "SOURCE-BUILD.json", (json.dumps({
                "schema": 1,
                "kind": "fdroid-source-native-and-reference-legal-attribution",
                "sourceBuild": source_provenance,
                "referenceLegalSnapshotSha256": canonical["snapshotSha256"],
                "referenceNativeLibraries": canonical["referenceNativeLibraries"],
                "originalLegalFilesUnchanged": True,
                "APKReproducibilityVerified": False,
            }, indent=2) + "\n").encode())

    else:
        copy_core_material(output, args, foxcore_root, core_revision)
        sentinel_revision = copy_sentinel_material(output, args, foxcore_root, core_revision)
        write(output, "sentinel/REVISION.txt", f"{sentinel_revision}\n".encode())

    readme = (
        "FoxHole Guard bundled license material\n\n"
        "THIRD_PARTY_NOTICES.md is the human-readable inventory. This directory also contains "
        "upstream license and NOTICE material for bundled native code, fonts, icons, "
        "flags and Android native dependencies. FoxCore, Tor transport dependencies and the "
        "Tor Project integration configuration are pinned to the exact release inputs. "
        "SHA256SUMS authenticates every file in this bundle.\n"
    )
    if getattr(args, "foxcore_sbom", None) or canonical is not None:
        readme += (
            "Selected Cargo material is under foxcore/cargo-notices. A legacy declared-license "
            "supplement is labeled separately from publisher originals and may include an "
            "unchanged SPDX reference text.\n"
        )
    write(output, "README.txt", readme.encode())
    if getattr(args, "derived_native_root", None):
        subprocess.run(["python3", str(REPO_ROOT / "scripts/copy-derived-native-licenses.py"),
                        "--repo", str(REPO_ROOT), "--native", args.derived_native_root,
                        "--output", str(output), "--abis", *args.android_abis.split()], check=True)
    maven_args = ["python3", str(REPO_ROOT / "scripts/copy-maven-runtime-licenses.py"),
                  "--repo", str(REPO_ROOT), "--bundle", str(REPO_ROOT / "third_party/maven-runtime"),
                  "--output", str(output)]
    if canonical is not None and canonical.get("sourceNativeProvenance") is not None:
        maven_args.append("--fdroid-unsigned-source")
    subprocess.run(maven_args, check=True)
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
    if (getattr(args, "foxcore_sbom", None) or canonical is not None) and core_delivery_profile(args) != "legacy4":
        profile = core_delivery_profile(args)
        shapes = {"android4": (["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]),
                  "android64": (["arm64-v8a", "x86_64"], ["aarch64-linux-android", "x86_64-linux-android"]),
                  "android-armv7": (["armeabi-v7a"], ["armv7-linux-androideabi"]),
                  "android3": (["arm64-v8a", "armeabi-v7a", "x86_64"], ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"])}
        abis, targets = shapes[profile]
        manifest.update(androidDeliveryProfile=profile, abis=abis, targets=targets)
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
    parser.add_argument("--source-legal-snapshot")
    parser.add_argument("--source-native-manifest")
    parser.add_argument("--fresh-core-native-root")
    parser.add_argument("--fresh-sentinel-native-root")
    parser.add_argument("--foxcore-sbom")
    parser.add_argument("--foxcore-sbom-receipt")
    parser.add_argument("--core-delivery-profile", choices=["legacy4", "android64", "android-armv7", "android3", "android4"])
    parser.add_argument("--sentinel-sbom")
    parser.add_argument("--sentinel-sbom-receipt")
    parser.add_argument("--sentinel-retained-role")
    parser.add_argument("--sentinel-current-source-manifest")
    parser.add_argument("--sentinel-current-producer")
    parser.add_argument("--sentinel-current-stage")
    parser.add_argument("--native-deps-root")
    parser.add_argument("--ndk-root")
    parser.add_argument("--android-abis", default="arm64-v8a")
    parser.add_argument("--derived-native-root")
    generate(parser.parse_args())


if __name__ == "__main__":
    main()
