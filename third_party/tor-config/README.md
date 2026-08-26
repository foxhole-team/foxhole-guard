# Tor integration configuration provenance

FoxHole Guard carries three non-executable integration files from the official
Tor Project Expert Bundle 15.0.9:

- Archive:
  `https://archive.torproject.org/tor-package-archive/torbrowser/15.0.9/tor-expert-bundle-android-aarch64-15.0.9.tar.gz`
- Archive SHA-256:
  `5bdf7d70e3453d13ac5c7b094903b2aab987fbdeb99ea2145093a12119f7c154`
- Upstream members: `data/torrc-defaults`,
  `tor/pluggable_transports/pt_config.json` and
  `tor/pluggable_transports/README.CONJURE.md`.

The corresponding files under `app/src/main/assets/tor/arm64-v8a/` are
byte-for-byte identical to those archive members. The other ABI directories use
the same `torrc-defaults` and `pt_config.json`; the two ARM directories also use
the same `README.CONJURE.md`. `assets.sha256` records the accepted member bytes.
The release licence generator rejects any drift or ABI disagreement.

The application does not package the archive's `libTor.so`, GeoIP databases or
prebuilt pluggable-transport executables. FoxHole Core provides the Tor client,
and `lyrebird` plus `conjure-client` are rebuilt from separately pinned upstream
source. The Conjure README is covered by the Conjure BSD-3-Clause licence in the
user-readable notice bundle.
