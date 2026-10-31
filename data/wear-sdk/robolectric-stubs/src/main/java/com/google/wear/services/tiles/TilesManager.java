package com.google.wear.services.tiles;

import android.os.OutcomeReceiver;
import java.util.List;
import java.util.concurrent.Executor;

/** Signature placeholder for the optional Wear SDK tiles manager. */
public class TilesManager {
  public static final int WIDGET_CONTAINER_TYPE_FULLSCREEN = 1;
  public static final int WIDGET_CONTAINER_TYPE_LARGE = 2;
  public static final int WIDGET_CONTAINER_TYPE_SMALL = 3;

  public TilesManager() {}

  public void getActiveTiles(
      Executor executor, OutcomeReceiver<List<TileInstance>, Exception> receiver) {
    throw new UnsupportedOperationException("Wear SDK calls require the Compose Preview Wear simulator");
  }
}
