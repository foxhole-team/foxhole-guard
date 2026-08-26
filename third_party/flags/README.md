# Flag assets

FoxHole Guard ships two separately rendered flag sets derived from the MIT-licensed
[`lipis/flag-icons`](https://github.com/lipis/flag-icons) SVG collection.

## Runtime flag assets

- Output: `app/src/main/assets/flags/*.png`.
- Inventory: 270 lowercase code names, each 256x192 RGBA PNG. The exact inventory
  and output hashes are in `app-assets.sha256`.
- Source: the upstream `flags/4x3/*.svg` tree at commit
  `086f7e97d657358203916dbe84f61c2bccaa81eb` (2026-04-07).
- Source tree Git object: `c4c2a6afc33d534b412f7a1ed1ab372fb4b1e2d0`.
- Source archive:
  `https://codeload.github.com/lipis/flag-icons/tar.gz/086f7e97d657358203916dbe84f61c2bccaa81eb`
  with SHA-256
  `eb5b814c794cda735155e2874e288ad9302b6f7a5728e5e50d3ef0333b6c20f4`.
- Renderer: `@resvg/resvg-wasm` 2.6.2. Its npm archive SHA-256 is
  `ff51acbb5ee0074601b75c3bea9226a18d346752af787f6d2d3adcdd98493d71`.
  The renderer is an MPL-2.0 build tool and is not packaged in the application.
- Selection: the manifest preserves the application's existing 270 code names.
  The pinned upstream tree has one additional source, `sh-ac.svg`, which is not
  shipped.

Every committed PNG is byte-for-byte identical to the output produced by the
pinned source and renderer. Regenerate and verify the complete set with:

```sh
./scripts/generate-app-flag-assets.sh
```

The script renders into a temporary directory, checks all generated files against
`app-assets.sha256`, and only then copies them into the application assets.

## Pixel flag resources

- Output: `app/src/main/res/drawable-nodpi/flag_<iso2>.png`.
- Inventory: 212 flags named by lowercase ISO 3166-1 alpha-2 code, each rendered
  as 32x18 pixel art from the same upstream SVG collection.
- Rendering: each source SVG is rasterised large and reduced by dominant colour
  per output cell. Nepal retains transparency around its non-rectangular flag.
- Display: nearest-neighbour integer scaling and native colours, without theme
  tinting.

The runtime asset set above is the set loaded by `CliFlagIcon`; it is distinct
from these pixel resources. The earlier documentation incorrectly described only
the pixel resources while the runtime asset set was also shipped.

## License

The upstream source is Copyright (c) 2013 Panayiotis Lipiridis and licensed under
the MIT License. The exact upstream text is vendored as `flag-icons-LICENSE.txt`.
