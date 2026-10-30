#!/usr/bin/env python3
"""Link resolved Maven and native inventories to the exact release APK bytes."""
import argparse
import copy
import hashlib
import importlib.util
import json
import pathlib
import re
import urllib.parse
import zipfile

FORK_SPEC = importlib.util.spec_from_file_location("guard_rustls_delivery_origin", pathlib.Path(__file__).with_name("rustls-fork-origin.py"))
FORK = importlib.util.module_from_spec(FORK_SPEC)
FORK_SPEC.loader.exec_module(FORK)

DELIVERY_SPEC = importlib.util.spec_from_file_location("guard_android_delivery", pathlib.Path(__file__).with_name("android-delivery-profile.py"))
DELIVERY = importlib.util.module_from_spec(DELIVERY_SPEC)
DELIVERY_SPEC.loader.exec_module(DELIVERY)

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


def derived_native_component(name, path, data, archive, notices, root):
    kind = {"libandroidx.graphics.path.so": "graphics", "libsodium.so": "sodium"}.get(name)
    if kind is None:
        return None
    config_path = root / "config/native/page-aligned-native.json"
    if not config_path.is_file():
        return None
    config = json.loads(config_path.read_text())[kind]
    prefix = "derived-native/" + kind + "/"
    producer_bytes = verified_notice(archive, notices, prefix + "producer-evidence.json")
    input_bytes = verified_notice(archive, notices, prefix + "producer-inputs.json")
    producer, inputs = json.loads(producer_bytes), json.loads(input_bytes)
    spec = importlib.util.spec_from_file_location("canonical_page_native_evidence", root / "scripts/copy-derived-native-licenses.py")
    projection = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(projection)
    projection.validate_projection(kind, producer)
    if projection.encoded(producer) != producer_bytes:
        raise ValueError("Derived native producer evidence is not canonically encoded")
    if producer.get("exitCode") != 0 or producer.get("sourceMutations") != [] or \
            producer.get("producerInputManifestSha256") != sha256(input_bytes):
        raise ValueError("Derived native producer is not bound to actual inputs")
    abi = pathlib.PurePosixPath(path).parts[1]
    matches = [item for item in producer["libraries"]
               if item["abi"] == abi and pathlib.PurePosixPath(item["path"]).name == name]
    if len(matches) != 1 or matches[0]["sha256"] != sha256(data) or matches[0]["bytes"] != len(data):
        raise ValueError("Derived native APK bytes differ from its actual producer")
    if kind == "graphics":
        source = config["commit"]
        if producer.get("graphicsReleaseCommit") != source or \
                {n: v["sha256"] for n, v in producer["graphicsOriginalSource"].items()} != \
                {n: v["sha256"] for n, v in config["sourceFiles"].items()}:
            raise ValueError("Derived graphics release source differs")
        version = "1.0.1+foxhole16k"
        license_id = "Apache-2.0"
        aar_name = "graphics-path-1.0.1.aar"
    else:
        source = config["upstreamSecurityFixCommit"]
        if producer.get("upstreamSecurityFixCommit") != source:
            raise ValueError("Derived sodium upstream security fix differs")
        if inputs["files"].get("libsodium-CVE-2025-69277-upstream.patch") != config["patch"]["sha256"]:
            raise ValueError("Derived sodium patch digest differs")
        counts = producer.get("upstreamGreenTestCounts", {})
        if counts.get("TOTAL") != counts.get("PASS") or counts.get("PASS") != 80 or \
                producer.get("upstreamRedRegressionExpectedExit") != 2 or any(
                    counts.get(key) != 0 for key in ("SKIP", "XFAIL", "FAIL", "XPASS", "ERROR")):
            raise ValueError("Derived sodium upstream regression evidence is incomplete")
        inventory_path = root / config["sourceInventory"]["path"]
        if sha256(inventory_path.read_bytes()) != config["sourceInventory"]["sha256"]:
            raise ValueError("Derived sodium source inventory differs")
        inventory = json.loads(inventory_path.read_text())
        expected_effective = inventory["originalSourceInventory"] | inventory["compiledSourcePatchFiles"]
        if producer["originalSodiumSource"] != inventory["originalSourceInventory"] or \
                producer["effectivePatchedSodiumSource"] not in [expected_effective, inventory["actualReferenceEffectiveInventory"]]:
            raise ValueError("Derived sodium original/effective source differs")
        version = "1.0.20+foxhole-ad3004"
        license_id = "ISC"
        aar_name = "lazysodium-android-5.2.0.aar"
    if inputs["files"].get(aar_name) != config["originalAar"]["sha256"]:
        raise ValueError("Derived native original Maven Java/AAR input differs")
    return component(
        "foxhole-derived-" + kind + "-native", version, "generic",
        licenses=[{"license": {"id": license_id}}],
        properties=[property_value("nativeOrigin", "derived-from-pinned-publisher-source"),
                    property_value("sourceRevisionOrPatch", source),
                    property_value("producerEvidenceSha256", sha256(producer_bytes)),
                    property_value("producerInputsSha256", sha256(input_bytes)),
                    property_value("originalMavenJavaUnchanged", "true")],
    )


def internal_project_component(item, root):
    projects = {"project::core:" + name: name for name in
                ("importer", "model", "network", "profile", "runtime", "sentinel")}
    return (item.get("type") == "library" and "purl" not in item and isinstance(root, dict) and
            root.get("bom-ref") == "project::app" and root.get("version") == "1.0.0" and
            item.get("bom-ref") in projects and item.get("name") == projects[item["bom-ref"]] and
            item.get("version") == root["version"] and
            item.get("licenses") == [{"license": {"id": "GPL-3.0-or-later"}}])


def validate_bom(bom, label):
    if not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX":
        raise ValueError(f"{label} is not CycloneDX")
    components = bom.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError(f"{label} component inventory is empty")
    refs = set()
    for item in components:
        if not isinstance(item, dict) or not all(isinstance(item.get(k), str) and item[k]
                                                for k in ("bom-ref", "name", "version")):
            raise ValueError(f"{label} has an incomplete component identity")
        metadata = bom.get("metadata")
        root = metadata.get("component") if isinstance(metadata, dict) else None
        if not isinstance(item.get("purl"), str) or not item["purl"]:
            if not internal_project_component(item, root):
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


def validate_explicit_workspace(document, properties, revision, fork_origin=None):
    count = properties["foxhole:native:explicit-workspace-components"]
    if not re.fullmatch(r"[1-9][0-9]*", count) or "foxcore:workspace_members" in properties:
        raise ValueError("Invalid explicit Core workspace component count")
    source_revision = properties.get("foxhole:native:core-revision")
    if not isinstance(source_revision, str) or not re.fullmatch(r"[0-9a-f]{40}", source_revision):
        raise ValueError("Explicit Core graph lacks its source revision")
    if revision is not None and source_revision != revision:
        raise ValueError("Explicit Core graph differs from the pinned source revision")
    root = document["metadata"]["component"]
    nodes = [root] + document["components"]
    refs, local_count, fork_count = set(), 0, 0
    for item in nodes:
        ref = item["bom-ref"]
        if ref in refs:
            raise ValueError("Duplicate explicit Core component")
        refs.add(ref)
        values = {row["name"]: row["value"] for row in item.get("properties", [])}
        purl = item.get("purl", "")
        if purl.startswith("pkg:generic/foxhole-source-fork/") or values.get("foxhole:source-kind") == "vendored-modified":
            if fork_origin is None or fork_origin.get("sourcePinsBound") is not True:
                raise ValueError("Modified Core fork lacks packaged source pin binding")
            FORK.validate_component(item, fork_origin, source_revision)
            fork_count += 1
        elif purl.startswith("pkg:generic/foxhole/") or "cargo:source-revision" in values:
            expected = ("pkg:generic/foxhole/" + urllib.parse.quote(item["name"], safe="-._~")
                        + "@" + item["version"] + "?core_commit=" + source_revision)
            manifest = values.get("cargo:workspace-manifest")
            if purl != expected or values.get("cargo:source-revision") != source_revision or not isinstance(manifest, str):
                raise ValueError("Invalid explicit local Core package identity")
            path = pathlib.PurePosixPath(manifest)
            if path.is_absolute() or ".." in path.parts or path.name != "Cargo.toml":
                raise ValueError("Invalid explicit local Core manifest")
            local_count += 1
    declared_forks = properties.get("foxhole:native:vendored-fork-components")
    if (fork_origin is not None and fork_count != 1) or (fork_count and (fork_count != 1 or declared_forks != "1")) or (not fork_count and declared_forks is not None):
        raise ValueError("Explicit Core vendored fork count differs from its actual nodes")
    if local_count != int(count):
        raise ValueError("Explicit Core workspace count differs from its actual local nodes")
    graph = {}
    for row in document["dependencies"]:
        ref, children = row["ref"], row.get("dependsOn")
        if ref not in refs or ref in graph or not isinstance(children, list) or any(child not in refs for child in children):
            raise ValueError("Explicit Core graph has an unresolved or duplicate dependency")
        graph[ref] = children
    if set(graph) != refs:
        raise ValueError("Explicit Core graph omits dependency nodes")
    reachable, pending = set(), [root["bom-ref"]]
    while pending:
        ref = pending.pop()
        if ref not in reachable:
            reachable.add(ref)
            pending.extend(graph[ref])
    if reachable != refs:
        raise ValueError("Explicit Core graph has disconnected components")


def expand_core_workspace(bom, revision=None, fork_origin=None):
    document = copy.deepcopy(bom)
    rows = document["metadata"].get("properties", [])
    properties = {row["name"]: row["value"] for row in rows}
    if len(properties) != len(rows):
        raise ValueError("Duplicate Core inventory properties")
    if "foxhole:native:explicit-workspace-components" in properties:
        validate_explicit_workspace(document, properties, revision, fork_origin)
        return document
    if fork_origin is not None or "foxhole:native:vendored-fork-components" in properties or any(
            item.get("purl", "").startswith("pkg:generic/foxhole-source-fork/") or
            any(row.get("name") == "foxhole:source-kind" and row.get("value") == "vendored-modified"
                for row in item.get("properties", []))
            for item in [document["metadata"]["component"]] + document["components"]):
        raise ValueError("Modified Core fork requires an explicit captured graph")
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


def validate_sentinel_bom(bom):
    metadata = bom.get("metadata") if isinstance(bom, dict) else None
    root = metadata.get("component") if isinstance(metadata, dict) else None
    if not isinstance(root, dict) or root.get("name") != "foxhole-sentinel-yara" or root.get("type") != "library":
        raise ValueError("Sentinel BOM has an invalid root component")
    version = root.get("version")
    if not isinstance(version, str) or not version:
        raise ValueError("Sentinel BOM has no root version")
    root_ref = component(root["name"], version, "cargo")["purl"]
    if root.get("bom-ref") != root_ref or root.get("purl", root_ref) != root_ref:
        raise ValueError("Sentinel BOM root identity differs from its Cargo identity")
    dependencies = bom.get("dependencies")
    if not isinstance(dependencies, list) or any(
            not isinstance(row, dict) or not isinstance(row.get("ref"), str)
            or not isinstance(row.get("dependsOn"), list)
            or any(not isinstance(child, str) for child in row["dependsOn"])
            for row in dependencies):
        raise ValueError("Sentinel BOM has a malformed dependency graph")
    validate_bom(bom, "Sentinel BOM")
    refs = {item["bom-ref"] for item in bom["components"]}
    if root_ref in refs:
        raise ValueError("Sentinel BOM duplicates its root component")
    refs.add(root_ref)
    graph = {row["ref"]: row["dependsOn"] for row in dependencies}
    if len(graph) != len(dependencies) or set(graph) != refs:
        raise ValueError("Sentinel BOM has an incomplete or duplicate dependency graph")
    reachable, pending = set(), [root_ref]
    while pending:
        ref = pending.pop()
        if ref not in reachable:
            reachable.add(ref)
            pending.extend(graph[ref])
    if reachable != refs:
        raise ValueError("Sentinel BOM contains dependencies disconnected from its root")
    return root_ref


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


def packaged_core_fork(document, archive, notices, root, revision):
    nodes = [document["metadata"]["component"]] + document["components"]
    fork_nodes = [item for item in nodes if item.get("purl", "").startswith("pkg:generic/foxhole-source-fork/") or
                  any(row.get("name") == "foxhole:source-kind" and row.get("value") == "vendored-modified"
                      for row in item.get("properties", []))]
    prefix = "foxcore/cargo-notices/"
    notice_names = {p.removeprefix("assets/licenses/") for p in archive.namelist()
                    if p.startswith("assets/licenses/" + prefix + FORK.NOTICE_PREFIX)}
    pins_path = root / "config/foxcore-source-manifest.json"
    if not fork_nodes and not notice_names and not pins_path.exists() and not pins_path.is_symlink():
        return None
    if pins_path.is_symlink() or not pins_path.is_file() or pins_path.stat().st_size > 4 * 1024 ** 2:
        raise ValueError("Fork lacks a bounded pinned Core source manifest")
    source = FORK.read_json(pins_path.read_bytes())
    if source.get("commit") != revision or not isinstance(source.get("files"), list):
        raise ValueError("Fork source manifest differs from Core revision")
    pins = {}
    for row in source["files"]:
        if not isinstance(row, dict) or set(row) != {"path", "sha256"}:
            raise ValueError("Invalid pinned Core source row")
        path = FORK.safe_path(row["path"])
        if path in pins:
            raise ValueError("Duplicate pinned Core source path")
        pins[path] = row["sha256"]
    if not fork_nodes and not notice_names and not any(p.startswith(FORK.VENDOR_PATH + "/") for p in pins):
        return None
    expected = {prefix + p for p in (FORK.NOTICE_PREFIX + n for n in
                ("ORIGIN.json", "upstream-files.json", "chrome151.patch", "FOXHOLE-MODIFICATIONS.md", *FORK.LICENSES))}
    if notice_names != expected:
        raise ValueError("Packaged fork notice file set differs")
    return FORK.validate_packaged_origin(lambda p: verified_notice(archive, notices, prefix + p), pins)


def android64_core_union(primary, archive, notices, root, revision, fork_origin):
    return selected_core_union(primary, archive, notices, root, revision, fork_origin, "android64")


def selected_core_union(primary, archive, notices, root, revision, fork_origin, profile):
    """Recompute the selected target union and bind each actual APK Core ELF."""
    pins = FORK.read_json((root / "config/foxcore-source-manifest.json").read_bytes())
    DELIVERY.require_fields(pins, profile)
    if pins.get("commit") != revision:
        raise ValueError("android64 source pin differs from selected Core revision")
    coverage_raw = verified_notice(archive, notices, "foxcore/ABI-COVERAGE.json")
    coverage = FORK.read_json(coverage_raw)
    expected_targets = dict(zip(DELIVERY.PROFILES[profile][1], DELIVERY.PROFILES[profile][0]))
    if (coverage.get("schema") != 1 or coverage.get("coreCommit") != revision or
            coverage.get("consumer") != "guard" or coverage.get("features") != "shipped" or
            coverage.get("coverage") != "complete" or coverage.get("primaryTarget") != DELIVERY.PROFILES[profile][1][0] or
            not isinstance(coverage.get("targets"), dict) or set(coverage["targets"]) != set(expected_targets)):
        raise ValueError("android64 selected coverage identity/target set differs")
    if coverage.get("androidDeliveryProfile") != profile or coverage.get("abis") != list(expected_targets.values()):
        raise ValueError("android64 selected coverage lacks exact profile")
    union_raw = verified_notice(archive, notices, "foxcore/cargo-notices/MANIFEST.json")
    catalog = FORK.read_json(union_raw)
    DELIVERY.require_fields(catalog, profile)
    if (catalog.get("schema_version") != 2 or catalog.get("coverage") != "complete" or catalog.get("uncovered") != [] or
            coverage.get("noticeManifestSha256") != sha256(union_raw)):
        raise ValueError("android64 notice union differs from selected coverage")
    nodes, graph, target_hashes, target_refs = {}, {}, {}, {}
    root_ref = primary["metadata"]["component"]["bom-ref"]
    for target, abi in expected_targets.items():
        raw = verified_notice(archive, notices, "foxcore/foxcore-" + target + ".cdx.json")
        document = expand_core_workspace(FORK.read_json(raw), revision, fork_origin)
        props = {r["name"]: r["value"] for r in document["metadata"].get("properties", [])}
        if props.get("foxhole:native:android-delivery-profile") != profile or props.get("foxhole:native:target") != target:
            raise ValueError("android64 selected target lacks explicit profile/target metadata")
        if document["metadata"]["component"]["bom-ref"] != root_ref:
            raise ValueError("android64 target roots differ")
        refs = set()
        for item in [document["metadata"]["component"]] + document["components"]:
            ref = item["bom-ref"]
            if ref in nodes and nodes[ref] != item:
                raise ValueError("android64 cross-target component identity/license conflict")
            nodes[ref] = item; refs.add(ref)
        edges = {(r["ref"], child) for r in document["dependencies"] for child in r["dependsOn"]}
        for row in document["dependencies"]: graph.setdefault(row["ref"], set()).update(row["dependsOn"])
        row = coverage["targets"][target]
        actual_library = archive.read("lib/" + abi + "/libfoxhole_native.so")
        if (row.get("abi") != abi or row.get("componentRefs") != sorted(refs) or row.get("components") != len(refs) or
                row.get("edges") != len(edges) or row.get("deliverySbomSha256") != sha256(raw) or
                row.get("nativeLibrarySha256") != sha256(actual_library) or
                row.get("originalSbomSha256") != props.get("foxhole:native:source-sbom-sha256")):
            raise ValueError("android64 target graph/native ELF differs from selected coverage")
        target_hashes[target] = sha256(raw); target_refs[target] = refs
    all_edges = {(ref, child) for ref, children in graph.items() for child in children}
    primary_refs = target_refs[DELIVERY.PROFILES[profile][1][0]]
    if any(coverage["targets"][t].get("componentSubsetOfPrimary") != (refs <= primary_refs) for t, refs in target_refs.items()):
        raise ValueError("android64 reported subset coverage differs")
    if (coverage.get("unionComponents") != len(nodes) or coverage.get("unionEdges") != len(all_edges) or
            coverage.get("unionComponentRefs") != sorted(nodes) or catalog.get("component_refs") != sorted(nodes) or
            catalog.get("target_sboms") != target_hashes):
        raise ValueError("android64 union counts/identities differ from its distinct graphs")
    covered, names, total = set(), set(), 0
    for row in catalog.get("files", []):
        path = FORK.safe_path(row["path"])
        if path in names or path in ("MANIFEST.json", "SHA256SUMS"):
            raise ValueError("Duplicate/reserved android64 original notice path")
        raw = verified_notice(archive, notices, "foxcore/cargo-notices/" + path)
        total += len(raw)
        owners = row.get("owners")
        if (sha256(raw) != row.get("sha256") or len(raw) > 8 * 1024 ** 2 or total > 128 * 1024 ** 2 or
                not isinstance(owners, list) or not owners or len(owners) != len(set(owners)) or not set(owners) <= set(nodes)):
            raise ValueError("android64 original notice bytes/owners differ")
        names.add(path); covered.update(owners)
    if covered != set(nodes): raise ValueError("android64 original notice union omits selected packages")
    union = copy.deepcopy(primary)
    union["components"] = [nodes[r] for r in sorted(nodes) if r != root_ref]
    union["dependencies"] = [{"ref": ref, "dependsOn": sorted(children)} for ref, children in sorted(graph.items())]
    local_count = sum(item.get("purl", "").startswith("pkg:generic/foxhole/") for item in nodes.values())
    for row in union["metadata"]["properties"]:
        if row["name"] == "foxhole:native:explicit-workspace-components": row["value"] = str(local_count)
    return union, coverage_raw


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
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        if any(len(pathlib.PurePosixPath(name).parts) != 3 for name in libraries):
            raise ValueError("Candidate has an invalid native APK path")
        actual_abis = {pathlib.PurePosixPath(name).parts[1] for name in libraries}
        source_pins_path = root / "config/foxcore-source-manifest.json"
        pinned_profile_required = False
        if source_pins_path.is_file() and not source_pins_path.is_symlink():
            pinned_source = FORK.read_json(source_pins_path.read_bytes())
            pinned_profile_required = (pinned_source.get("androidDeliveryProfile") in ("android64", "android-armv7", "android3", "android4") or
                any(isinstance(row, dict) and isinstance(row.get("path"), str) and
                    row["path"].startswith(FORK.VENDOR_PATH + "/") for row in pinned_source.get("files", [])))
        profile, _ = DELIVERY.read_profile(root, required=pinned_profile_required or actual_abis in ({"arm64-v8a", "x86_64"}, {"armeabi-v7a"}))
        if profile != "legacy4" and actual_abis != set(DELIVERY.PROFILES[profile][0]):
            raise ValueError("android64 APK must contain exactly both selected ABIs")
        manifest = json.loads(verified_notice(archive, notices, "MANIFEST.json"))
        if manifest.get("foxcoreRevision") != revision:
            raise ValueError("Candidate notice revision differs from the Core pin")
        if profile != "legacy4": DELIVERY.require_fields(manifest, profile)
        core_path = "foxcore/foxcore-" + DELIVERY.PROFILES[profile][1][0] + ".cdx.json"
        core_bytes = verified_notice(archive, notices, core_path)
        core_document = json.loads(core_bytes)
        fork_origin = packaged_core_fork(core_document, archive, notices, root, revision)
        core = expand_core_workspace(core_document, revision, fork_origin)
        coverage_raw = None
        if profile == "android64":
            core, coverage_raw = android64_core_union(core, archive, notices, root, revision, fork_origin)
        elif profile in ("android-armv7", "android3", "android4"):
            core, coverage_raw = selected_core_union(core, archive, notices, root, revision, fork_origin, profile)
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
        sentinel_revision = verified_notice(archive, notices, "sentinel/REVISION.txt").decode("ascii").strip()
        retained_role_sha = None
        if revision == "be0ff0ed269341b4c4ee6b1f6d9976344ecbd3f1":
            if sentinel_revision != "ea4f180fc2bf5a5dd108bd304bb86e97fa14a625" or coverage_raw is None:
                raise ValueError("Current be0 Guard requires the explicit original EA Sentinel evidence")
            spec = importlib.util.spec_from_file_location("retained_sentinel_apk_delivery", root / "scripts/retained-sentinel-role.py")
            retained_module = importlib.util.module_from_spec(spec); spec.loader.exec_module(retained_module)
            sentinel, retained_role_sha = retained_module.packaged(root, revision, profile,
                lambda name: verified_notice(archive, notices, name), archive.read, FORK.read_json(coverage_raw))
            sentinel_bytes = retained_module.encoded(sentinel)
            sentinel_path = "sentinel/selected-android64-original-EA-union"
            sentinel_root_ref = validate_bom(sentinel, "Retained original selected Sentinel BOM")
        elif profile != "legacy4":
            if sentinel_revision != revision:
                raise ValueError("Current Sentinel notice revision differs from the Core pin")
            spec = importlib.util.spec_from_file_location("current_sentinel_apk_delivery", root / "scripts/selected-sentinel-sbom.py")
            selected_module = importlib.util.module_from_spec(spec); spec.loader.exec_module(selected_module)
            sentinel, _coverage_digest = selected_module.packaged_delivery(
                lambda name: verified_notice(archive, notices, name), revision, profile)
            if profile in ("android-armv7", "android3", "android4"):
                sentinel_coverage = FORK.read_json(verified_notice(archive, notices, "sentinel/ABI-COVERAGE.json"))
                for target, abi in zip(DELIVERY.PROFILES[profile][1], DELIVERY.PROFILES[profile][0]):
                    row = sentinel_coverage["targets"][target]
                    if row.get("nativeLibrarySha256") != sha256(archive.read("lib/" + abi + "/libfoxhole_sentinel_yara.so")):
                        raise ValueError("Selected Sentinel ELF differs from its producer graph")
            sentinel_bytes = selected_module.encoded(sentinel)
            sentinel_path = "sentinel/selected-" + profile + "-current-union"
            sentinel_root_ref = validate_bom(sentinel, "Current android64 selected Sentinel BOM")
        else:
            if sentinel_revision != revision:
                raise ValueError("Sentinel notice revision differs from the Core pin")
            sentinel_path = "sentinel/sbom/sentinel-yara-all-targets.cdx.json"
            sentinel_bytes = verified_notice(archive, notices, sentinel_path)
            sentinel = json.loads(sentinel_bytes)
            sentinel_root_ref = validate_sentinel_bom(sentinel)
        sentinel_refs = {item["bom-ref"]: "sentinel:" + item["bom-ref"] for item in sentinel["components"]}
        sentinel_refs[sentinel_root_ref] = "sentinel:root"
        sentinel_root = copy.deepcopy(sentinel["metadata"]["component"])
        sentinel_root.setdefault("purl", sentinel_root_ref)
        if retained_role_sha is not None:
            sentinel_root.setdefault("properties", []).extend([
                property_value("retained-role-sha256", retained_role_sha),
                property_value("retained-original-core-revision", sentinel_revision),
                property_value("consumer-core-revision", revision)])
        for item in [sentinel_root] + copy.deepcopy(sentinel["components"]):
            item["bom-ref"] = sentinel_refs[item["bom-ref"]]
            item.setdefault("properties", []).append(property_value("origin", sentinel_path))
            components.append(item)
        for item in sentinel["dependencies"]:
            connect(sentinel_refs[item["ref"]], [sentinel_refs[ref] for ref in item["dependsOn"]])
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
        if not libraries or (profile == "legacy4" and any(not name.startswith("lib/arm64-v8a/") for name in libraries)):
            raise ValueError("Delivery BOM requires an arm64-v8a release APK")
        required = {"libfoxhole_native.so", "libfoxhole_sentinel_yara.so", "libi2pd.so",
                    "liblyrebird.so", "libconjure_client.so"}
        if not required.issubset({pathlib.PurePosixPath(p).name for p in libraries}):
            raise ValueError("Candidate is missing a required native runtime library")
        if profile != "legacy4" and any(
                not {"lib/" + abi + "/" + name for name in required} <= set(libraries)
                for abi in DELIVERY.PROFILES[profile][0]):
            raise ValueError("android64 APK omits a required runtime library from one ABI")
        for path in libraries:
            data = archive.read(path)
            name = pathlib.PurePosixPath(path).name
            digest = sha256(data)
            entry = component(path, digest, "generic", type="file", hashes=[{"alg": "SHA-256", "content": digest}])
            components.append(entry)
            connect(root_ref, [entry["bom-ref"]])
            if name == "libfoxhole_native.so":
                parents = ["foxcore:root"]
            elif name == "libfoxhole_sentinel_yara.so":
                parents = ["sentinel:root"]
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
                           if c.get("purl", "").startswith(f"pkg:maven/{group}/{artifact}@")]
                if len(parents) != 1:
                    raise ValueError(f"Native library lacks one resolved Maven owner: {name}")
                derived = derived_native_component(name, path, data, archive, notices, root)
                if derived is not None:
                    existing = [c for c in components if c["bom-ref"] == derived["bom-ref"]]
                    if existing:
                        if len(existing) != 1 or existing[0] != derived:
                            raise ValueError("Conflicting derived native component identity")
                    else:
                        components.append(derived)
                    connect(derived["bom-ref"], parents)
                    parents = [derived["bom-ref"]]
            connect(entry["bom-ref"], parents)
    result["metadata"].setdefault("properties", []).extend([
        property_value("coreRevision", revision), property_value("target", DELIVERY.PROFILES[profile][1][0]),
        property_value("apkSha256", sha256(apk.read_bytes())), property_value("coreBomSha256", sha256(core_bytes)),
        property_value("sentinelRevision", sentinel_revision),
        property_value("sentinelSelectedUnionSha256" if retained_role_sha is not None else "sentinelBomSha256", sha256(sentinel_bytes)),
        property_value("goInventorySha256", sha256(modules_bytes)),
        property_value("inventoryTool", "aggregate-delivery-sbom.py"),
        property_value("inventoryToolSha256", sha256(pathlib.Path(__file__).read_bytes())),
    ])
    if profile != "legacy4":
        result["metadata"]["properties"].extend([
            property_value("androidDeliveryProfile", profile),
            property_value("androidAbis", ",".join(DELIVERY.PROFILES[profile][0])),
            property_value("coreAbiCoverageSha256", sha256(coverage_raw)),
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
