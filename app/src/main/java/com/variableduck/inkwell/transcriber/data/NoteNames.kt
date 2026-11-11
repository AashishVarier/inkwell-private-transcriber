package com.variableduck.inkwell.transcriber.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * File naming: a recording and its transcript share one timestamp taken when recording starts,
 * in 24-hour local time, e.g. `11-11-2026-14-30-05.wav` / `11-11-2026-14-30-05.txt`.
 * Hyphens only, never colons: Windows and most USB/SD storage reject `:` in file names.
 * In the app the same moment is shown as `11-11-2026 14:30:05`.
 */
object NoteNames {
    // Locale.US keeps the digits ASCII whatever the phone's language is.
    private val fileFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy-HH-mm-ss", Locale.US)
    private val displayFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss", Locale.US)

    fun baseName(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fileFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun displayTimestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        displayFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Returns [base], or `base-2`, `base-3`, … if a name is already taken. */
    fun unique(base: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(base)) return base
        var n = 2
        while (isTaken("$base-$n")) n++
        return "$base-$n"
    }
}
