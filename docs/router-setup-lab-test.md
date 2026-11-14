# Optional router setup integration test

`app/src/routerLabTest/kotlin` is intentionally outside the normal Android unit-test source set. Normal unit tasks do not discover this test, report it as passed, or require a lab fixture. The explicit init script below adds it only for a dedicated integration run.

The test performs real SSH installation, TLS certificate pinning, initial PIN setup, pairing and authenticated provisioning/settings calls against one disposable stock OpenWrt router. It checks the default auto-update ON, explicit OFF, Settings readback and reentry without policy overwrite. Both scenarios need a fresh guest.

Only Android network selection and fresh-board endpoint reachability are adapted to fixed loopback forwards. SSH, TLS, the installer, API and provisioner remain the production implementations. This test does not establish physical Wi-Fi, Compose UI, WAN tunnel or firmware-upgrade acceptance.

## Requirements

Use an isolated, explicitly owned disposable router fixture and the existing verified toolchain/dependency cache. Never target a production router or saved owner credentials. The private fixture must be a regular nlink1 file0600 inside a directory0700. Its fixture identifier must match the environment variable. It supplies synthetic passwords/PIN, the observed SSH key digest, loopback forwarding ports, the actual guest-observed peer address and the selected initial auto-update choice.

The guest must expose normal SSH and management HTTPS, have functional package-publisher access, and provide genuine peer/ARP evidence for default phone registration. Reset its disposable overlay between the default-ON and explicit-OFF runs. No source scanner, TLS/signature verification or backend policy check may be disabled for the test.

## Run

Supply the private fixture path and identifier through the environment. Do not put fixture contents or a password/PIN on a command line or in source control.

```sh
export FOXHOLE_ROUTER_LAB_FIXTURE=/path/to/private-fixture.json
export FOXHOLE_ROUTER_LAB_FIXTURE_ID=ACTUAL_EPHEMERAL_FIXTURE_ID
./gradlew :app:testDebugUnitTest \
  --tests com.foxhole.guard.router.RouterDefaultSetupLabTest \
  --init-script scripts/router-default-lab.init.gradle \
  --no-daemon --max-workers=1 --offline --dependency-verification=strict
```

The identifier is generated for the disposable fixture; it is not an artifact hash. The test source pins the qualified public package release and checksum manifest. Change those pins only as part of a reviewed test update.

An explicit lab invocation fails when its fixture inputs are absent or invalid. A normal unit invocation omits the init script and does not include this integration class at all. Keep actual runtime evidence separate from ordinary unit-test results and from host-only metadata validator tests.

## Optional locked publisher snapshots

The prepared release currently lacks147 upstream APK mirror files while its immutable signed checksum manifest already binds them. Their original upstream revisions can disappear. An explicit `publisherMirrorDescriptor` in the owned private fixture may therefore route only those147 exact GitHub snapshot URLs to a loopback HTTPS mirror of the qualified original bytes. The dedicated test client trusts only that mirror certificate and verifies its loopback SAN; all other requests keep the normal public client. No authorization/cookie header is forwarded. Package/index, size, checksum and native signature checks remain the production implementations.

This boundary proves installation from exact locked publisher inputs, not availability of missing public GitHub assets. Keep the mirror key outside repositories and all fixture/ops data untracked. Ordinary tests and release APKs do not include this source set. Never report this local artifact transport as a completed public delivery repair.
