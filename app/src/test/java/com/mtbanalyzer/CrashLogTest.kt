package com.mtbanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class CrashLogTest {

    private fun reader(text: String) = BufferedReader(StringReader(text))

    @Test
    fun tailLines_keepsOnlyTheLastLines() {
        val log = (1..10_000).joinToString("\n") { "line $it" }
        assertEquals("line 9999\nline 10000", CrashLog.tailLines(reader(log), 2, 100))
    }

    @Test
    fun tailLines_cutsLongLines() {
        val out = CrashLog.tailLines(reader("x".repeat(5_000)), 5, 10)
        assertEquals("x".repeat(10) + "…", out)
    }

    @Test
    fun capLines_keepsAShortTraceWhole() {
        assertEquals("a\nb", CrashLog.capLines("a\nb\n", 5))
    }

    @Test
    fun capLines_saysHowMuchOfADeepTraceWasDropped() {
        val trace = (1..5_000).joinToString("\n") { "\tat Foo.bar(Foo.kt:$it)" }
        val out = CrashLog.capLines(trace, 120)
        assertEquals(121, out.lines().size)
        assertTrue(out.endsWith("… (4880 more lines)"))
    }
}
