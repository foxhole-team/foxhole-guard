# Opt-in router BASE update integration test

These tests use the application's production coordinator, TLS-pinned panel API,
HTTP provisioner and device release relay against an owned disposable router.
They are outside ordinary unit-test source sets. They do not run, skip, or claim
laboratory acceptance during a normal Gradle invocation.

Use the existing explicit init script with `FOXHOLE_ROUTER_LAB_SUITE`:

- `setup` (default): the fresh-install/default-policy suite.
- `update-readback`: authenticate an already configured 1.0.4-r1 router, open the
  production management coordinator through its existing paired read-only entry,
  and compare repeated profile/settings,
  installed module and pairing readback without invoking package update.
- `update-apply`: prepare a real update plan and dispatch exactly one BASE APPLY;
  recover by read-only polling and authenticate again with the original pairing.

Example from this repository, with a private fixture already prepared:

```sh
FOXHOLE_ROUTER_LAB_FIXTURE="$fixture_file" \
FOXHOLE_ROUTER_LAB_FIXTURE_ID="$fixture_id" \
FOXHOLE_ROUTER_LAB_SUITE=update-readback \
./gradlew --init-script scripts/router-default-lab.init.gradle \
  :app:testDebugUnitTest --no-daemon --max-workers=1
```

For APPLY, change only the suite to `update-apply`. Use a separately restored old
fixture for every app and every delivery mode. A successful update consumes the
old-version precondition; never relabel its result as another run.

## Fixture contract

Keep the JSON outside source/build archives: regular non-symlink file, one hard
link, mode 0600 inside a 0700 directory, at most 4096 bytes. Reuse the setup
fixture's `ownedEphemeral`, `fixtureId`, `logicalAddress`, `guestObservedPeer`,
`sshForwardPort`, `tlsForwardPort`, `sshKeySha256`, `initialAutoUpdate`, and
`newPanelPin` fields. The unused setup PIN must still be a synthetic six-digit
value. `initialAutoUpdate` must be false for this already configured fixture.
The fixture ID must match the separate 32-hex environment variable. The logical
address is `10.77.0.2`; actual connections map only to distinct loopback high ports.

Additional required fields:

| Field | Required value / meaning |
| --- | --- |
| `baselineVersion` | `1.0.4-r1`, verified on the actual installed signed BASE |
| `baselineBaseSha256` | `f5ad78213c3f019ea0de2579a8effc1cc625d559ebc6b55c3739bc708df5d71d` |
| `targetVersion` | `1.0.6-r1` |
| `targetBaseSha256` | `9d3814c09ad38c6f88ef7d7eee52fb9c30f5a28e507f7606df5585f4744062c0` |
| `panelCertificateSha256` | Independently observed leaf certificate SHA-256 |
| `existingPairingId` | Pre-existing synthetic `pair-` plus 32 lowercase hex |
| `existingPairingCredentialHex` | That fixture pairing's 32-byte credential as 64 lowercase hex; never log it |
| `deployment` | Independently verified `RAM_BASE` or `FLASH`; APPLY currently admits only `RAM_BASE` |
| `expectedDelivery` | `DIRECT` or `DEVICE_RELAY` |
| `allowPackageApply` | Must be boolean true for the mutating suite |

The router must have an inactive synthetic profile, its real installed protocol
module, an existing active pairing and auto-update explicitly disabled. No VPN
connection is started. Do not use user profiles, credentials or physical routers.

Before running, qualify the old BASE from its real release URL and signature:
`https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.4/foxhole-openwrt-client-1.0.4-r1.apk`
(2254723 bytes, baseline SHA above). Its SHA256SUMS SHA-256 is
`f0800292d4ed01b400aa2b79922cdc95a1f77f00b6d5e20eac1603860b2ca487`.
Qualify the target at the equivalent v1.0.6 URL (2283134 bytes, target SHA above),
with SHA256SUMS SHA-256
`b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d`.
JSON declarations alone do not prove which package was installed.

The 1.0.4 FLASH updater validates the new embedded engine catalog using its old
OpenWrt-only origin validator. The signed 1.0.6 BASE embeds a Core-origin catalog;
FLASH compatibility needs its separate qualification. This APPLY suite therefore
admits only the RAM BASE transaction; readback can use either deployment. The
old RAM path has device relay support and validates the signed BASE payload
without that FLASH catalog admission step.

`DIRECT` requires actual router access to the public release service.
`DEVICE_RELAY` requires a real guest-only publisher egress failure that produces
`deviceDownloadRequired` in the router's authentic response; the application host
must still reach the real publisher. Do not rewrite responses, flags, versions,
metadata, signatures, or package bytes. The production source uses current public
release discovery. If it no longer offers 1.0.6, this fixed acceptance case fails;
do not fake latest metadata to make it pass.

## Assertions and limits

The mutating suite requires an actual 1.0.4 → 1.0.6 plan, one authenticated APPLY,
a successful matching terminal job, and readback preserving router identity,
certificate, existing pairing credential, auto-update policy, installed module
identity and full synthetic profile contents. The client must not write profile,
country/settings or update-policy data during this transaction. Relay coverage is
claimed only if production state reports the actual BASE relay path.

The only platform adaptation is Android network selection/socket addressing to
loopback-only forwards. SSH/TLS/HTTP and the updater/relay remain production code.
This is not Compose interaction, physical Wi-Fi, firmware upgrade, bulk package
upgrade, VPN traffic, power loss, or ARM/RAM-stage acceptance. Record exact app
source, package hashes, guest identity, delivery mode and real test result. A
compiled harness or an unexecuted fixture is not a laboratory PASS.
