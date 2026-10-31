package com.google.wear.services.tiles;

import android.content.ComponentName;

/** Signature placeholder for an optional Wear SDK tile provider. */
public class TileProvider {
  public TileProvider() {}

  public ComponentName getComponentName() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public int getContainerType() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public String getGroup() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
