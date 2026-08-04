# F-Droid inclusion: native source build

F-Droid rejects unexplained prebuilt executable blobs. This document records every native payload
and the remaining inclusion blocker.

## FoxCore

The VPN/Tor runtime is the Rust workspace pinned by `config/foxcore-revision.txt`. CI checks out
that exact revision, installs the pinned Rust toolchain, Android NDK r29 and `cargo-ndk`, then runs
the workspace tests and `scripts/android-build.sh`.

The build produces `libfoxhole_native.so` from source for every shipped ABI. Gradle copies those
libraries into the APK/AAB and verifies the ABI inventory and ELF dependencies. No prebuilt
FoxCore library is committed to this repository.

Arti is compiled as part of FoxCore's Rust dependency graph; there is no standalone Tor executable
in the application package.

## Pluggable transports

The optional `lyrebird` and `conjure-client` transport executables are still committed under
`app/src/main/assets/tor/<abi>/tor/pluggable_transports/`. A normal Android release relocates them
to private native-library storage and verifies their inventory, but an F-Droid recipe must compile
them from their pinned upstream source instead.

That source-build step is the current F-Droid blocker:

1. pin the exact upstream revisions;
2. cross-compile each transport with the F-Droid NDK/Go toolchain;
3. install the outputs in the existing ABI layout during the recipe build;
4. remove the committed executable copies from the F-Droid source tarball;
5. run the same native-inventory and ELF gates as the regular release.

## i2pd

`libi2pd.so` is **committed** to this repository under `app/src/main/jniLibs/<abi>/`, so an
F-Droid recipe would ship a prebuilt binary blob unless the recipe rebuilds it. This is a second
inclusion blocker and it was previously missing from this document entirely.

The build itself is already reproducible from pinned source, which is what makes the blocker
mechanical rather than open-ended:

- source: `third_party/i2pd`, pinned in `third_party/i2pd.version` to i2pd 2.60.0, commit
  `f618e417dbd0b7c5956af8f0d5a6b0ee78caf35e`;
- builder: `scripts/build-i2pd.sh`, which cross-compiles OpenSSL and Boost alongside it and needs
  `ANDROID_NDK_HOME` (NDK r28) plus `I2PD_ABIS`;
- the Gradle task `prepareBundledI2pd` already calls that script whenever the `.so` is absent, so
  deleting the committed copies is enough to force a source build locally.

What the recipe has to do:

1. delete `app/src/main/jniLibs/*/libi2pd.so` from the F-Droid source tarball;
2. run `scripts/build-i2pd.sh` with the F-Droid NDK for every shipped ABI;
3. keep the reseed and family certificates the script copies into assets;
4. run the same native-inventory and ELF gates as the regular release
   (`verifyReleaseContainsNativeRuntime`, `verifyPublicReleaseNativeInventory`).

## Other native libraries

SQLCipher and AndroidX native libraries come from source-available Maven artifacts. Their versions
are locked and covered by Gradle dependency verification.

## Permissions and anti-features

- `QUERY_ALL_PACKAGES` supports app routing, firewall rules, DNS exceptions and local app traffic.
- The app has no proprietary SDK, advertising, analytics or developer telemetry.
- VPN servers are user supplied. Optional IP-information and signed DNS-filter endpoints are
  replaceable and are not required for the app's local protection features.
