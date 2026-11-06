package com.egouvc.phone.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ImuAttemptTraceTest {
    @Test fun boundedTracePreservesAttemptTimingAndCountsOverflow() {
        val trace = ImuAttemptTrace(2)
        trace.record(1, 100, 101, 105, 252, 1, 32, 448, 106, 110, 1, 10, 111, 121)
        trace.record(2, 122, 123, 124, 0, 3, -1, -1, 0, 0, 0, 10, 125, 135)
        trace.record(3, 136, 137, 138, -1, 3, -1, -1, 0, 0, 0, 10, 139, 149)
        val writer = StringWriter()
        trace.writeTo(writer)
        val lines = writer.toString().trimEnd().lines()
        assertEquals(2, trace.size)
        assertEquals(1L, trace.omitted)
        assertEquals(3, lines.size)
        assertEquals(14, lines[0].split(",").size)
        assertEquals("1,100,101,105,252,1,32,448,106,110,1,10,111,121", lines[1])
        assertEquals("2,122,123,124,0,3,-1,-1,0,0,0,10,125,135", lines[2])
    }
    @Test fun emptyTraceExportsHeaderOnly() {
        val writer = StringWriter()
        ImuAttemptTrace(1).writeTo(writer)
        assertEquals(1, writer.toString().trimEnd().lines().size)
    }
}
