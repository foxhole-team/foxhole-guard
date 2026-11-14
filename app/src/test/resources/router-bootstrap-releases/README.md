# Router bootstrap regression data

The two catalog files and the 1.0.6 checksum manifest are exact published bytes from FoxHole OpenWrt releases 1.0.5 and 1.0.6. The 1.0.6 catalog refers to Core 1.0.4 assets; the historical 1.0.5 catalog refers to Core 1.0.2. Their differing component versions are intentional.

`core-1.0.4-auth-relationships.json` is the authentication-only projection of a native-generated selected install budget for the published Core 1.0.4 cohort. `core-1.0.4-index.json` is the native signed-index metadata rendering. These structural fixtures are not signatures or runtime acceptance proof. The tests keep canonical key, package identity, manifest and origin constraints intact. Tampered metadata is derived in memory and is never treated as a valid signed release.

Exact resource SHA-256 values are pinned in `ReleasedRouterBootstrapFixtures`. No deployment, credential, private profile or operational paths are present.
