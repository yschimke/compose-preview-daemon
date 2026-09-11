package ee.schimke.composeai.overrides

/** JVM wall clock. */
actual val SystemPreviewClock: PreviewClock = PreviewClock { System.currentTimeMillis() }
