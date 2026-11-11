package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.data.NoteNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NoteNamesTest {
    private val zone = ZoneId.of("Europe/London")
    private val millis = LocalDateTime.of(2026, 11, 11, 14, 30, 5).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun fileNameUsesHyphensAnd24HourTime() {
        assertEquals("11-11-2026-14-30-05", NoteNames.baseName(millis, zone))
        assertFalse(NoteNames.baseName(millis, zone).contains(':'))
    }

    @Test
    fun displayTimestamp() {
        assertEquals("11-11-2026 14:30:05", NoteNames.displayTimestamp(millis, zone))
    }

    @Test
    fun clashesGetNumberSuffix() {
        val taken = mutableSetOf("11-11-2026-14-30-05")
        assertEquals("11-11-2026-14-30-05-2", NoteNames.unique("11-11-2026-14-30-05") { it in taken })
        taken += "11-11-2026-14-30-05-2"
        assertEquals("11-11-2026-14-30-05-3", NoteNames.unique("11-11-2026-14-30-05") { it in taken })
        assertEquals("01-02-2027-00-00-00", NoteNames.unique("01-02-2027-00-00-00") { it in taken })
    }
}
