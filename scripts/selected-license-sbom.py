"""Validate a selected Android Cargo inventory against its original producer inputs."""
import copy
from collections import OrderedDict
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
from urllib.parse import quote

FORK_SPEC = importlib.util.spec_from_file_location("guard_rustls_origin", Path(__file__).with_name("rustls-fork-origin.py"))
FORK = importlib.util.module_from_spec(FORK_SPEC)
FORK_SPEC.loader.exec_module(FORK)

HASH = re.compile(r"[0-9a-f]{64}")
TARGET_ABIS = {"aarch64-linux-android": "arm64-v8a", "armv7-linux-androideabi": "armeabi-v7a",
               "i686-linux-android": "x86", "x86_64-linux-android": "x86_64"}
PRIMARY_TARGET = "aarch64-linux-android"
TREE_TOKEN_CACHE_MAX_ENTRIES = 262_144
TREE_TOKEN_CACHE_MAX_BYTES = 64 * 1024 ** 2
TREE_MAX_LINES = 64_000_000

INPUTS = {"producerReceipt", "sourceManifest", "sourceArchive", "captureReceipt", "targetCapture",
          "cargoMetadata", "cargoTree", "originalSbom", "nativeLibrary", "cargoNoticeManifest"}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON member")
        result[key] = value
    return result


def read_json(data):
    return json.loads(data, object_pairs_hook=object_pairs)


def local_purl(name, version, revision):
    return "pkg:generic/foxhole/" + quote(name, safe="-._~") + "@" + version + "?core_commit=" + revision


def delivery_properties(local_count, original_hash, fork_count=0):
    return ([
        {"name": "foxhole:native:explicit-workspace-components", "value": str(local_count)},
        {"name": "foxhole:native:workspace-coverage", "value":
         "Every selected workspace node is explicit"},
        {"name": "foxhole:native:source-sbom-sha256", "value": original_hash},
    ] + ([{"name": "foxhole:native:vendored-fork-components", "value": str(fork_count)}] if fork_count else []))


def properties(rows):
    require(isinstance(rows, list), "Invalid inventory properties")
    result = {}
    for item in rows:
        require(isinstance(item, dict) and set(item) == {"name", "value"}, "Invalid inventory property")
        require(isinstance(item["name"], str) and item["name"] not in result and
                isinstance(item["value"], str), "Duplicate or invalid inventory property")
        result[item["name"]] = item["value"]
    return result


DELIVERY_TARGETS = {
    "android4": {"aarch64-linux-android": "arm64-v8a", "armv7-linux-androideabi": "armeabi-v7a",
                 "i686-linux-android": "x86", "x86_64-linux-android": "x86_64"},
    "legacy4": {"aarch64-linux-android": "arm64-v8a", "armv7-linux-androideabi": "armeabi-v7a",
                "i686-linux-android": "x86", "x86_64-linux-android": "x86_64"},
    "android64": {"aarch64-linux-android": "arm64-v8a", "x86_64-linux-android": "x86_64"},
    "android-armv7": {"armv7-linux-androideabi": "armeabi-v7a"},
    "android3": {"aarch64-linux-android": "arm64-v8a", "armv7-linux-androideabi": "armeabi-v7a", "x86_64-linux-android": "x86_64"},
}


def delivery_targets(profile):
    require(isinstance(profile, str) and profile in DELIVERY_TARGETS, "Unknown Android delivery profile")
    return DELIVERY_TARGETS[profile]


def primary_target(profile):
    return next(iter(delivery_targets(profile)))


def validate_delivery_profile(record, profile):
    targets = delivery_targets(profile)
    require(record.get("androidDeliveryProfile", "legacy4") == profile, "Android delivery profile differs")
    if profile != "legacy4":
        actual_targets = record.get("targets")
        target_names = list(actual_targets) if isinstance(actual_targets, dict) else actual_targets
        require(record.get("abis") == list(targets.values()) and isinstance(target_names, list) and
                len(target_names) == len(targets) and set(target_names) == set(targets),
                "Selected delivery requires its exact ABI/target authorities")


def captured_tree(path, metadata):
    labels = {}
    for package in metadata["packages"]:
        label = package["name"] + " v" + package["version"]
        if any("proc-macro" in target.get("kind", []) for target in package.get("targets", [])):
            label += " (proc-macro)"
        if package["source"] is None:
            label += " (" + str(PurePosixPath(package["manifest_path"]).parent) + ")"
        elif package["source"] not in ("registry+https://github.com/rust-lang/crates.io-index",
                                      "sparse+https://index.crates.io/"):
            continue
        labels.setdefault(label, []).append("urn:foxhole:cargo:" + digest(package["id"].encode()))
    stack, edges, root, number = [], {}, None, 0
    tokens = OrderedDict()
    token_bytes = 0
    with path.open("rb") as stream:
        while raw := stream.readline(4097):
            number += 1
            require(number <= TREE_MAX_LINES and len(raw) <= 4096 and raw.endswith(b"\n"), "Captured tree is oversized or incomplete")
            parsed = tokens.get(raw)
            cache_miss = parsed is None
            if cache_miss:
                line = raw[:-1].decode("utf-8")
                require(line and not any(ord(char) < 32 for char in line), "Captured tree has control characters")
                match = re.fullmatch(r"(0|[1-9][0-9]*)\|(.+)\|", line)
                require(match is not None, "Captured tree has unsupported or deduplicated output")
                found = labels.get(match[2], [])
                require(len(found) == 1, "Captured tree package is unknown or ambiguous")
                parsed = found[0], int(match[1])
            else:
                tokens.move_to_end(raw)
            ref, depth = parsed
            require(depth <= 256 and depth <= len(stack), "Captured tree depth jump")
            if root is None:
                require(depth == 0, "Captured tree has no root")
                root = ref
            else:
                require(depth > 0, "Captured tree has multiple roots")
            del stack[depth:]
            require(ref not in stack, "Captured tree has a package cycle")
            edges.setdefault(ref, set())
            if depth:
                edges[stack[-1]].add(ref)
            stack.append(ref)
            if cache_miss and TREE_TOKEN_CACHE_MAX_ENTRIES > 0 and len(raw) <= TREE_TOKEN_CACHE_MAX_BYTES:
                while tokens and (len(tokens) >= TREE_TOKEN_CACHE_MAX_ENTRIES or
                                  token_bytes + len(raw) > TREE_TOKEN_CACHE_MAX_BYTES):
                    evicted, _ = tokens.popitem(last=False)
                    token_bytes -= len(evicted)
                tokens[raw] = parsed
                token_bytes += len(raw)
    require(root is not None, "Captured tree is empty")
    return root, edges


def checked_input(root, record, limit):
    require(isinstance(record, dict) and set(record) == {"path", "sha256"}, "Invalid captured input record")
    relative = record["path"]
    require(isinstance(relative, str) and "\\" not in relative and
            all(ord(char) >= 32 for char in relative), "Invalid captured input path")
    path = PurePosixPath(relative)
    require(relative == str(path) and path.parts and not path.is_absolute() and
            not any(part in ("..", ".git", "AGENTS.md") for part in path.parts), "Input path escapes receipt root")
    source = root
    for part in path.parts:
        source = source / part
        require(not source.is_symlink(), "Captured inputs must not traverse symlinks")
    require(source.is_file() and 0 < source.stat().st_size <= limit, "Captured input absent or oversized")
    require(isinstance(record["sha256"], str) and HASH.fullmatch(record["sha256"]), "Invalid captured input hash")
    with source.open("rb") as stream:
        require(hashlib.file_digest(stream, "sha256").hexdigest() == record["sha256"], "Captured input bytes changed")
    return source


def validate_graph(bom, metadata, revision, pinned_version, fork_origin=None):
    require(bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") == "1.6", "Invalid selected BOM")
    root = bom["metadata"]["component"]
    require(root.get("name") == "foxcore-android", "Selected inventory is not the Android root")
    packages = {}
    for item in metadata["packages"]:
        ref = "urn:foxhole:cargo:" + digest(item["id"].encode())
        require(ref not in packages, "Duplicate captured package")
        packages[ref] = item
    nodes = [root] + bom["components"]
    refs = set()
    for node in nodes:
        ref = node.get("bom-ref")
        require(isinstance(ref, str) and ref in packages and ref not in refs, "Unknown or duplicate selected package")
        refs.add(ref)
        package = packages[ref]
        require((node.get("name"), node.get("version")) == (package["name"], package["version"]), "Package identity changed")
        require(isinstance(node.get("licenses"), list) and node["licenses"], "Selected package has no license declaration")
        if node.get("purl", "").startswith("pkg:generic/foxhole-source-fork/") or any(
                row.get("name") == "foxhole:source-kind" and row.get("value") == "vendored-modified"
                for row in node.get("properties", [])):
            require(package["source"] is None and package["name"] == "rustls", "Fork identity is not the captured local rustls package")
        if package["source"] is None and package["name"] == "rustls":
            require(fork_origin is not None and fork_origin.get("sourceBound") is True,
                    "Local rustls requires an archive-bound fork origin")
            workspace_root = PurePosixPath(metadata.get("workspace_root", ""))
            require(workspace_root.is_absolute() and ".." not in workspace_root.parts and
                    isinstance(metadata.get("workspace_members"), list) and
                    package["version"] == "0.23.46" and package["id"] not in metadata["workspace_members"] and
                    package.get("manifest_path") == str(workspace_root / fork_origin["vendorManifest"]),
                    "Local rustls is not the exact non-workspace vendored fork")
            FORK.validate_component(node, fork_origin, revision)
        elif package["source"] is None:
            require(node["version"] == pinned_version, "Workspace package differs from the pinned source version")
            props = properties(node.get("properties", []))
            require(props.get("cargo:source-revision") == revision and props.get("cargo:workspace-manifest"),
                    "Workspace package has no exact source identity")
    graph = {}
    for row in bom["dependencies"]:
        ref, children = row.get("ref"), row.get("dependsOn")
        require(ref in refs and ref not in graph and isinstance(children, list) and
                all(child in refs for child in children) and len(children) == len(set(children)),
                "Incomplete, duplicate or dangling selected edge")
        graph[ref] = children
    require(set(graph) == refs, "Selected graph omits package edges")
    captured_nodes = {}
    for node in metadata.get("resolve", {}).get("nodes", []):
        ref = "urn:foxhole:cargo:" + digest(node["id"].encode())
        require(ref not in captured_nodes, "Duplicate metadata dependency node")
        captured_nodes[ref] = node
    for ref, children in graph.items():
        require(ref in captured_nodes, "Selected package absent from captured resolution")
        allowed = set()
        for dependency in captured_nodes[ref]["deps"]:
            kinds = dependency.get("dep_kinds")
            require(isinstance(kinds, list) and kinds and
                    all(kind.get("kind") in (None, "build", "dev") for kind in kinds), "Invalid dependency kind")
            if any(kind.get("kind") in (None, "build") for kind in kinds):
                allowed.add("urn:foxhole:cargo:" + digest(dependency["pkg"].encode()))
        require(set(children) <= allowed, "Selected graph adds uncaptured runtime/build edges")
        if ref == root["bom-ref"]:
            require(set(children) == allowed, "Selected graph omits captured Android root dependencies")
    visited, pending = set(), [root["bom-ref"]]
    while pending:
        ref = pending.pop()
        if ref not in visited:
            visited.add(ref)
            pending.extend(graph[ref])
    require(visited == refs, "Selected graph contains disconnected packages")


def load_fork_origin(archive_path, source):
    """Read bound source bytes without extracting or executing archive content."""
    pins = source.get("files")
    require(isinstance(pins, dict) and all(isinstance(p, str) and isinstance(h, str) and HASH.fullmatch(h)
                                        for p, h in pins.items()), "Invalid native source digest map")
    with tarfile.open(archive_path, "r:*") as archive:
        members, names = {}, set()
        for member in archive:
            name = member.name.rstrip("/") if member.isdir() else member.name
            FORK.safe_path(name)
            require(name not in names, "Duplicate native source archive path")
            names.add(name)
            require(member.isdir() or member.isfile(), "Native source archive contains a link or special entry")
            if member.isfile():
                require(0 <= member.size <= 128 * 1024 ** 2, "Native source member exceeds byte bound")
                members[name] = member
        require(set(members) == set(pins), "Native source archive file set differs from source manifest")
        def read_source(path):
            member = members[path]
            require(member.size <= 2 * 1024 ** 2, "Fork source member exceeds byte bound")
            stream = archive.extractfile(member)
            require(stream is not None, "Fork source archive member cannot be read")
            with stream:
                raw = stream.read(2 * 1024 ** 2 + 1)
            require(len(raw) == member.size and digest(raw) == pins[path], "Fork source differs from source manifest")
            return raw, member.mode
        return FORK.validate_origin(read_source, members)


def validate_fork_notices(material, fork_origin, fork_ref):
    files = dict(material)
    expected = dict(fork_origin["noticeFiles"])
    require({p for p in files if p.startswith(FORK.NOTICE_PREFIX)} == set(expected) and
            all(files.get(p) == raw for p, raw in expected.items()), "Fork notices differ from bound source origin")
    catalog = read_json(files["MANIFEST.json"])
    owners = {row["path"]: row["owners"] for row in catalog["files"]}
    require(all(fork_ref in owners[p] for p in expected), "Fork notices lack their captured component owner")


def load_notice_files(sbom_data, receipt_path):
    receipt_path = Path(receipt_path)
    receipt = read_json(receipt_path.read_bytes())
    manifest_path = checked_input(receipt_path.parent.resolve(strict=True),
                                  receipt["inputs"]["cargoNoticeManifest"], 16 * 1024 ** 2)
    data = manifest_path.read_bytes()
    manifest = read_json(data)
    require(type(manifest.get("schema_version")) is int and manifest["schema_version"] == 1 and
            manifest.get("coverage") == "complete" and manifest.get("uncovered") == [] and
            manifest.get("sbom_sha256") == digest(sbom_data), "Cargo notice manifest differs from selected inventory")
    bom = read_json(sbom_data)
    refs = {bom["metadata"]["component"]["bom-ref"]} | {item["bom-ref"] for item in bom["components"]}
    result, covered, names, total = [], set(), set(), 0
    for item in manifest.get("files", []):
        name = item.get("path")
        owners = item.get("owners")
        require(isinstance(name, str) and name not in names and name not in ("MANIFEST.json", "SHA256SUMS") and
                isinstance(owners, list) and owners and all(isinstance(owner, str) and owner in refs for owner in owners)
                and len(owners) == len(set(owners)), "Invalid Cargo notice path/owners")
        source = checked_input(manifest_path.parent, {"path": name, "sha256": item.get("sha256")}, 8 * 1024 ** 2)
        content = source.read_bytes()
        total += len(content)
        require(total <= 128 * 1024 ** 2, "Cargo notice set exceeds its byte bound")
        names.add(name)
        covered.update(owners)
        result.append((name, content))
    require(covered == refs, "Cargo notice set omits selected package licenses")
    return [("MANIFEST.json", data)] + result


def _load_selected_target_sbom(sbom_path, receipt_path, revision, pinned_lock, pinned_version, expected_target="aarch64-linux-android", profile="legacy4"):
    selected_targets = delivery_targets(profile)
    require(expected_target in selected_targets, "Target is outside selected Android delivery profile")
    require(expected_target in TARGET_ABIS, "Unknown Guard Core target")
    receipt_path = Path(receipt_path)
    require(not receipt_path.is_symlink() and receipt_path.is_file() and receipt_path.stat().st_size <= 64 * 1024,
            "Invalid selected inventory receipt")
    receipt = read_json(receipt_path.read_bytes())
    require(type(receipt.get("schema")) is int and receipt["schema"] == 1 and receipt.get("coreCommit") == revision,
            "Selected inventory receipt differs from the pinned Core revision")
    require(receipt.get("consumer") == "guard" and receipt.get("target") == expected_target and
            receipt.get("features") == "shipped", "Selected inventory has a different consumer, target or feature set")
    require(set(receipt.get("inputs", {})) == INPUTS, "Selected inventory receipt has incomplete inputs")
    root = receipt_path.parent.resolve(strict=True)
    files = {name: checked_input(root, record, 4 * 1024 ** 3 if name == "cargoTree" else 128 * 1024 ** 2)
             for name, record in receipt["inputs"].items()}
    values = {name: read_json(files[name].read_bytes()) for name in
              ("producerReceipt", "sourceManifest", "captureReceipt", "targetCapture", "cargoMetadata", "originalSbom")}
    producer, source, capture, target = (values[name] for name in
                                       ("producerReceipt", "sourceManifest", "captureReceipt", "targetCapture"))
    require(type(producer.get("exitCode")) is int and producer["exitCode"] == 0 and
            producer.get("sourceMutations") == [] and producer.get("consumerInputMutations") == [],
            "Native producer failed or changed its inputs")
    require(producer.get("staticLoadAndRelroGate") == "pass" and
            set(producer.get("abis", [])) == set(selected_targets.values()) and
            len(producer.get("abis", [])) == len(selected_targets) and
            len(producer.get("libraries", [])) == len(selected_targets),
            "Complete genuine Guard Core producer for the selected profile required")
    require({row.get("path") for row in producer["libraries"]} ==
            {abi+"/libfoxhole_native.so" for abi in selected_targets.values()}, "Guard Core library inventory differs")
    for value in (producer, capture):
        require(value.get("consumer") == "guard" and value.get("coreCommit") == revision and
                value.get("features") == "shipped", "Native producer/capture identity mismatch")
    for value in (receipt, producer, source, capture, target):
        validate_delivery_profile(value, profile)
    input_hash = lambda name: receipt["inputs"][name]["sha256"]
    require(producer.get("sourceArchiveSha256") == source.get("archiveSha256") ==
            capture.get("sourceArchiveSha256") == input_hash("sourceArchive"), "Native source archive differs")
    require(capture.get("producerReceiptSha256") == input_hash("producerReceipt") and
            capture.get("status") == "selected-cargo-capture-complete", "Selected capture is not a successful producer capture")
    require(capture.get("steps") and all(type(step.get("exit_code")) is int and step["exit_code"] == 0
                                        for step in capture["steps"]), "Capture contains a failed producer step")
    lock_hash = digest(pinned_lock)
    require(source.get("files", {}).get("Cargo.lock") == capture.get("cargoLockSha256") ==
            target.get("cargo_lock_sha256") == lock_hash, "Capture used a different pinned Cargo.lock")
    require(type(target.get("schema_version")) is int and target["schema_version"] == 1 and
            target.get("target") == expected_target and target.get("core_revision") == revision and
            target.get("features") == ["shipped"], "Target capture identity mismatch")
    for kind, name in (("tree", "cargoTree"), ("metadata", "cargoMetadata")):
        require(type(target.get(kind, {}).get("exit_code")) is int and target[kind]["exit_code"] == 0 and
                target[kind].get("sha256") == input_hash(name), "Target capture input was not successfully captured")
    sbom_steps = [step for step in capture.get("steps", []) if step.get("label") == "sbom-" + expected_target]
    require(len(sbom_steps) == 1 and type(sbom_steps[0].get("exit_code")) is int and sbom_steps[0]["exit_code"] == 0,
            "Selected graph validation did not pass")
    libraries = [item for item in producer.get("libraries", []) if item.get("path") == TARGET_ABIS[expected_target] + "/libfoxhole_native.so"]
    require(len(libraries) == 1 and libraries[0].get("sha256") == input_hash("nativeLibrary") and
            libraries[0].get("bytes") == files["nativeLibrary"].stat().st_size, "Captured library differs from native producer")
    if profile != "legacy4":
        require(producer.get("sourceManifestSha256") == capture.get("sourceManifestSha256") ==
                target.get("source_manifest_sha256") == input_hash("sourceManifest") and
                target.get("source_archive_sha256") == input_hash("sourceArchive") and
                target.get("source_fork_origin_sha256") == source["files"].get(FORK.ORIGIN_PATH),
                "android64 target lacks exact producer/source archive/manifest/fork binding")
    original = values["originalSbom"]
    props = properties(original["metadata"].get("properties", []))
    expected = {"inventory": "guard-shipped-cargo-selected-normal-build-closure", "core-revision": revision,
                "target": expected_target, "features": "shipped", "cargo-lock-sha256": lock_hash,
                "tree-sha256": input_hash("cargoTree"), "metadata-sha256": input_hash("cargoMetadata")}
    require(all(props.get("foxhole:native:" + key) == value for key, value in expected.items()),
            "Original graph identity differs from captured inputs")
    selected_refs = {node["bom-ref"] for node in [original["metadata"]["component"]] + original["components"]}
    fork_packages = [package for package in values["cargoMetadata"]["packages"]
                     if package["source"] is None and package["name"] == "rustls" and
                     "urn:foxhole:cargo:" + digest(package["id"].encode()) in selected_refs]
    require(len(fork_packages) <= 1, "Multiple local rustls forks are not admitted")
    fork_origin = load_fork_origin(files["sourceArchive"], source) if fork_packages else None
    validate_graph(original, values["cargoMetadata"], revision, pinned_version, fork_origin)
    tree_root, tree_edges = captured_tree(files["cargoTree"], values["cargoMetadata"])
    graph_edges = {row["ref"]: set(row["dependsOn"]) for row in original["dependencies"]}
    require(tree_root == original["metadata"]["component"]["bom-ref"] and tree_edges == graph_edges,
            "Selected inventory differs from the actual target's captured tree")
    expected_view = copy.deepcopy(original)
    local_count = 0
    for node in [expected_view["metadata"]["component"]] + expected_view["components"]:
        if "purl" not in node:
            require(properties(node.get("properties", [])).get("cargo:source-revision") == revision,
                    "Non-workspace component has no package URL")
            node["purl"] = local_purl(node["name"], node["version"], revision)
            local_count += 1
    expected_view["metadata"]["properties"] += delivery_properties(local_count, input_hash("originalSbom"), len(fork_packages))
    if profile != "legacy4":
        expected_view["metadata"]["properties"].append(
            {"name": "foxhole:native:android-delivery-profile", "value": profile})
    sbom_path = Path(sbom_path)
    require(sbom_path.is_file() and not sbom_path.is_symlink() and sbom_path.stat().st_size <= 16 * 1024 ** 2,
            "Selected inventory absent or oversized")
    data = sbom_path.read_bytes()
    require(digest(data) == receipt.get("sbomSha256") and read_json(data) == expected_view,
            "Selected delivery view changed the captured package graph")
    material = load_notice_files(data, receipt_path)
    if fork_origin is not None:
        validate_fork_notices(material, fork_origin, "urn:foxhole:cargo:" + digest(fork_packages[0]["id"].encode()))
    return data

def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def combined_evidence(targets, revision, profile="legacy4"):
    """Compute coverage from validated target graphs/files, never from reported counts."""
    nodes, all_edges, notice_bytes, notice_owners, target_rows = {}, set(), {}, {}, {}
    shared = None
    primary_refs = set()
    for target, item in targets.items():
        data, receipt_path = item["data"], item["receipt"]
        receipt = read_json(receipt_path.read_bytes())
        inputs = receipt["inputs"]
        identity = {name: inputs[name]["sha256"] for name in
                    ("producerReceipt", "sourceManifest", "sourceArchive", "captureReceipt")}
        capture_path = checked_input(receipt_path.parent.resolve(strict=True), inputs["captureReceipt"], 128 * 1024**2)
        identity["cargoLockSha256"] = read_json(capture_path.read_bytes())["cargoLockSha256"]
        require(shared is None or shared == identity, "Target graphs have different producer/source authorities")
        shared = identity
        bom = read_json(data)
        refs = set()
        for node in [bom["metadata"]["component"]] + bom["components"]:
            ref = node["bom-ref"]
            require(ref not in nodes or nodes[ref] == node, "Cross-target package identity/license conflict")
            nodes[ref] = node
            refs.add(ref)
        if target == primary_target(profile):
            primary_refs = refs
        edges = {(row["ref"], child) for row in bom["dependencies"] for child in row["dependsOn"]}
        all_edges |= edges
        material = dict(load_notice_files(data, receipt_path))
        manifest = read_json(material.pop("MANIFEST.json"))
        for row in manifest["files"]:
            name = row["path"]
            body = material[name]
            require(name not in notice_bytes or notice_bytes[name] == body, "Cross-target original notice bytes collide")
            notice_bytes[name] = body
            notice_owners.setdefault(name, set()).update(row["owners"])
        target_rows[target] = {"abi": TARGET_ABIS[target], "componentRefs": sorted(refs),
                              "components": len(refs), "edges": len(edges),
                              "originalSbomSha256": inputs["originalSbom"]["sha256"],
                              "deliverySbomSha256": digest(data),
                              "nativeLibrarySha256": inputs["nativeLibrary"]["sha256"],
                              "noticeManifestSha256": inputs["cargoNoticeManifest"]["sha256"]}
    selected_targets = delivery_targets(profile)
    require(set(targets) == set(selected_targets), "All selected Guard Core target graphs are required")
    require(bool(notice_owners), "Original notice union is empty")
    covered = set().union(*notice_owners.values())
    require(covered == set(nodes), "Union original notices do not cover every selected ABI component")
    notice_manifest = {"schema_version": 2, "coverage": "complete", "uncovered": [],
                       "target_sboms": {target: row["deliverySbomSha256"] for target, row in target_rows.items()},
                       "component_refs": sorted(nodes),
                       "files": [{"path": name, "sha256": digest(body), "owners": sorted(notice_owners[name])}
                                 for name, body in sorted(notice_bytes.items())]}
    if profile != "legacy4":
        notice_manifest.update(androidDeliveryProfile=profile, abis=list(selected_targets.values()), targets=list(selected_targets))
    notice_manifest_bytes = encoded(notice_manifest)
    for row in target_rows.values():
        row["componentSubsetOfPrimary"] = set(row["componentRefs"]) <= primary_refs
    coverage = {"schema": 1, "coreCommit": revision, "consumer": "guard", "features": "shipped",
                "primaryTarget": primary_target(profile), "authorities": shared,
                "targets": target_rows, "unionComponents": len(nodes), "unionEdges": len(all_edges),
                "unionComponentRefs": sorted(nodes), "noticeManifestSha256": digest(notice_manifest_bytes),
                "coverage": "complete", "scope": "Four distinct captured Cargo graphs and original notice union; not an all-targets BOM or whole APK/toolchain inventory"}
    if profile != "legacy4":
        coverage.update(androidDeliveryProfile=profile, abis=list(selected_targets.values()))
        coverage["scope"] = ("Two distinct android64 captured Cargo graphs, exact producer/native source and original notice union" if profile == "android64" else ("Four distinct android4 captured Cargo graphs, exact per-ABI producer/native source and original notice union" if profile == "android4" else ("Three distinct android3 captured Cargo graphs, exact producer/native source and original notice union" if profile == "android3" else "One exact ARMv7 captured Cargo graph, producer/native source and original notice union")))
    return {"targets": {target: item["data"] for target, item in targets.items()},
            "notices": [("MANIFEST.json", notice_manifest_bytes)] + sorted(notice_bytes.items()),
            "coverage": encoded(coverage)}



def delivery_input_fence(sbom_path, receipt_path):
    """Retain a finite immutable identity/hash fence, never cached file contents."""
    paths = {}
    def retain(path, expected):
        info = path.stat()
        with path.open("rb") as stream:
            require(hashlib.file_digest(stream, "sha256").hexdigest() == expected, "Selected input changed before validation")
        keys = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
        after = path.stat()
        require(all(getattr(info, key) == getattr(after, key) for key in keys), "Selected input changed while fenced")
        paths[str(path)] = (expected, tuple(getattr(info, key) for key in keys))
    def checked(root, row, limit):
        path = checked_input(root, row, limit); retain(path, row["sha256"]); return path
    receipt_path = Path(receipt_path)
    require(not receipt_path.is_symlink() and receipt_path.is_file() and receipt_path.stat().st_size <= 128 * 1024, "Invalid four-target delivery receipt")
    raw = receipt_path.read_bytes(); receipt = read_json(raw); retain(receipt_path, digest(raw))
    selected_targets = delivery_targets(receipt.get("androidDeliveryProfile", "legacy4"))
    validate_delivery_profile(receipt, receipt.get("androidDeliveryProfile", "legacy4"))
    root = receipt_path.parent.resolve(strict=True)
    require(isinstance(receipt.get("targets"), dict) and set(receipt["targets"]) == set(selected_targets), "All four Guard Core target receipts are required")
    for records in receipt["targets"].values():
        target_receipt = checked(root, records["receipt"], 64 * 1024)
        checked(root, records["sbom"], 16 * 1024**2)
        target = read_json(target_receipt.read_bytes())
        target_root = target_receipt.parent.resolve(strict=True)
        require(set(target.get("inputs", {})) == INPUTS, "Selected inventory receipt has incomplete inputs")
        inputs = {name: checked(target_root, row, 4 * 1024**3 if name == "cargoTree" else 128 * 1024**2)
                  for name, row in target["inputs"].items()}
        manifest_path = inputs["cargoNoticeManifest"]
        manifest = read_json(manifest_path.read_bytes())
        for row in manifest.get("files", []):
            checked(manifest_path.parent, {"path": row["path"], "sha256": row["sha256"]}, 8 * 1024**2)
    checked(root, receipt["abiCoverage"], 1024 * 1024)
    checked(root, receipt["unionNoticeManifest"], 16 * 1024**2)
    primary = Path(sbom_path)
    require(primary.is_file() and not primary.is_symlink() and primary.stat().st_size <= 16 * 1024**2, "Invalid primary ARM64 delivery path")
    retain(primary, receipt["sbomSha256"])
    return tuple(sorted((name, *value) for name, value in paths.items()))


def recheck_delivery_input_fence(fence):
    keys = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
    for name, expected, identity in fence:
        path = Path(name)
        require(all(not part.is_symlink() for part in (path, *path.parents)), "Selected input path changed after validation")
        require(tuple(getattr(path.stat(), key) for key in keys) == identity, "Selected input metadata changed after validation")
        with path.open("rb") as stream:
            require(hashlib.file_digest(stream, "sha256").hexdigest() == expected, "Selected input bytes changed after validation")
        require(tuple(getattr(path.stat(), key) for key in keys) == identity, "Selected input metadata changed while rechecked")


def load_selected_delivery(sbom_path, receipt_path, revision, pinned_lock, pinned_version, profile="legacy4"):
    receipt_path = Path(receipt_path)
    require(not receipt_path.is_symlink() and receipt_path.is_file()
            and receipt_path.stat().st_size <= 128 * 1024, "Invalid four-target delivery receipt")
    receipt = read_json(receipt_path.read_bytes())
    selected_targets = delivery_targets(profile)
    validate_delivery_profile(receipt, profile)
    require(type(receipt.get("schema")) is int and receipt["schema"] == 2
            and receipt.get("coreCommit") == revision and receipt.get("consumer") == "guard"
            and receipt.get("features") == "shipped" and receipt.get("target") == primary_target(profile),
            "Four-target Guard Core delivery receipt required")
    require(isinstance(receipt.get("targets"), dict) and set(receipt["targets"]) == set(selected_targets),
            "All four Guard Core target receipts are required")
    root = receipt_path.parent.resolve(strict=True)
    targets = {}
    for target, records in receipt["targets"].items():
        require(isinstance(records, dict) and set(records) == {"receipt", "sbom"}, "Invalid target delivery inputs")
        target_receipt = checked_input(root, records["receipt"], 64 * 1024)
        target_sbom = checked_input(root, records["sbom"], 16 * 1024**2)
        data = _load_selected_target_sbom(target_sbom, target_receipt, revision, pinned_lock,
                                          pinned_version, target, profile)
        targets[target] = {"data": data, "receipt": target_receipt}
    primary = targets[primary_target(profile)]
    sbom_path = Path(sbom_path)
    require(sbom_path.is_file() and not sbom_path.is_symlink() and sbom_path.stat().st_size <= 16 * 1024**2,
            "Invalid primary ARM64 delivery path")
    require(sbom_path.read_bytes() == primary["data"] and receipt.get("sbomSha256") == digest(primary["data"]),
            "Primary ARM64 delivery differs from its distinct captured graph")
    require(receipt.get("inputs") == read_json(primary["receipt"].read_bytes())["inputs"],
            "Primary input compatibility view changed")
    combined = combined_evidence(targets, revision, profile)
    coverage_path = checked_input(root, receipt["abiCoverage"], 1024 * 1024)
    union_path = checked_input(root, receipt["unionNoticeManifest"], 16 * 1024**2)
    require(coverage_path.read_bytes() == combined["coverage"], "Computed ABI coverage certificate differs")
    require(union_path.read_bytes() == combined["notices"][0][1], "Computed original notice union differs")
    return combined


def load_selected_sbom(sbom_path, receipt_path, revision, pinned_lock, pinned_version, profile="legacy4"):
    return load_selected_delivery(sbom_path, receipt_path, revision, pinned_lock,
                                  pinned_version, profile)["targets"][primary_target(profile)]
