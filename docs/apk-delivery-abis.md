# APK delivery ABIs

`foxhole.abis` selects the native producer and legal delivery profile. It defaults to `arm64-v8a,armeabi-v7a,x86,x86_64`.

`foxhole.deliveryAbis` optionally selects a nonempty, unique subset of that profile for APK packaging. Omitting it preserves the complete native profile. Native preparation, producer receipts and the legal asset union continue to use `foxhole.abis`.

For one ARM64 APK, keep the four-ABI native profile and pass:

```text
-Pfoxhole.abis=arm64-v8a,armeabi-v7a,x86,x86_64
-Pfoxhole.deliveryAbis=arm64-v8a
-Pfoxhole.splitApks=false
```

Native verification checks every ABI in the producer profile. AGP merged libraries must retain exactly the complete native ABI profile. The final APK set must contain exactly the delivery ABIs, and each APK must retain the complete merged library inventory for its delivered ABIs. Existing provenance, native byte and ELF checks remain required. ABI splits use the delivery subset; a universal APK is generated only when the complete native profile is delivered.

The native verifier accepts the same distinction through `--abis` and optional `--delivery-abis`. APK subset verification also requires `--merged` to bind the complete library inventory.
