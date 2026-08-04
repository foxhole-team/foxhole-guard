# Pixel Flags

- Source: R74n Pixel Flags — https://r74n.com/pixelflags/
- 32x18 pixel-art country flags, downloaded 2026-07-22 from `png/country/` (current
  flags only; historical variants like `*_1912.png` were skipped).
- Shipped as `app/src/main/res/drawable-nodpi/flag_<iso2>.png` — 212 flags renamed
  from the pack's descriptive names to lowercase ISO 3166-1 alpha-2 codes
  (`united_states.png` -> `flag_us.png`). Non-ISO entities (historical states,
  micronations, unrecognized territories) were not shipped.
- Rendered by `ui/cli/components/CliFlagIcon.kt`: nearest-neighbour, integer pixel
  scale, native colors (never theme-tinted).
- License: R74n publishes its pixel-art packs for free use; the site page carries no
  explicit license text. Credit R74n in the app's about screen alongside the fonts
  and the 1-bit icon pack.
