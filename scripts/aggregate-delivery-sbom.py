#!/usr/bin/env python3
"""Link resolved Maven and native inventories to the exact release APK bytes."""
import argparse
import copy
import hashlib
import json
import pathlib
import re
import urllib.parse
import zipfile

GO_MAGIC = b"\xff Go buildinf:"
NATIVE_MAVEN_OWNERS = {
    "libandroidx.graphics.path.so": ("androidx.graphics", "graphics-path"),
    "libdatastore_shared_counter.so": ("androidx.datastore", "datastore-core-android"),
    "libjnidispatch.so": ("net.java.dev.jna", "jna"),
    "libsodium.so": ("com.goterl", "lazysodium-android"),
    "libsqlcipher.so": ("net.zetetic", "sqlcipher-android"),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def component(name, version, ecosystem, **extra):
    if not isinstance(name, str) or not name or not isinstance(version, str) or not version:
        raise ValueError("Component must have an explicit name and version")
    purl = f"pkg:{ecosystem}/{urllib.parse.quote(name, safe='/')}@{urllib.parse.quote(version, safe='')}"
    return {"type": "library", "name": name, "version": version, "purl": purl, "bom-ref": purl, **extra}


def property_value(name, value):
    return {"name": "foxhole:" + name, "value": value}


def validate_bom(bom, label):
    if not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX":
        raise ValueError(f"{label} is not CycloneDX")
    components = bom.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError(f"{label} component inventory is empty")
    refs = set()
    for item in components:
        if not isinstance(item, dict) or not all(isinstance(item.get(k), str) and item[k]
                                                for k in ("bom-ref", "name", "version", "purl")):
            raise ValueError(f"{label} has an incomplete component identity")
        if item["bom-ref"] in refs:
            raise ValueError(f"{label} has duplicate component identities")
        refs.add(item["bom-ref"])
    root_ref = bom.get("metadata", {}).get("component", {}).get("bom-ref")
    if not isinstance(root_ref, str) or not root_ref:
        raise ValueError(f"{label} has no root component identity")
    refs.add(root_ref)
    dependencies = bom.get("dependencies", [])
    if not isinstance(dependencies, list):
        raise ValueError(f"{label} dependencies must be an array")
    for item in dependencies:
        if not isinstance(item, dict) or item.get("ref") not in refs or not isinstance(item.get("dependsOn", []), list):
            raise ValueError(f"{label} has an invalid dependency edge")
        if any(ref not in refs for ref in item.get("dependsOn", [])):
            raise ValueError(f"{label} has a dangling dependency edge")
    return root_ref


def expand_core_workspace(bom):
    document = copy.deepcopy(bom)
    known = {item["bom-ref"] for item in document["components"]}
    root = document["metadata"]["component"]
    unknown = {row["ref"] for row in document["dependencies"]} - known - {root["bom-ref"]}
    member_count = next((item["value"] for item in document["metadata"].get("properties", [])
                         if item["name"] == "foxcore:workspace_members"), None)
    if member_count != str(len(unknown)):
        raise ValueError("Core internal module count differs from its workspace inventory")
    for ref in sorted(unknown):
        match = re.fullmatch(r"pkg:cargo/((?:foxcore-|proto-)[a-z0-9-]+)@([0-9.]+)", ref)
        if not match or match[2] != root["version"]:
            raise ValueError("Core BOM has an unreviewed missing dependency")
        document["components"].append({"type": "library", "name": match[1], "version": match[2],
                                       "purl": ref, "bom-ref": ref,
                                       "properties": [property_value("workspaceMember", "true")]})
    return document


def inline_string(data, offset):
    length = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("Truncated Go build information")
        byte = data[offset]
        offset += 1
        length |= (byte & 127) << shift
        if byte < 128:
            if length > 4 * 1024 * 1024 or offset + length > len(data):
                raise ValueError("Invalid Go build information length")
            return data[offset:offset + length], offset + length
    raise ValueError("Invalid Go build information varint")


def go_build_information(data):
    # Go >=1.18 embeds varint-prefixed strings; see src/debug/buildinfo/buildinfo.go.
    offset = data.find(GO_MAGIC)
    if offset < 0 or offset + 32 > len(data) or data[offset + 15] & 2 != 2:
        raise ValueError("Candidate does not carry supported Go build information")
    version, position = inline_string(data, offset + 32)
    encoded, _ = inline_string(data, position)
    if len(encoded) < 33 or encoded[-17] != 10:
        raise ValueError("Candidate Go module information is not framed")
    modules = []
    pending = None
    for line in encoded[16:-16].decode().splitlines():
        fields = line.split("\t")
        if fields[0] == "dep":
            if pending:
                modules.append(pending)
            if len(fields) < 3:
                raise ValueError("Truncated Go module identity")
            pending = {"declaredPath": fields[1], "declaredVersion": fields[2],
                       "path": fields[1], "version": fields[2]}
        elif fields[0] == "=>":
            if pending is None or len(fields) < 3:
                raise ValueError("Unbound Go module replacement")
            if fields[1].startswith((".", "/")):
                pending["replacementPath"] = fields[1]
            else:
                pending.update(path=fields[1], version=fields[2])
        elif pending:
            modules.append(pending)
            pending = None
    if pending:
        modules.append(pending)
    if not modules or len({m["declaredPath"] for m in modules}) != len(modules):
        raise ValueError("Go module inventory is empty or ambiguous")
    return version.decode(), modules


def verified_notice(archive, notices, path):
    external = (notices / path).read_bytes()
    try:
        embedded = archive.read("assets/licenses/" + path)
    except KeyError as error:
        raise ValueError(f"Candidate lacks embedded delivery inventory: {path}") from error
    if external != embedded:
        raise ValueError(f"Delivery inventory differs from the candidate APK: {path}")
    return external


def pin(text, pattern, label):
    match = re.search(pattern, text, re.M)
    if not match:
        raise ValueError(f"Missing release input pin: {label}")
    return match.group(1)


def module_identity(item):
    if not isinstance(item, dict):
        raise ValueError("Go module record is not an object")
    fields = ("declaredPath", "declaredVersion", "path", "version")
    if not all(isinstance(item.get(key), str) and item[key] for key in fields):
        raise ValueError("Go module record is missing an identity")
    if item["path"].startswith((".", "/")) or not re.match(r"^v\d+\.\d+\.\d+", item["version"]):
        raise ValueError("Go module has an unversioned or local OSV identity")
    return tuple(item[key] for key in fields) + (item.get("replacementPath"),)


def validate_replacement(item, archive, notices):
    replacement = item.get("replacementPath")
    if replacement is None:
        return []
    if (item["declaredPath"], item["declaredVersion"], replacement) != (
            "github.com/refraction-networking/conjure", "v0.9.1", "../conjure-patched"):
        raise ValueError("Unreviewed local Go module replacement")
    properties = [property_value("replacementPath", replacement)]
    for key, path in (("patchManifestSha256", "tor/CONJURE-PATCH.json"),
                      ("patchScriptSha256", "tor/CONJURE-PATCH.py")):
        digest = sha256(verified_notice(archive, notices, path))
        if item.get(key) != digest:
            raise ValueError(f"Go replacement provenance does not match candidate: {key}")
        properties.append(property_value(key, digest))
    return properties


def aggregate(bom, notices, apk, root):
    root_ref = validate_bom(bom, "Maven BOM")
    result = copy.deepcopy(bom)
    result["metadata"]["component"]["name"] = "foxhole-android"
    components = result["components"]
    edges = {}

    def connect(ref, children):
        edges.setdefault(ref, set()).update(children)

    for row in result.get("dependencies", []):
        connect(row["ref"], row.get("dependsOn", []))
    pins = (root / "scripts/native-deps.sh").read_text()
    versions = {name: pin(pins, pattern, name) for name, pattern in {
        "OpenSSL": r'I2PD_OPENSSL_VERSION:-([^}]+)', "Boost": r'I2PD_BOOST_VERSION:-([^}]+)',
        "stdlib": r'TOR_TRANSPORT_GO:-go([^}]+)'}.items()}
    revision = (root / "config/foxcore-revision.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Core revision is not a full commit SHA")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Candidate APK has duplicate entries")
        manifest = json.loads(verified_notice(archive, notices, "MANIFEST.json"))
        if manifest.get("foxcoreRevision") != revision:
            raise ValueError("Candidate notice revision differs from the Core pin")
        core_path = "foxcore/foxcore-aarch64-linux-android.cdx.json"
        core_bytes = verified_notice(archive, notices, core_path)
        core = expand_core_workspace(json.loads(core_bytes))
        core_root_ref = validate_bom(core, "Core BOM")
        core_refs = {item["bom-ref"]: "foxcore:" + item["bom-ref"] for item in core["components"]}
        core_refs[core_root_ref] = "foxcore:root"
        core_root = copy.deepcopy(core["metadata"]["component"])
        core_root["bom-ref"] = "foxcore:root"
        core_root.setdefault("purl", "pkg:generic/foxcore@" + revision)
        core_root.setdefault("version", revision)
        components.append(core_root)
        for item in core["components"]:
            item = copy.deepcopy(item)
            item["bom-ref"] = core_refs[item["bom-ref"]]
            item.setdefault("properties", []).append(property_value("origin", core_path))
            components.append(item)
        for item in core.get("dependencies", []):
            connect(core_refs[item["ref"]], [core_refs[x] for x in item.get("dependsOn", [])])
        if not edges.get("foxcore:root"):
            connect("foxcore:root", [core_refs[c["bom-ref"]] for c in core["components"]])
        modules_bytes = verified_notice(archive, notices, "tor/GO-MODULES.json")
        modules = json.loads(modules_bytes)
        if not isinstance(modules, list) or not modules:
            raise ValueError("Go delivery inventory is empty")
        module_refs = {}
        modules_by_binary = {"lyrebird": [], "conjure-client": []}
        for item in modules:
            identity = module_identity(item)
            binaries = item.get("binaries")
            if not isinstance(binaries, list) or not binaries or any(b not in modules_by_binary for b in binaries):
                raise ValueError("Go inventory names an unknown delivered binary")
            properties = [property_value("origin", "tor/GO-MODULES.json"),
                          property_value("declaredModule", item["declaredPath"] + "@" + item["declaredVersion"]),
                          property_value("binaries", ",".join(sorted(binaries)))]
            properties += validate_replacement(item, archive, notices)
            entry = component(item["path"], item["version"], "golang", properties=properties)
            if entry["bom-ref"] in module_refs:
                raise ValueError("Duplicate Go module delivery record")
            module_refs[entry["bom-ref"]] = entry
            components.append(entry)
            for binary in binaries:
                modules_by_binary[binary].append((identity, entry["bom-ref"]))
        i2pd_bytes = verified_notice(archive, notices, "i2pd/VERSION.txt")
        if i2pd_bytes != (root / "third_party/i2pd.version").read_bytes():
            raise ValueError("Candidate i2pd revision differs from its release pin")
        versions["i2pd"] = pin(i2pd_bytes.decode(), r'^ref=(.+)$', "i2pd")
        native_refs = {}
        for name, version in versions.items():
            entry = component(name, version, "golang" if name == "stdlib" else "generic")
            components.append(entry)
            native_refs[name] = entry["bom-ref"]
        for binary, prefix in (("lyrebird", "lyrebird"), ("conjure-client", "conjure")):
            commit = pin(pins, rf'^{prefix}_commit="([0-9a-f]{{40}})"$', prefix)
            entry = component(binary, commit, "generic", properties=[property_value("sourceCommit", commit)])
            components.append(entry)
            native_refs[binary] = entry["bom-ref"]
            connect(entry["bom-ref"], [ref for _, ref in modules_by_binary[binary]] + [native_refs["stdlib"]])
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        if not libraries or any(not name.startswith("lib/arm64-v8a/") for name in libraries):
            raise ValueError("Delivery BOM requires an arm64-v8a release APK")
        required = {"libfoxhole_native.so", "libi2pd.so", "liblyrebird.so", "libconjure_client.so"}
        if not required.issubset({pathlib.PurePosixPath(p).name for p in libraries}):
            raise ValueError("Candidate is missing a required native runtime library")
        for path in libraries:
            data = archive.read(path)
            name = pathlib.PurePosixPath(path).name
            digest = sha256(data)
            entry = component(path, digest, "generic", type="file", hashes=[{"alg": "SHA-256", "content": digest}])
            components.append(entry)
            connect(root_ref, [entry["bom-ref"]])
            if name == "libfoxhole_native.so":
                parents = ["foxcore:root"]
            elif name in ("liblyrebird.so", "libconjure_client.so"):
                binary = "lyrebird" if name == "liblyrebird.so" else "conjure-client"
                go_version, actual_modules = go_build_information(data)
                if go_version != "go" + versions["stdlib"]:
                    raise ValueError("Candidate Go toolchain differs from the release pin")
                expected = {identity for identity, _ in modules_by_binary[binary]}
                actual = {module_identity(m) for m in actual_modules}
                if actual != expected:
                    raise ValueError(f"Candidate Go modules differ from delivery inventory: {binary}")
                parents = [native_refs[binary]]
            elif name == "libi2pd.so":
                if ("OpenSSL " + versions["OpenSSL"] + " ").encode() not in data:
                    raise ValueError("Candidate OpenSSL version differs from the release pin")
                parents = [native_refs[n] for n in ("i2pd", "OpenSSL", "Boost")]
            else:
                owner = NATIVE_MAVEN_OWNERS.get(name)
                if owner is None:
                    raise ValueError(f"Native library has no reviewed Maven owner mapping: {name}")
                group, artifact = owner
                parents = [c["bom-ref"] for c in bom["components"]
                           if c["purl"].startswith(f"pkg:maven/{group}/{artifact}@")]
                if len(parents) != 1:
                    raise ValueError(f"Native library lacks one resolved Maven owner: {name}")
            connect(entry["bom-ref"], parents)
    result["metadata"].setdefault("properties", []).extend([
        property_value("coreRevision", revision), property_value("target", "aarch64-linux-android"),
        property_value("apkSha256", sha256(apk.read_bytes())), property_value("coreBomSha256", sha256(core_bytes)),
        property_value("goInventorySha256", sha256(modules_bytes)),
        property_value("inventoryTool", "aggregate-delivery-sbom.py"),
        property_value("inventoryToolSha256", sha256(pathlib.Path(__file__).read_bytes())),
    ])
    result["dependencies"] = [{"ref": ref, "dependsOn": sorted(children)} for ref, children in sorted(edges.items())]
    validate_bom(result, "Aggregate BOM")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("maven", type=pathlib.Path)
    parser.add_argument("notices", type=pathlib.Path)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    document = aggregate(json.loads(args.maven.read_text()), args.notices, args.apk, pathlib.Path(__file__).resolve().parent.parent)
    args.output.write_text(json.dumps(document, indent=2) + "\n")
