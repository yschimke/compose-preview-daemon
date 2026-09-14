package com.google.wear.services.ambient;

/**
 * Linkage shim for the Wear Services ambient API referenced by Wear Compose.
 *
 * <p>The real type is supplied by Wear OS. Robolectric must nevertheless resolve method and field
 * descriptors on {@code AmbientModeManagerImpl} before it can attach our shadow, so previews need
 * this minimal definition when the system-only API is absent.
 */
final class AmbientManager {
  private AmbientManager() {}

  static final class Controller {
    void destroy() {}
  }
}
