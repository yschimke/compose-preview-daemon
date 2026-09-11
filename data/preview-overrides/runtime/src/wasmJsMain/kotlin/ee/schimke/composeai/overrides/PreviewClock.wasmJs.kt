package ee.schimke.composeai.overrides

@JsFun("() => Date.now()") private external fun dateNow(): Double

/** wasmJs wall clock, off the host page's `Date.now()`. */
actual val SystemPreviewClock: PreviewClock = PreviewClock { dateNow().toLong() }
