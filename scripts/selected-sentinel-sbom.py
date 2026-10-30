"""Validate a selected public Sentinel own-workspace Cargo inventory against its original producer inputs."""
import copy
from collections import OrderedDict
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from urllib.parse import quote

HASH = re.compile(r"[0-9a-f]{64}")
TARGET_ABIS = {"aarch64-linux-android": "arm64-v8a", "armv7-linux-androideabi": "armeabi-v7a",
               "i686-linux-android": "x86", "x86_64-linux-android": "x86_64"}
PRIMARY_TARGET = "aarch64-linux-android"
CURRENT_TARGET_ABIS = {target: TARGET_ABIS[target] for target in ("aarch64-linux-android", "x86_64-linux-android")}


def delivery_targets(profile):
    require(profile in ("legacy4", "android64", "android-armv7", "android3", "android4"), "Unknown Sentinel delivery profile")
    if profile == "android3":
        return {target: TARGET_ABIS[target] for target in ("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android")}
    if profile == "android-armv7":
        return {"armv7-linux-androideabi": "armeabi-v7a"}
    return TARGET_ABIS if profile in ("legacy4", "android4") else CURRENT_TARGET_ABIS


def primary_target(profile):
    return next(iter(delivery_targets(profile)))


def require_delivery_profile(value, profile, target_kind="list"):
    targets = delivery_targets(profile)
    if profile != "legacy4":
        require(value.get("androidDeliveryProfile") == profile
                and value.get("abis") == list(targets.values()), f"Current Sentinel {profile} profile required")
        if target_kind == "map":
            require(isinstance(value.get("targets"), dict) and set(value["targets"]) == set(targets),
                    f"Exact Sentinel {profile} target map required")
        else:
            require(value.get("targets") == list(targets), f"Exact Sentinel {profile} target list required")
    else:
        require(value.get("androidDeliveryProfile") in (None, "legacy4"), "Historical Sentinel profile differs")
    return targets
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


def delivery_properties(local_count, original_hash):
    return [
        {"name": "foxhole:native:explicit-workspace-components", "value": str(local_count)},
        {"name": "foxhole:native:workspace-coverage", "value":
         "Every selected workspace node is explicit"},
        {"name": "foxhole:native:source-sbom-sha256", "value": original_hash},
    ]


def properties(rows):
    require(isinstance(rows, list), "Invalid inventory properties")
    result = {}
    for item in rows:
        require(isinstance(item, dict) and set(item) == {"name", "value"}, "Invalid inventory property")
        require(isinstance(item["name"], str) and item["name"] not in result and
                isinstance(item["value"], str), "Duplicate or invalid inventory property")
        result[item["name"]] = item["value"]
    return result


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
                parsed = match[2], int(match[1])
            else:
                tokens.move_to_end(raw)
            label, depth = parsed
            found = labels.get(label, [])
            require(len(found) == 1, "Captured tree package is unknown or ambiguous")
            ref = found[0]
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


def validate_graph(bom, metadata, revision, pinned_manifests):
    require(bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") == "1.6", "Invalid selected BOM")
    root = bom["metadata"]["component"]
    require(root.get("name") == "foxhole-sentinel-yara" and root.get("version") == "0.1.0", "Selected inventory is not the Android root")
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
        if package["source"] is None:
            require(node["name"] in pinned_manifests and node["version"] == pinned_manifests[node["name"]]["version"], "Workspace package differs from the pinned source version")
            props = properties(node.get("properties", []))
            require(props.get("cargo:source-revision") == revision and props.get("cargo:workspace-manifest") == ({"foxhole-sentinel-yara": "Cargo.toml", "yara-x": "vendor/yara-x/Cargo.toml", "yara-x-parser": "vendor/yara-x-parser/Cargo.toml"}[node["name"]]),
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


def _load_selected_target_sbom(sbom_path, receipt_path, revision, pinned_lock, pinned_manifest_bytes, expected_target="aarch64-linux-android", delivery_profile="legacy4"):
    target_abis = delivery_targets(delivery_profile)
    require(expected_target in target_abis, "Unknown Sentinel target for delivery profile")
    import tomllib
    expected_paths = {"foxhole-sentinel-yara": "native/sentinel-yara/Cargo.toml", "yara-x": "native/sentinel-yara/vendor/yara-x/Cargo.toml", "yara-x-parser": "native/sentinel-yara/vendor/yara-x-parser/Cargo.toml"}
    require(set(pinned_manifest_bytes) == set(expected_paths), "Exact Sentinel local manifest set required")
    pinned_manifests = {name: tomllib.loads(body.decode())["package"] for name, body in pinned_manifest_bytes.items()}
    require(all(v["name"] == k for k, v in pinned_manifests.items()), "Sentinel local package names differ")
    receipt_path = Path(receipt_path)
    require(not receipt_path.is_symlink() and receipt_path.is_file() and receipt_path.stat().st_size <= 64 * 1024,
            "Invalid selected inventory receipt")
    receipt = read_json(receipt_path.read_bytes())
    require(type(receipt.get("schema")) is int and receipt["schema"] == 1 and receipt.get("coreCommit") == revision,
            "Selected inventory receipt differs from the pinned Core revision")
    require(receipt.get("consumer") == "sentinel" and receipt.get("target") == expected_target and
            receipt.get("features") == "sentinel-v2", "Selected inventory has a different consumer, target or feature set")
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
    expected_abis = set(target_abis.values())
    if delivery_profile != "legacy4":
        for record in (producer, source, capture):
            require_delivery_profile(record, delivery_profile)
    require(producer.get("staticLoadAndRelroGate") == "pass" and set(producer.get("abis", [])) == expected_abis
            and len(producer.get("abis", [])) == len(expected_abis)
            and len(producer.get("libraries", [])) == len(expected_abis), "Complete genuine Sentinel producer for delivery profile required")
    require({row.get("path") for row in producer["libraries"]} == {abi+"/libfoxhole_sentinel_yara.so" for abi in expected_abis}, "Sentinel library inventory differs")
    for value in (producer, capture):
        require(value.get("consumer") == "sentinel" and value.get("coreCommit") == revision and
                value.get("features") == "sentinel-v2", "Native producer/capture identity mismatch")
    input_hash = lambda name: receipt["inputs"][name]["sha256"]
    require(producer.get("sourceArchiveSha256") == source.get("archiveSha256") ==
            capture.get("sourceArchiveSha256") == input_hash("sourceArchive"), "Native source archive differs")
    require(capture.get("producerReceiptSha256") == input_hash("producerReceipt") and
            capture.get("status") == "selected-cargo-capture-complete", "Selected capture is not a successful producer capture")
    require(capture.get("steps") and all(type(step.get("exit_code")) is int and step["exit_code"] == 0
                                        for step in capture["steps"]), "Capture contains a failed producer step")
    lock_hash = digest(pinned_lock)
    require(source.get("files", {}).get("native/sentinel-yara/Cargo.lock") == capture.get("cargoLockSha256") ==
            target.get("cargo_lock_sha256") == lock_hash, "Capture used a different pinned Cargo.lock")
    require(type(target.get("schema_version")) is int and target["schema_version"] == 1 and
            target.get("target") == expected_target and target.get("core_revision") == revision and
            target.get("features") == [], "Target capture identity mismatch")
    for kind, name in (("tree", "cargoTree"), ("metadata", "cargoMetadata")):
        require(type(target.get(kind, {}).get("exit_code")) is int and target[kind]["exit_code"] == 0 and
                target[kind].get("sha256") == input_hash(name), "Target capture input was not successfully captured")
    sbom_steps = [step for step in capture.get("steps", []) if step.get("label") == "sbom-" + expected_target]
    require(len(sbom_steps) == 1 and type(sbom_steps[0].get("exit_code")) is int and sbom_steps[0]["exit_code"] == 0,
            "Selected graph validation did not pass")
    libraries = [item for item in producer.get("libraries", []) if item.get("path") == TARGET_ABIS[expected_target] + "/libfoxhole_sentinel_yara.so"]
    require(len(libraries) == 1 and libraries[0].get("sha256") == input_hash("nativeLibrary") and
            libraries[0].get("bytes") == files["nativeLibrary"].stat().st_size, "Captured library differs from native producer")
    original = values["originalSbom"]
    props = properties(original["metadata"].get("properties", []))
    expected = {"inventory": "public-sentinel-own-workspace-selected-normal-build-closure", "core-revision": revision,
                "target": expected_target, "features": "", "cargo-lock-sha256": lock_hash,
                "tree-sha256": input_hash("cargoTree"), "metadata-sha256": input_hash("cargoMetadata")}
    require(all(props.get("foxhole:native:" + key) == value for key, value in expected.items()),
            "Original graph identity differs from captured inputs")
    require(all(source.get("files", {}).get(expected_paths[name]) == digest(body) for name, body in pinned_manifest_bytes.items()), "Sentinel local manifests differ from producer source")
    validate_graph(original, values["cargoMetadata"], revision, pinned_manifests)
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
    expected_view["metadata"]["properties"] += delivery_properties(local_count, input_hash("originalSbom"))
    sbom_path = Path(sbom_path)
    require(sbom_path.is_file() and not sbom_path.is_symlink() and sbom_path.stat().st_size <= 16 * 1024 ** 2,
            "Selected inventory absent or oversized")
    data = sbom_path.read_bytes()
    require(digest(data) == receipt.get("sbomSha256") and read_json(data) == expected_view,
            "Selected delivery view changed the captured package graph")
    load_notice_files(data, receipt_path)
    return data


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def combined_evidence(targets, revision, delivery_profile="legacy4"):
    """Compute coverage from validated target graphs/files, never from reported counts."""
    target_abis = delivery_targets(delivery_profile)
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
        if target == primary_target(delivery_profile):
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
    require(set(targets) == set(target_abis), "Exact Sentinel delivery target graphs are required")
    require(bool(notice_owners), "Original notice union is empty")
    covered = set().union(*notice_owners.values())
    require(covered == set(nodes), "Union original notices do not cover every selected ABI component")
    notice_manifest = {"schema_version": 2, "coverage": "complete", "uncovered": [],
                       "target_sboms": {target: row["deliverySbomSha256"] for target, row in target_rows.items()},
                       "component_refs": sorted(nodes),
                       "files": [{"path": name, "sha256": digest(body), "owners": sorted(notice_owners[name])}
                                 for name, body in sorted(notice_bytes.items())]}
    notice_manifest_bytes = encoded(notice_manifest)
    for row in target_rows.values():
        row["componentSubsetOfPrimary"] = set(row["componentRefs"]) <= primary_refs
    coverage = {"schema": 1, "coreCommit": revision, "consumer": "sentinel", "features": "sentinel-v2",
                "primaryTarget": primary_target(delivery_profile), "authorities": shared,
                "targets": target_rows, "unionComponents": len(nodes), "unionEdges": len(all_edges),
                "unionComponentRefs": sorted(nodes), "noticeManifestSha256": digest(notice_manifest_bytes),
                "coverage": "complete", "scope": "Four distinct captured Cargo graphs and original notice union; not an all-targets BOM or whole APK/toolchain inventory"}
    if delivery_profile != "legacy4":
        coverage.update(androidDeliveryProfile=delivery_profile, abis=list(target_abis.values()),
                        scope=("Two distinct android64 captured Cargo graphs and original notice union; no current32 or whole APK/toolchain claim" if delivery_profile == "android64" else ("Four distinct android4 captured Cargo graphs and original notice union; no runtime or whole APK/toolchain claim" if delivery_profile == "android4" else ("Three distinct android3 captured Cargo graphs and original notice union; no runtime or whole APK/toolchain claim" if delivery_profile == "android3" else "One exact ARMv7 captured Cargo graph and original notice union; no runtime or whole APK/toolchain claim"))))
    return {"targets": {target: item["data"] for target, item in targets.items()},
            "notices": [("MANIFEST.json", notice_manifest_bytes)] + sorted(notice_bytes.items()),
            "coverage": encoded(coverage)}


def load_selected_delivery(sbom_path, receipt_path, revision, pinned_lock, pinned_manifest_bytes, delivery_profile="legacy4"):
    target_abis = delivery_targets(delivery_profile)
    receipt_path = Path(receipt_path)
    require(not receipt_path.is_symlink() and receipt_path.is_file()
            and receipt_path.stat().st_size <= 128 * 1024, "Invalid four-target delivery receipt")
    receipt = read_json(receipt_path.read_bytes())
    require(type(receipt.get("schema")) is int and receipt["schema"] == 2
            and receipt.get("coreCommit") == revision and receipt.get("consumer") == "sentinel"
            and receipt.get("features") == "sentinel-v2" and receipt.get("target") == primary_target(delivery_profile),
            "Four-target Sentinel delivery receipt required")
    if delivery_profile != "legacy4":
        require_delivery_profile(receipt, delivery_profile, "map")
    require(isinstance(receipt.get("targets"), dict) and set(receipt["targets"]) == set(target_abis),
            "Exact Sentinel delivery target receipts are required")
    root = receipt_path.parent.resolve(strict=True)
    targets = {}
    for target, records in receipt["targets"].items():
        require(isinstance(records, dict) and set(records) == {"receipt", "sbom"}, "Invalid target delivery inputs")
        target_receipt = checked_input(root, records["receipt"], 64 * 1024)
        target_sbom = checked_input(root, records["sbom"], 16 * 1024**2)
        data = _load_selected_target_sbom(target_sbom, target_receipt, revision, pinned_lock,
                                          pinned_manifest_bytes, target, delivery_profile)
        targets[target] = {"data": data, "receipt": target_receipt}
    primary = targets[primary_target(delivery_profile)]
    sbom_path = Path(sbom_path)
    require(sbom_path.is_file() and not sbom_path.is_symlink() and sbom_path.stat().st_size <= 16 * 1024**2,
            "Invalid primary ARM64 delivery path")
    require(sbom_path.read_bytes() == primary["data"] and receipt.get("sbomSha256") == digest(primary["data"]),
            "Primary ARM64 delivery differs from its distinct captured graph")
    require(receipt.get("inputs") == read_json(primary["receipt"].read_bytes())["inputs"],
            "Primary input compatibility view changed")
    combined = combined_evidence(targets, revision, delivery_profile)
    coverage_path = checked_input(root, receipt["abiCoverage"], 1024 * 1024)
    union_path = checked_input(root, receipt["unionNoticeManifest"], 16 * 1024**2)
    require(coverage_path.read_bytes() == combined["coverage"], "Computed ABI coverage certificate differs")
    require(union_path.read_bytes() == combined["notices"][0][1], "Computed original notice union differs")
    return combined


def load_selected_sbom(sbom_path, receipt_path, revision, pinned_lock, pinned_manifest_bytes, delivery_profile="legacy4"):
    return load_selected_delivery(sbom_path, receipt_path, revision, pinned_lock,
                                  pinned_manifest_bytes, delivery_profile)["targets"][primary_target(delivery_profile)]


def packaged_android64(read_notice, revision):
    return packaged_delivery(read_notice, revision, "android64")


def packaged_delivery(read_notice, revision, delivery_profile):
    """Recheck the generated two-target union inside the verified APK notices."""
    target_abis = delivery_targets(delivery_profile)
    coverage_raw = read_notice("sentinel/ABI-COVERAGE.json")
    coverage = read_json(coverage_raw)
    require_delivery_profile(coverage, delivery_profile, "map")
    require(coverage.get("schema") == 1 and coverage.get("coreCommit") == revision
            and coverage.get("consumer") == "sentinel" and coverage.get("features") == "sentinel-v2"
            and coverage.get("primaryTarget") == primary_target(delivery_profile) and coverage.get("coverage") == "complete",
            "Current Sentinel coverage identity differs")
    require(isinstance(coverage.get("targets"), dict) and set(coverage["targets"]) == set(target_abis),
            "Exact current Sentinel packaged targets required")
    authority = coverage.get("authorities")
    require(isinstance(authority, dict) and set(authority) ==
            {"producerReceipt", "sourceManifest", "sourceArchive", "captureReceipt", "cargoLockSha256"}
            and all(isinstance(v, str) and HASH.fullmatch(v) for v in authority.values()),
            "Current Sentinel producer/source/lock authorities missing")
    nodes, edges, raw_targets, primary = {}, set(), {}, None
    for target, abi in target_abis.items():
        raw = read_notice("sentinel/selected-" + target + ".cdx.json")
        bom = read_json(raw); row = coverage["targets"][target]
        require(isinstance(row, dict), "Current Sentinel target coverage malformed")
        require(row.get("abi") == abi and row.get("deliverySbomSha256") == digest(raw),
                "Current Sentinel selected target bytes differ")
        require(bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") == "1.6"
                and isinstance(bom.get("components"), list) and isinstance(bom.get("dependencies"), list),
                "Current Sentinel selected BOM malformed")
        root = bom.get("metadata", {}).get("component", {})
        props = properties(bom.get("metadata", {}).get("properties", []))
        require(root.get("name") == "foxhole-sentinel-yara" and root.get("version") == "0.1.0"
                and root.get("purl") == local_purl(root["name"], root["version"], revision)
                and props.get("foxhole:native:core-revision") == revision
                and props.get("foxhole:native:target") == target
                and props.get("foxhole:native:cargo-lock-sha256") == authority["cargoLockSha256"],
                "Current Sentinel selected BOM source/profile differs")
        refs = set()
        for node in [root] + bom["components"]:
            ref = node.get("bom-ref")
            require(isinstance(ref, str) and ref not in refs and node.get("licenses")
                    and isinstance(node.get("purl"), str), "Current Sentinel package identity/license incomplete")
            require(ref not in nodes or nodes[ref] == node, "Current Sentinel cross-target package collision")
            nodes[ref] = node; refs.add(ref)
        require(all(isinstance(r, dict) and isinstance(r.get("ref"), str)
                    and isinstance(r.get("dependsOn"), list)
                    and all(isinstance(child, str) for child in r["dependsOn"])
                    and len(r["dependsOn"]) == len(set(r["dependsOn"])) for r in bom["dependencies"]),
                "Current Sentinel dependency rows malformed")
        graph = {r["ref"]: set(r["dependsOn"]) for r in bom["dependencies"]}
        require(len(graph) == len(bom["dependencies"]) and set(graph) == refs
                and all(children <= refs for children in graph.values()), "Current Sentinel graph incomplete")
        reachable, pending = set(), [root["bom-ref"]]
        while pending:
            ref = pending.pop()
            if ref not in reachable:
                reachable.add(ref); pending.extend(graph[ref])
        require(reachable == refs and set(row.get("componentRefs", [])) == refs
                and row.get("components") == len(refs)
                and row.get("edges") == sum(len(v) for v in graph.values()), "Current Sentinel graph coverage differs")
        edges |= {(ref, child) for ref, children in graph.items() for child in children}
        raw_targets[target] = digest(raw)
        if target == primary_target(delivery_profile):
            primary = bom
    notice_raw = read_notice("sentinel/cargo-notices/MANIFEST.json")
    notice = read_json(notice_raw)
    require(coverage.get("noticeManifestSha256") == digest(notice_raw)
            and notice.get("schema_version") == 2 and notice.get("coverage") == "complete"
            and notice.get("uncovered") == [] and notice.get("target_sboms") == raw_targets
            and set(notice.get("component_refs", [])) == set(nodes), "Current Sentinel original notice union differs")
    seen, owners = set(), set()
    for row in notice.get("files", []):
        require(isinstance(row, dict), "Current Sentinel notice row malformed")
        name = row.get("path"); path = PurePosixPath(name) if isinstance(name, str) else None
        require(path is not None and "\\" not in name and not path.is_absolute() and str(path) == name and name not in seen
                and all(p not in (".", "..", ".git", "AGENTS.md") for p in path.parts), "Unsafe Sentinel notice path")
        body = read_notice("sentinel/cargo-notices/" + name)
        require(digest(body) == row.get("sha256") and isinstance(row.get("owners"), list)
                and row["owners"] and len(row["owners"]) == len(set(row["owners"]))
                and set(row["owners"]) <= set(nodes), "Current Sentinel notice bytes/owners differ")
        seen.add(name); owners.update(row["owners"])
    require(seen and owners == set(nodes) and coverage.get("unionComponents") == len(nodes)
            and coverage.get("unionEdges") == len(edges) and set(coverage.get("unionComponentRefs", [])) == set(nodes),
            "Current Sentinel original notices do not cover selected union")
    union = copy.deepcopy(primary); root_ref = union["metadata"]["component"]["bom-ref"]
    union["components"] = [nodes[ref] for ref in sorted(nodes) if ref != root_ref]
    all_edges = {ref: set() for ref in nodes}
    for ref, child in edges:
        all_edges[ref].add(child)
    union["dependencies"] = [{"ref": ref, "dependsOn": sorted(children)} for ref, children in sorted(all_edges.items())]
    union["metadata"].setdefault("properties", []).append(
        {"name": "foxhole:native:" + delivery_profile + "-coverage-sha256", "value": digest(coverage_raw)})
    return union, digest(coverage_raw)
