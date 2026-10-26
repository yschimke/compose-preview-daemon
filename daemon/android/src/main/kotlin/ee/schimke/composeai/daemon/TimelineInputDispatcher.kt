package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputParams

/**
 * An [InteractiveSession] that can place an input on a scripted recording's own timeline
 * (issue #399), rather than treating it as live input.
 *
 * [InteractiveSession.dispatch] is built for a person clicking in a viewer: after each event it
 * advances the held clock by a settle window (100 ms for everything but a click) so the press is on
 * screen for the next frame. A scripted recording already advances the clock to every frame's time
 * itself, so those settles stacked on top of the script — a 60 ms swipe lasted several hundred
 * virtual milliseconds, `VelocityTracker` saw no fling, and frame 0 was captured late.
 */
internal interface TimelineInputDispatcher {

  /**
   * Advances the held clock by exactly [advanceBeforeMs], dispatches [input] at that time, and
   * returns without the interactive settle. Returns how many milliseconds the held clock moved in
   * total — [advanceBeforeMs] plus anything the gesture needs itself (a click's press hold) — or 0
   * when the input was dropped without reaching the sandbox.
   */
  fun dispatchOnTimeline(input: InteractiveInputParams, advanceBeforeMs: Long): Long
}
