package com.seastreams.tv.testutil

/**
 * Old-scheme RAR volume index → extension (`0` → `rar`, `1` → `r00`, `101` → `s00`): the inverse of
 * `RarVolumeNames.parse` for that scheme, used to name test volumes.
 */
fun oldRarExtension(index: Int): String {
    if (index == 0) return "rar"
    val n = index - 1
    return "${'r' + n / 100}${"%02d".format(n % 100)}"
}
