package com.google.wear;

/** Signature placeholder for the optional Wear SDK API-version token. */
public final class WearApiVersion implements Comparable<WearApiVersion> {
  WearApiVersion() {}

  @Override
  public int compareTo(WearApiVersion other) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
