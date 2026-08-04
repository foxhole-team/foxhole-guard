# Bundled fonts

Third-party font assets shipped inside the APK at `app/src/main/res/font/` — the
single fixed pixel style of the CLI UI (see `ui/cli/CliTheme.kt`). All three faces
are licensed under the SIL Open Font License, Version 1.1
(https://openfontlicense.org), which permits bundling, subsetting and redistribution
as long as the copyright and license notice travel with the font. Copyright lines
below are taken verbatim from the `name` tables of the shipped files.

## lanapixel.ttf

- Face: LanaPixel (11px-design pixel font; body/small/button text)
- Copyright: Copyright (c) 2020, eishiya
- License: SIL Open Font License 1.1
- Source: https://opengameart.org/content/lanapixel-localization-friendly-pixel-font
- Shipped as a subset: Basic Latin, Latin-1 Supplement, Latin Extended-A (partial),
  Cyrillic, general punctuation, arrows (U+2190–2199). NOTE: the box-drawing /
  geometric-shape / dingbat ranges (U+2500–25FF, U+2700–27BF) are NOT in this subset —
  the CLI glyphs ▸ ▾ ● ○ ❯ █ ░ ▒ ─ render via the platform symbol fallback.

## press_start_2p.ttf

- Face: Press Start 2P (arcade caps; fixed-width accents — ASCII art, PIN dots)
- Copyright: Copyright 2012 The Press Start 2P Project Authors (cody@zone38.net),
  with Reserved Font Name "Press Start 2P"
- License: SIL Open Font License 1.1 (embedded in the font's license name table)
- Source: https://fonts.google.com/specimen/Press+Start+2P

## silkscreen_bold.ttf

- Face: Silkscreen Bold (8px-design techno pixel face; display titles — brand
  «FOXHOLE GUARD», screen headers, panel captions)
- Copyright: Copyright 2001 The Silkscreen Project Authors
  (https://github.com/googlefonts/silkscreen)
- License: SIL Open Font License 1.1 (`OFL.txt` next to this file, vendored verbatim
  from the upstream repository)
- Source: https://github.com/googlefonts/silkscreen (fonts/ttf/Silkscreen-Bold.ttf)
- Latin-only: the Compose FontFamily chains LanaPixel as a per-glyph fallback, so
  cyrillic display text (ru locale) renders in LanaPixel.

TODO: vendor the verbatim OFL-1.1 license texts for LanaPixel and Press Start 2P from
the upstream distributions next to this file (one LICENSE per component).
