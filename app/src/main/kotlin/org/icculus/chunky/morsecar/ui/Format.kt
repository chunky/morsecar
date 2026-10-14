package org.icculus.chunky.morsecar.ui

/** "m:ss", or "h:mm:ss" from an hour up. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
    val h = totalSeconds / 3600
    val m = totalSeconds / 60 % 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatWpm(wpm: Double): String = "%.1f".format(wpm).removeSuffix(".0")
