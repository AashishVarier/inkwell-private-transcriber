package com.variableduck.inkwell.transcriber.util

import java.util.Locale

object Format {
    /** 0:05, 12:34, 1:02:03 */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun percent(fraction: Float): String = "${(fraction * 100).toInt().coerceIn(0, 100)}%"

    /** "less than a minute left", "about 3 min left", "about 1 h 5 min left". */
    fun remaining(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        return when {
            ms < 60_000 -> "less than a minute left"
            minutes < 60 -> "about $minutes min left"
            else -> "about ${minutes / 60} h ${minutes % 60} min left"
        }
    }
}
