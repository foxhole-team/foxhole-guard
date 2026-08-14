# Pixel Flags

- 32x18 pixel-art country flags, rendered by this project from the `flag-icons`
  SVG set (https://github.com/lipis/flag-icons, MIT, © Panayiotis Lipiridis):
  each 4x3 SVG is rasterised large, then every output pixel takes the dominant
  colour of its source cell — flat fields stay crisp, emblems are reduced to
  coarse pixels. Nepal keeps its non-rectangular shape via restored transparency.
- Shipped as `app/src/main/res/drawable-nodpi/flag_<iso2>.png` — 212 flags named
  by lowercase ISO 3166-1 alpha-2 code (`flag_us.png`).
- Rendered by `ui/cli/components/CliFlagIcon.kt`: nearest-neighbour, integer pixel
  scale, native colors (never theme-tinted).
- License of the source SVG set: MIT (full text in THIRD_PARTY_NOTICES.md). The
  previous asset pack (R74n Pixel Flags, no written license grant) is fully
  replaced by this in-house render.
