FoxHole modification of rustls 0.23.46

Original crates.io archive SHA-256: 48e13bd8c0e9365c43cfa5c9e8f9ad49d3c8444926c9aac819e0e4dc503c8fdf

FoxHole adds the explicit chrome-hello source feature for the chrome_151 ClientHello option. The upstream authentication and TLS state-machine code remains subject to separate regression and native qualification. This record identifies modified source; it does not describe an unmodified publisher crate.

Modified files:
- Cargo.toml
- src/client/builder.rs
- src/client/chrome_hello.rs
- src/client/client_conn.rs
- src/client/common.rs
- src/client/hs.rs
- src/client/tls12.rs
- src/client/tls13.rs
- src/lib.rs
- src/msgs/handshake.rs

The original Apache-2.0, ISC and MIT license texts and attribution are retained.
