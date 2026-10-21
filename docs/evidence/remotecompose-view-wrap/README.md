# Remote Compose view player wrap-content evidence

A wrap-content Remote Compose preview (no `device`, `widthDp` or `heightDp`) whose content is one
40dp red `RemoteBox`, played by the `androidx-view` player inside a wrap-content grey box on a
200×400dp xhdpi window (#342). Before, the player sized itself to the document's declared size —
the capture canvas — so the whole window was the preview. After, it measures to its content's
laid-out bounds, 80×80px, as the `androidx-embedded` player already did.

| Before (400×800px) | After (80×80px) |
| --- | --- |
| ![The 40dp box at the corner of a full-window grey player](before.png) | ![The 40dp box alone](after.png) |

Captured from the connector's `RemoteOverridablePreview` with `player = VIEW`;
`RemoteWrapContentSizeTest` asserts the 80×80px size for every player and that a sized preview
keeps its size.
