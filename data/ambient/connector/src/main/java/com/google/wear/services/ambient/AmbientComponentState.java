package com.google.wear.services.ambient;

/** Linkage shim paired with the shadowed Wear Compose ambient manager implementation. */
final class AmbientComponentState {
  private AmbientComponentState() {}

  static ActivityStateRegistry makeActivityStateRegistry() {
    return new ActivityStateRegistry();
  }

  static final class ActivityStateRegistry {
    void onResume() {}

    void onPause() {}
  }
}
