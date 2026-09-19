# Bundled UI fonts

All bundled faces are unmodified upstream binaries. Tiny5 provides the same
Latin and Cyrillic pixel face for headings, top bars, controls and compact dock
labels. JetBrains Mono provides every body, small, technical and non-heading
display role.

## Tiny5

- Face: Tiny5 Regular
- Upstream: https://github.com/Gissio/font_Tiny5
- Pinned commit: `cd350d50285f80abb885160e5054353d02397129`
- Selected font SHA-256: `756261726e160783bfa66723951f12e0e7ca53fa1dfcfbeced760e41093f9702`
- Copyright: Copyright 2022-2024 The Tiny5 Project Authors.
- License: SIL Open Font License 1.1; upstream `OFL.txt` is stored as
  `Tiny5-OFL.txt` (SHA-256
  `6fe7d64407c69d187748206265977654747d3e2fe9e38e45a62cd03ec4770df6`).

The full upstream TTF is used without subsetting so English and Russian headings
always render through one font rather than a script-specific fallback.

Tiny5 has no Japanese (CJK) glyphs. Pixel-style Japanese fonts exist, but on phone
screens their strokes are usually too thin or dense to stay readable. For Japanese
UI, missing glyphs therefore fall back to the system font: mixed Latin/CJK
appearance is an accepted trade-off rather than bundling a hard-to-read CJK
pixel face. JetBrains Mono likewise covers Latin/Cyrillic body text and relies on
the same system fallback for Japanese.

## JetBrains Mono

- Upstream: https://github.com/JetBrains/JetBrainsMono
- Release: `v2.304`
- Embedded font version: `2.304`
- Release asset: `JetBrainsMono-2.304.zip`
- Release asset SHA-256: `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`
- Selected font SHA-256:
  - `jetbrains_mono_bold.ttf`: `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb`
- Copyright: Copyright 2020 The JetBrains Mono Project Authors.
- License: SIL Open Font License 1.1; upstream `OFL.txt` is stored as
  `JetBrainsMono-OFL.txt` (SHA-256
  `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`).

