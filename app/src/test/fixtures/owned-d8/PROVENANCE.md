# Owned DEX inspection fixture

`OwnedBenign.java` is task-authored GPL-3.0-or-later test code. It only formats a string,
updates an integer, handles null and creates a lambda. Tests inspect these bytes; they
never execute the Java or DEX. No third-party APK, malware sample, signer material,
permission grant or network access is involved.

Generate with the existing JBR and Android SDK build-tools36.0.0:

```sh
javac --release 8 -d /tmp/owned-d8 app/src/test/fixtures/owned-d8/OwnedBenign.java
java -cp "$ANDROID_HOME/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$ANDROID_HOME/platforms/android-36.1/android.jar" \
  --output /tmp/owned-d8 /tmp/owned-d8/owned/OwnedBenign.class
```

Source SHA-256: `ddeb2f1b626531115912b20153e1452ea5ad4bc75a797d75c5dcc74a5750cef8`.
D8 JAR SHA-256: `4097ff9c46c185c6e7214da7fe9b1befb5adeea5cc9ca349270e0249904f9240`.
The resulting `classes.dex` SHA-256 is
`ec6722b6debf384c79bbfb88b54e8629f83827c1771e06e9a779bc6dbf615c85`;
identical copies live in `app/src/test/resources/sentinel-owned/` and
`app/src/androidTest/assets/owned-scanner/`.
Compiler output does not prove ART compatibility.
