package io.github.darkaxt.smartwifi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventLogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun historySurvivesReopening() {
        val dir = File(tmp.root, "logs")
        EventLogFile(dir).apply { append("a"); append("b") }
        val reopened = EventLogFile(dir)
        reopened.append("c")
        assertEquals(listOf("b", "c"), reopened.tail(2))
        assertEquals("a\nb\nc\n", reopened.readAll())
    }

    @Test fun rotatesAndStaysBounded() {
        val dir = tmp.root
        val log = EventLogFile(dir, maxBytes = 100)
        repeat(100) { log.append("line %03d xxxxxxxx".format(it)) } // 18 bytes per line
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("retained $total bytes", total <= 200)
        assertEquals("line 099 xxxxxxxx", log.tail(1).single())
        val all = log.readAll().lines().filter { it.isNotEmpty() }
        assertEquals(all.sorted(), all) // oldest first across the rotated pair
        assertTrue(all.size >= 5)
    }
}
