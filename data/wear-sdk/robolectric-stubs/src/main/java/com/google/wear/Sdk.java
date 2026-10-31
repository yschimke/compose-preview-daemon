package com.google.wear;

import android.content.Context;

/**
 * Signature placeholder for the optional Wear SDK. Robolectric shadows every callable member;
 * executing this body indicates the caller was launched without the simulator shadow.
 */
public final class Sdk {
  public static final String FEATURE_WEAR_GESTURE_DETECTION = "com.google.wear.feature.GESTURE_DETECTION";
  public static final String FEATURE_WEAR_MATERIAL_3 = "com.google.wear.feature.MATERIAL_3";
  public static final String FEATURE_WEAR_TIRAMISU_QPR1_API = "com.google.wear.feature.TIRAMISU_QPR1";
  public static final String FEATURE_WEAR_TIRAMISU_QPR2_API = "com.google.wear.feature.TIRAMISU_QPR2";
  public static final String FEATURE_WEAR_TIRAMISU_QPR3_API = "com.google.wear.feature.TIRAMISU_QPR3";
  public static final String FEATURE_WEAR_TIRAMISU_QPR4_API = "com.google.wear.feature.TIRAMISU_QPR4";

  private Sdk() {}

  public static <T> T getWearManager(Context context, Class<T> managerClass) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public static <T> T getWearManager(Class<T> managerClass) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public static boolean hasApiFeature(String feature) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public static boolean isApiVersionAtLeast(WearApiVersion version) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
