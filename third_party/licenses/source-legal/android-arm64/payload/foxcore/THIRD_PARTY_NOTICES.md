# Third-party notices

FoxHole Core depends on or adapts material from the projects below. Each entry
says what is used, whether it was changed, and under which licence it arrived.

## smoltcp

- Project: `smoltcp-rs/smoltcp`
- Version: exactly `0.14.0`
- Copyright (C) smoltcp contributors
- Licence: 0BSD (SPDX `0BSD`)
- Linked by: `crates/foxcore-tun/`

No smoltcp source is copied into FoxHole Core. A Fox-owned bounded Tokio actor
drives its synchronous `Device`, `Interface` and TCP sockets, while Fox-owned
code retains transparent five-tuple admission, UDP demultiplexing, ICMP policy,
DNS interception, memory budgets and shutdown ownership. The dependency is
exact-pinned because packets from an untrusted TUN cross this boundary.

The transitive `managed` 0.8.0 storage abstraction is also 0BSD. Both licences
are permissive and GPL-3.0-compatible; the crate-scoped policy decisions live
in `deny.toml` and `deny-all-features.toml`.

## uTLS

- Project: `refraction-networking/utls`
- Copyright (c) 2009 The Go Authors; `u_parrots.go` additionally
  "Copyright 2017 Google Inc. All rights reserved."
- Licence: BSD-3-Clause (SPDX `BSD-3-Clause`), confirmed against the repository
  `LICENSE` file, which carries the Go Authors' BSD text verbatim
- Used at: `crates/proto-reality/src/reality/hello_profile.rs` and the seven
  uTLS-derived vectors in `fingerprints/` (`chrome_133`, `chrome_131`,
  `edge_85`, `safari_26_3`, `ios_14`, `qq_11_1`, `firefox_148`). The
  `chrome_151` and `firefox_153` vectors take nothing from uTLS: uTLS has no
  table for either build, and both are transcribed from a first-party capture
  of the shipping browser.

No uTLS code is embedded, linked or executed: FoxHole Core is Rust and does not
build Go. What is taken is the *data* in uTLS' maintained parrot tables — `HelloChrome_133`,
`HelloChrome_131`, `HelloEdge_85`, `HelloSafari_26_3`, `HelloIOS_14` and
`HelloQQ_11_1` in `u_parrots.go` — transcribed into FoxHole Core's own table
types: the cipher list and its order, the extension set and its order, GREASE
placement, supported groups, signature algorithms, key shares, ALPN and the
certificate-compression algorithm. Which uTLS symbol each `fp=` name resolves
to is taken from sing-box's `common/tls/utls_client.go` and uTLS'
`Hello*_Auto` aliases, so the mapping matches the deployed ecosystem rather
than being chosen here.

`scripts/fingerprint-from-utls.py` re-reads those tables from uTLS source and
diffs them against the committed vectors, and it resolves every code point from
uTLS' own `const` blocks rather than from a transcribed copy.

uTLS is the reference rather than a reading of Chrome because it is the code
Xray and sing-box actually run, so matching uTLS is matching the deployed
population rather than one interpretation of a capture. Two rules were taken
from uTLS' own upstream instead, and are cited in place: BoringSSL's
`tls_record_version` for the initial record's `legacy_record_version`, and
BoringSSL's `setup_ech_grease` for the GREASE ECH HPKE suite.

The BSD-3-Clause conditions are satisfied by the copyright notice above; the
third condition (no use of the names of Google Inc. or contributors to endorse
or promote) is observed — this notice is attribution of provenance, not
endorsement.

## Shoes

FoxHole Core contains independently adapted protocol algorithms from the following
MIT-licensed project. The adapted code has been split behind FoxHole Core's own
protocol and transport interfaces; no upstream TUN, routing, configuration, or
application code is embedded.


- Project: `cfal/shoes`
- Reference commit: `386b11532424b8665ee3e46340c6236fb3c47595`
- Follow-up hardening references:
  `caf9a29cec3e9cc72bf19bbfb0986e17f8221ab2` (bounded handshake plaintext) and
  `47a222e90c15fb1bb4a75923e63b37c17b04302d` (combined outgoing buffer budget)
- Copyright (c) 2021-2023 Alex Lau <github@alau.ca>
- Adapted areas: modern VMess AEAD KDF/framing and compatibility test vectors;
  client-only REALITY TLS 1.3 state machine and crypto test vectors. FoxHole Core
  removes upstream server/TUN/routing code, secret-bearing debug traces and the
  crawler fallback; it additionally verifies the TLS server Finished message
  and carries the two bounded-buffer hardening invariants listed above.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## HTTP/3 and Brotli dependency notices

The locked graph additionally uses the unmodified registry crates `h3` 0.0.8
and `h3-quinn` 0.0.10 from `hyperium/h3` under MIT, and
`brotli-decompressor` 5.0.3, `alloc-no-stdlib` 2.0.4 and `alloc-stdlib` 0.2.4
from Dropbox's Rust Brotli/allocation projects. The latter crates declare
`BSD-3-Clause/MIT`, `BSD-3-Clause` and `BSD-3-Clause`, respectively.
The BSD-3-Clause notice below is retained for all three.

The original `alloc-stdlib` crate omits its licence file. Its upstream licence
is bound to repository `dropbox/rust-alloc-no-stdlib` commit
`ae42d22078b98549e987d2f03d12df7b984fde47`, path `LICENSE`, SHA-256
`c0c56f26d9c051cac4d200c34c84e7ae9aaa853e01a982a1df08b09931e518ae`.
This is the same previously qualified upstream licence used by the current
consumer notices, rather than a licence inferred from another package.

### h3 and h3-quinn — original MIT notice

Copyright (c) 2020 h3 authors

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.

### Dropbox allocation and Brotli crates — original BSD notice

Copyright (c) 2016 Dropbox, Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Modified rustls source

The path dependency `vendor/rustls` 0.23.46 is FoxHole's modified fork, not an
unmodified crates.io copy. Its frozen upstream inventory, patch, modification
notice and original Apache-2.0/ISC/MIT licence texts are retained under
`config/source-forks/rustls-0.23.46*`, `patches/rustls-0.23.46-chrome151.patch`
and `vendor/rustls/LICENSE-*`. Its source-bound identity and provenance are
validated by `scripts/rustls-fork-origin.py` and included by `scripts/sbom.sh`.
