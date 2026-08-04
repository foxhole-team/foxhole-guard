# Sentinel: which public detectors to ship, and where they come from

The question this answers: does the public beta load public detector feeds, which ones, and does
the project mirror them itself.

**Answer: one feed, two lanes, both through our own signed mirror.** The reasoning, the feeds that
were considered and rejected, and what is left to build are below.

## 1. What already exists

The update channel is built and dormant. It is not a design decision that is still open — it is a
finished pipeline with three blanks in it.

| Piece | State | Where |
| --- | --- | --- |
| Signed-update client | complete | `app/.../runtime/ThreatIntelUpdateClient.kt` |
| Verification chain | complete: HTTPS host → ECDSA-P256 signature → manifest schema → size → SHA-256 → parse | same file |
| Store, worker, repository | complete | `FileThreatIntelStore.kt`, `ThreatIntelUpdateWorker.kt`, `ThreatIntelUpdateRepository.kt` |
| Wire format | complete: `ThreatIntelDocument { schema, packages[], certs[], certsSha1[] }` (schema 2 added the SHA-1 band, because every public dataset publishes SHA-1 and SHA-1 to SHA-256 is a second preimage, not a conversion) | `core/model/.../ThreatIntelDocument.kt` |
| Scoring | complete: a package name or a signing-certificate SHA-256/SHA-1 match raises the app to a known threat | `core/sentinel/.../InstalledAppRiskScorer.kt` |
| **Manifest URL** | **empty string** | `ThreatIntelUpdateClient.kt` |
| **Pinned public key** | **documented placeholder** (`...QgAEPLACEHOLDER...`) | same file |
| Bundled seed | populated: 612 packages + 471 SHA-1 certificates from `AssoEchap/stalkerware-indicators` (CC-BY-4.0), built by `scripts/build-sentinel-threat-intel.py`, provenance in `threat-intel.source.json` | `app/src/main/assets/sentinel/threat-intel.json` |

The update *channel* is still off — the manifest URL and the pinned key are blank, and
`FoxholeApplication` enables the worker only when the URL is non-blank. What changed is that the
bundled seed is no longer empty, so the detector matches something in a build that never reaches
the network; hosting the feed now only buys refreshes between releases.

The Rust core has the same data model from the other side: `crates/foxcore-sentinel/src/feed.rs`
parses a flat text feed of `package` and `cert` rows, and its module documentation names the feed it
was written for.

## 2. The feed: AssoEchap/stalkerware-indicators

<https://github.com/AssoEchap/stalkerware-indicators> — maintained by Julien Voisin and Tek for the
Echap non-profit. 174 applications (147 stalkerware, 27 watchware). Licence: **CC-BY**.

It is the right feed for this product for reasons that are specific rather than general:

* **It publishes exactly what Sentinel scores.** `ioc.yaml` carries Android package names *and*
  signing-certificate SHA-256 hashes. Nothing else has to be derived, hashed or inferred on device.
* **The certificates are the valuable half.** Stalkerware renames itself freely — the package name
  is a label its author picks — but changing the signing key costs the author the ability to update
  the copies already installed. `ThreatFeed::cert_count` is reported separately in the core for this
  reason.
* **It is the threat this product is actually positioned against.** The app is a privacy tool for
  people who may be monitored. A curated list of consumer monitoring apps is on-topic in a way that
  a generic malware corpus is not.
* **It is small.** Low hundreds of rows, so the whole feed can be bundled in the APK as a seed and
  still be verified byte for byte.
* **The licence is compatible and the obligation is already enforced in code.** CC-BY requires
  attribution; `feed.rs` refuses to parse a feed that does not name its source, so the obligation
  travels with the data rather than with a README somebody may forget.

### Two lanes, not one

The same upstream repository serves both halves of what Sentinel claims to do:

1. **App-identity lane** — `ioc.yaml` → packages + certificate hashes → the existing signed
   threat-intel channel. This is what turns "this app requests accessibility and came from an
   unknown installer" into "this app is a known monitoring product".
2. **Network lane** — `generated/hosts` / `generated/quad9_blocklist.txt` → C2 domains → a second
   rule set in the existing DNS filter pipeline. This costs nothing new to build: the DNS channel
   already exists and already verifies signatures, and it works with no VPN profile at all.

The network lane is the cheaper of the two and works even when the app-identity lane finds nothing,
because it blocks the exfiltration rather than identifying the app doing it.

## 3. Feeds considered and rejected

| Feed | Licence | Verdict |
| --- | --- | --- |
| Hypatia / DivestOS signature databases | AGPL-3.0 | **No.** DivestOS is discontinued, the databases no longer receive updates and the repository is archived. It also requires hashing files on device (~120 MB of memory in Hypatia's own figures) and would turn Sentinel into an antivirus, which the README explicitly says it is not. |
| ClamAV | GPL | **No.** Hundreds of megabytes with daily deltas. Wrong shape for a phone, wrong shape for an F-Droid build. |
| MalwareBazaar (abuse.ch) | own terms | **No.** Needs an API key, the Android subset is small, and per-key terms do not sit well with an offline redistributed bundle. |
| Exodus Privacy ETIP trackers | AGPL-3.0 | **Not for the beta.** Technically the most interesting runner-up: `network_signature` would drop straight into the DNS lane, and `code_signature` would support a real "this app embeds N trackers" screen. Three reasons to defer: detecting code signatures needs dex inspection on device, which is a different class of work; AGPL on redistributed data needs a decision we have not made; and a tracker is not a threat, so it belongs in a separate lane with separate wording rather than raising an app's risk verdict. |

## 4. Mirroring: required, not optional

Four independent reasons, any one of which is sufficient:

1. **Reproducibility.** F-Droid and our own reproducible-build gate pin inputs. An app that fetches
   whatever a third-party endpoint serves today cannot make that promise.
2. **We are the signer.** The channel verifies against a key pinned in the APK. Echap does not hold
   the private half and should not — so somebody has to re-sign, and that somebody is us.
3. **The format has to change anyway.** Upstream is YAML; the core takes a dependency-free flat text
   format, and `feed.rs` explains why it will not grow a YAML parser (anchors, aliases and merge
   keys, to read a list of strings, in the crate with the shortest supply chain in the core). The
   conversion happens outside the core exactly once — which is to say, in our CI.
4. **Attribution has to be carried.** CC-BY obliges it, and the artifact is where it has to live.

## 5. What to build

The pattern already exists — `foxhole-dns` is the same shape — so this is a copy, not a design.

- [ ] Repository `foxhole-threat-intel`, published via GitHub Pages, alongside `foxhole-dns` and
      `foxhole-bridges`.
- [ ] CI job: fetch `ioc.yaml` at a pinned upstream commit → emit both consumers
      (`ThreatIntelDocument` JSON for Android, the flat `feed.rs` format for the core) → record the
      upstream commit and input hash → compute SHA-256 → sign the manifest.
- [ ] A signing key **separate** from the DNS key; replace the placeholder in
      `ThreatIntelUpdateClient.kt` and set the manifest URL.
- [ ] Replace the empty bundled seed with the snapshot taken at build time. An empty seed means the
      first launch without network detects nothing, which is the worst moment to detect nothing.
- [ ] Attribution line on the Sentinel screen, sourced from the feed's own `source` row.
- [ ] Second DNS rule set for the stalkerware C2 domains, published through the existing DNS
      channel.

## 6. Open question this raised

`crates/foxcore-sentinel` (8 619 lines) is a port of the Kotlin Sentinel and is reachable only from
`crates/foxcore-journal` (9 622 lines), whose JNI class `FoxholeNativeJournal` has **no Java
counterpart in this repository** — the app uses its own Kotlin `GuardJournal` and its own Kotlin
scorer. Both crates are compiled into every shipped `.so`.

That has to be decided before the public beta rather than after: either the Rust side becomes the
implementation and the Kotlin one goes, or the Rust side comes out of the Android build. Shipping
two divergent implementations of the same scoring rules, one of which nothing calls, is the worst of
the three options — `crates/foxcore-sentinel/PORT.md` exists specifically to keep them in agreement,
and that cost is being paid for code that does not run.
