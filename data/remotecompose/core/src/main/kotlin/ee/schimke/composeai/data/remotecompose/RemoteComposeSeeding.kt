package ee.schimke.composeai.data.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue

/**
 * Where a Remote Compose player receives seeded named values, in the player's own types.
 *
 * Every player that draws a seeded document implements this over its own state — the AndroidX
 * embedded player's typed states, a `StateUpdater`'s `setUserLocal*` setters, a JVM or browser
 * player's named-value map — and [reseed] does the one thing they must all agree on: what a
 * [RemoteNamedValue] *means*. Kept here, in the alpha-free core module, so a player outside the
 * daemon's own connector (the JVM renderer, an rc-players backend) seeds a document exactly as the
 * connector does instead of carrying a mirror of the mapping that drifts.
 */
public interface NamedValueSeedTarget {
  public fun setString(name: String, value: String)

  public fun setFloat(name: String, value: Float)

  public fun setInt(name: String, value: Int)

  /**
   * A boolean seed. Players with no boolean slot store it as an int (`1` / `0`), which is what a
   * `rememberNamedRemoteInt` bound to the same name reads.
   */
  public fun setBoolean(name: String, value: Boolean)

  public fun setColor(name: String, argb: Int)

  /**
   * Restores [name] to its authored default. A player that cannot un-seed a value — the AndroidX
   * `StateUpdater` has no remove — leaves this a no-op and seeds a fresh document instead.
   */
  public fun clear(name: String) {}
}

/**
 * Seeds [overrides] into this target and returns the names it seeded, clearing any name in
 * [previous] that is no longer seeded.
 *
 * The mapping, shared by every player: a dp is a float (dp units are densitised float values once
 * they reach a player), a boolean is [NamedValueSeedTarget.setBoolean], and a colour goes through
 * [rcColorToArgb] so a six-digit value is opaque. An unparseable colour is skipped rather than
 * thrown, and counts as unseeded: a name that held a valid colour last time is restored rather than
 * left stale.
 */
public fun NamedValueSeedTarget.reseed(
  previous: Set<String> = emptySet(),
  overrides: Map<String, RemoteNamedValue>,
): Set<String> {
  val applied = LinkedHashSet<String>()
  for ((name, value) in overrides) {
    when (value) {
      is RemoteNamedValue.StringValue -> setString(name, value.value)
      is RemoteNamedValue.FloatValue -> setFloat(name, value.value)
      is RemoteNamedValue.IntValue -> setInt(name, value.value)
      is RemoteNamedValue.DpValue -> setFloat(name, value.value)
      is RemoteNamedValue.BooleanValue -> setBoolean(name, value.value)
      is RemoteNamedValue.ColorValue -> {
        val argb = rcColorToArgb(value.argb) ?: continue
        setColor(name, argb)
      }
    }
    applied += name
  }
  (previous - applied).forEach { clear(it) }
  return applied
}

/**
 * An rc colour string as an ARGB int: strip a leading `#` (or its URL-encoded `%23`), treat a
 * six-digit `#RRGGBB` as **opaque**, and accept only a resulting 8 hex digits. Null when it won't
 * parse.
 *
 * The wire model carries `argb` as an arbitrary string — a typo in a panel value would otherwise
 * crash the render path — so an unparseable colour is skipped by the callers rather than thrown.
 *
 * Prepending `FF` is the load-bearing part. Without it `#RRGGBB` becomes `0x00RRGGBB`, fully
 * transparent, so a six-digit seed *erases* what it was meant to recolour. Six digits is the
 * ordinary spelling of a colour — it is what a hand-typed `?rc.WearM3.primary=color:%23FF6F61`
 * carries, and what `ServeHost.themeReplayColors` publishes for a theme.
 */
public fun rcColorToArgb(raw: String): Int? {
  val hex = raw.removePrefix("%23").removePrefix("#")
  val opaque = if (hex.length == 6) "FF$hex" else hex
  return opaque.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
}
