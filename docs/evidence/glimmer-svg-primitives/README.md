# Glimmer previews as SVG primitives

Evidence for the `compose/figma-svg` export of a Glimmer (`androidx.xr.glimmer`) container.

Every file here is produced by `FigmaSvgGlimmerCardRenderTest`, which composes Glimmer's `Card`
modifier chain for real — the surface's `clip` plus draw-only element, the `defaultMinSize` and 12dp
`contentPadding` behind it, the header slot, the leading icon and the text column — and exports it.
`svg-before.png` is the same test run against the pre-fix connector, so the comparison is
reproducible rather than reconstructed.

| file | what it is |
| --- | --- |
| `render.png` | the fixture's own render — the pixels the SVG has to describe |
| `svg-before.png` | the SVG the export emitted before the fix, rendered in Chromium |
| `svg-after.png` | the SVG it emits now, rendered the same way |

Against `render.png`:

| | mean channel delta | pixels differing by >15 |
| --- | --- | --- |
| `svg-before.png` | 56.0 | 27.0% |
| `svg-after.png` | **2.5** | **2.0%** |

`svg-before.png` is the bug, and it reproduces the published `glimmer-catalog` SVG exactly:
`Modifier.surface` paints the card's fill and border from a `DrawModifierNode` that looks like
neither `Modifier.background` nor `Modifier.border`, so the export resolved no paint for it and
emitted an empty layer — no card, white-on-white title and subtitle, a black icon where a white one
renders, and the card's box 12px in on every side, sharing a top-left corner with its own header
image.

`svg-after.png` is fill, border, icon tint and geometry as SVG primitives. The card is drawn at its
outer box again rather than its 12dp-padded content box; the test pins that as the difference
between the node's placed `bounds` and its resolved `paintBox`, which is the part only a live
composition can settle.

The fakes standing in for Glimmer's elements are documented in
[`GlimmerSurfaceFakes.kt`](../../../renderers/android/src/test/kotlin/androidx/xr/glimmer/testfake/GlimmerSurfaceFakes.kt),
including why the real library is not on that classpath.

What still differs from `render.png`:

- **Text shaping**, unchanged and unrelated: the exported face is subset with its `GPOS`/`kern`
  stripped, so a browser lays the runs out unkerned. It is most of the residual 2%.
- **The header artwork is still an `<image>`.** The gradient itself resolves — the test pins all
  four `<linearGradient>` coordinates — but `FigmaSvgModel.toLayer` checks its opaque-by-name rule
  before it looks at whether a node's paint has a vector form, and `Image`'s node resolves to
  `ImageKt`. That ordering lives in `compose-preview-contracts`;
  `the emitter still rasters a node named Image however vectorisable its paint` pins it so a fix
  there is noticed here. The published catalog does not hit it — its nodes carry no source info and
  read `ReusableComposeNode`, so the header falls through to the check the resolved gradient now
  satisfies.
- **The card's soft edge** is absent, by design. Glimmer records background and border into a
  `GraphicsLayer` and blurs both with a two-pass `RuntimeShader` (2dp→8dp at idle); there is no SVG
  form for that, so the crisp stroke Glimmer strokes underneath is emitted instead. The fixture's
  stand-in surface does not reproduce the blur either, so it is not visible in `render.png` here —
  the published catalog render shows it.
