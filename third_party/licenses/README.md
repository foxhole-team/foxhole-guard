# Bundled Android dependency licenses

These are the exact upstream license grants for binary dependencies whose AARs
do not reliably carry user-readable license material into the final APK. The
release license-bundle generator copies them without rewriting their text.

| Component | Pinned version | Upstream file | Vendored SHA-256 |
| --- | --- | --- | --- |
| SQLCipher Android | `4.17.0` | `sqlcipher/sqlcipher-android` tag `v4.17.0`, `LICENSE` | `09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14` |
| libsodium | `1.0.20` (inside lazysodium Android) | `jedisct1/libsodium` tag `1.0.20-RELEASE`, `LICENSE` | `43964d976a6db3fb986af689d05f8ca0e9971878bccae709750dac8fdc4a99cf` |
| lazysodium Android | `5.2.0` | `terl/lazysodium-android` tag `v5.2.0`, `LICENSE.md` | `1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5` |
| JNA license selector | `5.19.1` | `java-native-access/jna` tag `5.19.1`, `LICENSE` | `07c938b23950ab7d47a24ef35f9f5da3a05ae164278dc959ad6994135ed59ff1` |
| JNA Apache 2.0 text | `5.19.1` | same tag, `AL2.0` | `0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594` |
| JNA LGPL 2.1 text | `5.19.1` | same tag, `LGPL2.1` | `eea173a556abac0370461e57e12aab266894ea6be3874c2be05fd87871f75449` |

The lazysodium upstream file has no final newline; the vendored copy adds only
that terminating newline so text tooling and Android asset packaging remain
stable. Its upstream-byte SHA-256 is
`4b89d4518bd135ab4ee154a7bce722246b57a98c3d7efc1a09409898160c2bd1`.

Versions are also pinned in `gradle/libs.versions.toml`; release tests require
the table, hashes and pins to remain in sync.
