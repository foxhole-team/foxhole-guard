# Foxhole smart config sample

`foxhole-smart-config.sample.txt` is an anonymized copy of the shape the server issues: a flat
list of share URIs, one per line, each ending in a `#name` fragment. Every value here is a
placeholder — hosts resolve to `*.example.com`, keys and passwords are dummy `sample-…` strings.

What it demonstrates:

- One share URI per line becomes one profile (the fragment after `#` is its display name).
- The whole supported protocol matrix in one file: VLESS (REALITY + xUDP packet encoding),
  Hysteria2, VMess, Trojan, NaiveProxy (`naive+https://`), Shadowsocks (Outline base64,
  Shadowsocks-2022 `2022-blake3-aes-256-gcm`, ChaCha20), and WireGuard.
- The `tg://` (MTProto) line is intentionally included to show it is ignored on import —
  FoxCore has no MTProto outbound, so anything it cannot host is dropped, not silently faked.
- `Start` uses the selected profile; auto-connect probes the alternatives live and remembers the
  best working one for the current network.

Unsupported share schemes are reported as `IGNORED_UNSUPPORTED` on import rather than accepted.
