# Translucent recording GIF disposal evidence

`GifEncoder` wrote every frame with disposal `none`, so a recording whose frames carry alpha (a
`showBackground = false` preview) composited each frame over the previous one and smeared. It now
restores to background when the frames have alpha, as `ScrollGifEncoder` already did.

Twelve frames of a dot moving across a transparent 240×80 canvas, encoded by `GifEncoder` at
8 fps:

| Before (disposal 0 on every frame) | After (disposal 2 on every frame) |
| --- | --- |
| ![Dot leaves a trail](before.gif) | ![Dot moves cleanly](after.gif) |
