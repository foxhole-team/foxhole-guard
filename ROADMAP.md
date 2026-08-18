# Roadmap

Short list of what is deliberately deferred, and why. Everything here is known and
costed, not forgotten.

## Next version

**Browser-shaped TLS on the generic path.** REALITY connections already send a
browser-faithful ClientHello; plain TLS (VLESS+TLS, Trojan, ShadowTLS) still sends
rustls' own shape — measured at 10 cipher suites / 11 extensions against Chromium's
15 / 16, which is distinguishable by the unhashed part of JA4. Not reachable by
configuration: rustls does not implement the RSA and CBC suites Chrome carries and
exposes no API for GREASE or extension order. Candidate fix is **BoringSSL** as a
replacement for rustls — not alongside it. Cost: C in the tunnel data path, four
ABIs, and a harder reproducible F-Droid build. Trigger to act: the share of traffic
that uses the generic path, not the fingerprint itself.

**QUIC transport shape.** hysteria2 and TUIC carry their hello inside QUIC CRYPTO
frames. The hello itself now matches Chrome on both halves a detector reads first;
what is left is the Initial packet and transport parameters. Deliberately not chased
further yet: no published fingerprint format covers QUIC transport, and the one change
that looked free — Chrome's datagram size — measured as a *new* unique signature
rather than a match, because the second packet is built elsewhere.

**Fingerprint tables from the feed at every connect.** Tables installed at service
start are picked up on the next start; a document that lands mid-session waits for one.

## Later

- `360` / `android` profiles: impossible with REALITY for anyone — those hellos are
  TLS 1.2 and carry no `key_share`, which REALITY needs to derive its key.
- AmneziaWG `HeaderProtectionKey`: refused by name; there is no degraded mode.
- I2P SOCKS listener: fronted by our own authenticated gate, but the router's own
  loopback port stays reachable — stock i2pd cannot authenticate.
- Reproducible F-Droid build: merge the buildable recipe with the reproducible one
  (`SOURCE_DATE_EPOCH`, `Binaries:`, `AllowedAPKSigningKeys:`) so F-Droid publishes
  the project's own signature.

## Known caveats of what shipped

- `randomized` draws a fresh hello per connection, so its JA4 is never twice the same.
  That defeats exact-match blocklists and is itself a signal no browser produces — it
  is an opt-in, not the default, and the default stays `chrome_151`.
- Chromium 151's JA4 was computed from a first-party capture; the Firefox 153 number
  was confirmed against a live third-party detector, the Chromium one was not.
- Tables were captured on desktop macOS only. A host without AES hardware would put
  ChaCha20 first in Chrome's ECH GREASE, and a warm Firefox adds `pre_shared_key`.
