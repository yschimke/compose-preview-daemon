package com.google.wear.input;

/** Signature placeholder for optional Wear scrolling-haptic constants. */
public final class WearHapticFeedbackConstants {
  private WearHapticFeedbackConstants() {}

  public static int getScrollItemFocus() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public static int getScrollLimit() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public static int getScrollTick() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
