# FoxHole Guard code map

This repository contains the Android app and the typed Android integration layer for FoxCore. The
Rust workspace is a sibling checkout selected by `FOXCORE_SOURCE_ROOT` or
`config/foxcore-revision.txt`.

## Modules

```text
core:model                         shared models, settings and telemetry contracts
core:network -> core:model         HTTPS, DNS and host-resolution helpers
core:importer -> model,network     subscriptions, share URIs and normalized raw configs
core:profile -> core:model         profile construction and multi-protocol selection
core:sentinel -> core:model        pure on-device security analysis
core:runtime -> model,network      FoxCore translator, JNI facade and runtime owner
app -> all core modules            Android services, persistence and Compose UI
macrobenchmark                     startup and UI performance benchmarks
```

The graph is acyclic. `app` is the only Android application. `core:runtime` is the only module that
may call the native runtime.

## Runtime path

```text
Compose UI / HomeViewModel
        |
FoxholeConnectionController
        |
FoxholeVpnService
        |
FoxCoreConfigTranslator -- strict typed EngineConfig
        |
FoxCoreRuntime -- single process owner and lifecycle mutex
        |
FoxholeNativeEngine -- narrow JNI ABI
        |
libfoxhole_native.so -- Rust FoxCore supervisor, TUN, DNS, routing and protocols
```

`FoxCoreRuntime` is the sole owner of a native generation. It adopts the Android TUN descriptor,
applies generation-checked reloads, forwards network changes, drains typed audit events and closes
the generation before the service releases its resources. Unknown or unrepresentable config fields
are rejected; they are never silently ignored.

## Main Android areas

| Area | Responsibility |
|---|---|
| `app/.../runtime` | VPN service lifecycle, foreground state, validation and recovery |
| `app/.../traffic` | typed FoxCore traffic and DNS statistics bridge |
| `app/.../core/data` | encrypted profiles, schema migrations and secret storage |
| `app/.../core/settings` | encrypted settings and fail-closed normalization |
| `app/.../ui/cli` | public terminal-style Compose UI |
| `core/runtime` | normalized-config translator, JNI boundary and child-process reaping |
| `core/importer` | URI/subscription parsing into the normalized app config |

## Release gates

- Rust: `cargo fmt --all -- --check`, workspace tests and clippy with warnings denied.
- Native: NDK r29 builds for every shipped ABI, ABI contract and ELF dependency checks.
- Android: unit tests, lint, detekt, connected tests and release preflight.
- Artifact: only allowlisted native libraries, no executable payloads in assets, SBOM, mapping and
  native symbols collected for public release.
- Device: a tunnel is accepted only after traffic succeeds through the VPN-bound network.
