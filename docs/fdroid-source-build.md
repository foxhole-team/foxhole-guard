# F-Droid source builds

`scripts/fdroid-source-build.py` separates network preparation from the offline
source build. It requires the pinned Core checkout, Rust, NDK and four Android
ABIs. Guard also builds its pinned Go source before compiling Tor transports.

The compiled-source revision and canonical legal snapshot identify the original
reference material. Their native checksums describe those reference binaries.
Host-specific linker output can differ between Darwin and Linux. A source build
records its own checked output hashes and verified Core source commit; it does
not relabel that output as the reference producer.

The VPN source helper verifies the normal producer manifest, exact feature set,
all four output paths, their hashes and ELF identities. The normal Core build
also runs its page, JNI export and host-path gates. Guard records both normal
Core and Sentinel source outputs, and validates their source/lock identities
when generating legal assets. Canonical notice files remain unchanged reference
attribution; `SOURCE-BUILD.json` identifies the actual source-built native files.

Guard runs the pinned Core/Sentinel source-build scripts before starting the
Gradle JVM. During normal assembly, both native-preparation tasks verify the
source provenance, locks, all eight JNI hashes and ABI identities against those
fresh outputs. This keeps Rust fat LTO and the Kotlin compiler in separate memory
phases without importing donor JNI libraries or omitting native verification.
The source outputs stay in a separate staging directory. Each preparation task
checks and copies them after Gradle cleans its declared output files.
Dependency-prefetch tasks resolve each project's configurations inside that
project's own task so parallel Gradle execution holds the correct project locks.

The external Guard recipe removes only its two prebuilt test DEX fixtures and
copies the pinned Core `Cargo.lock` into the source root before scanning. This
also supplies the actual lock entry for the archived `void` Cargo manifests in
the legal source material. Source/APK scanning remains enabled without ignore
paths, and the normal source tarball is retained.

Reproducibility requires an independent source-built reference from the same
Linux toolchain. Sign that reference with the canonical release certificate,
then run an ordinary F-Droid source build and the official
`fdroidserver.common.verify_apks` comparison. Successful source compilation alone
does not establish APK reproducibility, runtime qualification or publication.

Caller-supplied `CARGO_HOME` and `CARGO_TARGET_DIR` are retained. Otherwise the
helper uses the user's Cargo home and a sibling dependency-cache target folder;
it does not require writable `/out` or `/target` directories on a build server.
