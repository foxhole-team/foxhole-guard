#!/usr/bin/env python3
"""Gate exact advisory identities without exempting dependencies by their file origin."""
import argparse
import datetime
import json
import pathlib
import sys


def require_object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def require_text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    return value


def read_exceptions(policy):
    exceptions = require_object(policy, "OSV policy").get("exceptions")
    if not isinstance(exceptions, list):
        raise ValueError("OSV exceptions must be an array")
    seen = set()
    for entry in exceptions:
        require_object(entry, "OSV exception")
        for key in ("ecosystem", "package", "version", "advisory", "reason", "evidence", "expires"):
            require_text(entry.get(key), f"OSV exception {key}")
        datetime.date.fromisoformat(entry["expires"])
        identity = tuple(entry[key] for key in ("ecosystem", "package", "version", "advisory"))
        if identity in seen:
            raise ValueError("Duplicate OSV exception identity")
        seen.add(identity)
    return exceptions


def findings(report):
    results = require_object(report, "OSV report").get("results")
    if not isinstance(results, list):
        raise ValueError("OSV report must contain a results array")
    by_package = {}
    for result in results:
        require_object(result, "OSV result")
        packages = result.get("packages")
        if not isinstance(packages, list):
            raise ValueError("OSV result must contain a packages array")
        source_data = require_object(result.get("source", {}), "OSV source")
        source = require_text(source_data.get("path", "unknown"), "OSV source path")
        for item in packages:
            require_object(item, "OSV package result")
            package = require_object(item.get("package"), "OSV package")
            identity = tuple(require_text(package.get(key), f"OSV package {key}")
                             for key in ("ecosystem", "name", "version"))
            vulnerabilities = item.get("vulnerabilities", [])
            if not isinstance(vulnerabilities, list):
                raise ValueError("OSV vulnerabilities must be an array")
            groups = by_package.setdefault(identity, [])
            for vulnerability in vulnerabilities:
                require_object(vulnerability, "OSV vulnerability")
                advisory = require_text(vulnerability.get("id"), "OSV advisory ID")
                aliases = vulnerability.get("aliases", [])
                if not isinstance(aliases, list):
                    raise ValueError("OSV advisory aliases must be an array")
                ids = {advisory, *(require_text(alias, "OSV advisory alias") for alias in aliases)}
                sources = {source}
                merged = []
                for existing in groups:
                    if ids & existing[0]:
                        ids |= existing[0]
                        sources |= existing[1]
                    else:
                        merged.append(existing)
                # The aliases of a later record can join two earlier groups.
                changed = True
                while changed:
                    changed = False
                    for existing in list(merged):
                        if ids & existing[0]:
                            ids |= existing[0]
                            sources |= existing[1]
                            merged.remove(existing)
                            changed = True
                groups[:] = [*merged, (ids, sources)]
    for identity, groups in sorted(by_package.items()):
        for ids, sources in sorted(groups, key=lambda group: sorted(group[0])):
            yield identity, ids, sources


def classify(report, policy, today):
    exceptions = read_exceptions(policy)
    blocked, accepted = [], []
    for (ecosystem, name, version), ids, sources in findings(report):
        matching = next((entry for entry in exceptions if
                         (entry["ecosystem"], entry["package"], entry["version"]) == (ecosystem, name, version)
                         and entry["advisory"] in ids
                         and datetime.date.fromisoformat(entry["expires"]) >= today), None)
        finding = f"{'; '.join(sorted(sources))}: {name}@{version} [{','.join(sorted(ids))}]"
        (accepted if matching else blocked).append(finding)
    return blocked, accepted


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("policy", type=pathlib.Path, nargs="?", default=pathlib.Path("config/osv-exceptions.json"))
    parser.add_argument("--scanner-status", type=int, choices=(0, 1))
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    policy = json.loads(args.policy.read_text())
    blocked, accepted = classify(report, policy, datetime.datetime.now(datetime.timezone.utc).date())
    if args.scanner_status == 1 and not (blocked or accepted):
        raise ValueError("Scanner reported vulnerabilities but its report contains none")
    for label, items in (("Documented, current exception", accepted), ("Blocked dependency finding", blocked)):
        for item in items:
            print(f"{label}: {item}")
    print(f"OSV: {len(blocked)} blocking, {len(accepted)} documented exception(s), aliases deduplicated")
    return bool(blocked)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, TypeError, OSError) as error:
        sys.exit(f"Invalid dependency scan: {error}")
