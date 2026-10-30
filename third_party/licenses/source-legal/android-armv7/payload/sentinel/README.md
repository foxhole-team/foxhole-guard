# Sentinel YARA native engine

This standalone Cargo workspace builds `libfoxhole_sentinel_yara.so` for an isolated Android scanner. It is separate from the FoxCore VPN workspace and its library. Keep the committed lockfile; MSRV is 1.96. The validation toolchain is Rust 1.97.1 and NDK 29/API 26; dependency changes require fresh affected qualification.

Load only in an ordinary non-exported isolatedProcess service. JNI rejects other Android appIds; application zygote isolated IDs are unsupported. A process owns one compile attempt/session. Native cancel fences publication but cannot forcibly interrupt scan or compilation. The scanner owner must enforce a process watchdog and end its own isolated PID after delivery/acknowledgement; YARA timeout creates a process-lifetime1Hz heartbeat.

Rule source provenance/signature and package identity are host responsibilities. Inputs, syntax, module imports, compilation artifacts, scan ranges, matches and diagnostics are bounded. No third-party compiled blob is deserialized. Complete evaluation covers only the declared input range and rule profile; no malware/safety verdict or dynamic-code coverage follows from zero matches.

YARA-X 1.21.0 / Wasmtime 49.0.4 uses Pulley execution; Cargo features include Cranelift. Required third-party license text and notices are in `third-party/`. The existing FoxCore project license applies to this adapter; upstream dependencies retain their terms.

The isolated Android bridge exposes `nativeIsReadonlyRegularFd(fd)` for API 26 and later. It duplicates the borrowed descriptor, verifies `fstat` regular-file type and `fcntl(F_GETFL)` read-only access, and closes only the owned duplicate. It does not read file contents, change the shared file position, or create a compiled rule session. The Android library still refuses loading outside the standard isolated UID range.

The vendored YARA-X source differs from the published crate in three explicit patches listed in `vendor/PROVENANCE.json`: the Wasmtime security pin, DEX helper availability without unused crypto/RSA, and exact private/global iterator lengths. Registry caches are unchanged. `tests/engine.rs` includes the iterator regression.

`third-party/licenses.json` records the locked dependency notices. The vendored parser replaces the debug-only `ascii_tree` dependency with an original BSD-3-Clause formatter. Its crate checksum and patch are recorded in the `parser` entry of `vendor/PROVENANCE.json`; `tests/parser_debug.rs` verifies the published AST fixtures. The upstream parser license and the formatter notice are retained in `vendor/yara-x-parser/LICENSE` and `third-party/`.
