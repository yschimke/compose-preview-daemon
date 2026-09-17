# Glimmer previews as SVG primitives

Evidence for the `compose/figma-svg` export of a Glimmer (`androidx.xr.glimmer`) container, taken
against `glimmer-catalog`'s `card__ideal__default` — the kit `Card` with its header artwork, leading
icon, title, subtitle and body.

| file | what it is |
| --- | --- |
| `reference.png` | the published raster render — the pixels the SVG has to describe |
| `before.png` | the published SVG, rendered in Chromium |
| `after.png` | the same SVG with this change's tokens, rendered the same way |

`before.png` is the bug: `Modifier.surface` paints the card's fill and border from a
`DrawModifierNode` that looks like neither `Modifier.background` nor `Modifier.border`, so the
export resolved no paint for it at all and emitted an empty layer — a white card with
white-on-white text, a black icon where a white one renders, and the header artwork cropped to a
raster `<image>`.

`after.png` is fill, border, icon tint and header gradient as SVG primitives. What still differs
from `reference.png` is the text shaping (the embedded face is subset with its `GPOS`/`kern`
stripped, so the browser lays the runs out unkerned — pre-existing, and the same on every SVG this
pipeline emits) and the soft edge of the card's border, which Glimmer draws through a two-pass
runtime-shader blur that has no SVG form and is emitted as the crisp stroke Glimmer strokes
underneath it.

The header gradient is pixel-exact: sampled at nine points across the slot, `after.png` and
`reference.png` agree on every channel. `PainterBrushGradientTest` pins the four
`<linearGradient>` coordinates that make that true.
