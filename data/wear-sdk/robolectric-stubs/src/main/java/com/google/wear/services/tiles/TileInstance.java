package com.google.wear.services.tiles;

/** Signature placeholder for an optional Wear SDK tile instance. */
public class TileInstance {
  public TileInstance() {}

  public int getId() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }

  public TileProvider getTileProvider() {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
