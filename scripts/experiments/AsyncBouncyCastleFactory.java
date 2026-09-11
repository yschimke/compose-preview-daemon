package org.robolectric.android.internal;

import java.util.concurrent.CompletableFuture;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Experimental overlap only: registration still waits for the actual provider. */
public final class AsyncBouncyCastleFactory {
  public static CompletableFuture<BouncyCastleProvider> start() {
    return CompletableFuture.supplyAsync(BouncyCastleProvider::new);
  }
}
