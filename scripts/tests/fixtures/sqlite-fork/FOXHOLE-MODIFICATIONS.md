# libsqlite3-sys 0.37.0 security backport

Original package: crates.io libsqlite3-sys 0.37.0, SHA-256 `b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1`.

FoxHole backports SQLite upstream commit e5bf119821f25be1d7f10ef0a2edbe175289166e to both bundled amalgamations for CVE-2026-119375 (RUSTSEC-2026-0459). A short WAL frame now zero-initializes the remainder of a larger output page. The Rust API and upstream package version remain unchanged. Original licenses are preserved in vendor/libsqlite3-sys/LICENSE and the source amalgamations.

Only sqlite3/sqlite3.c and sqlcipher/sqlite3.c differ from the published crate. The manifest pins every original and effective file and the patch. Coordinate-only scanners still report version 0.37.0; this source backport must be verified explicitly and does not describe existing native binaries.
