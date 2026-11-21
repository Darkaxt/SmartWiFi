package io.github.darkaxt.smartwifi.core

import java.io.File

/**
 * Append-only event log kept in two rotating files (current + previous), so history survives
 * process restarts and reboots while total size stays bounded at about 2 × [maxBytes].
 */
class EventLogFile(dir: File, private val maxBytes: Long = 512 * 1024) {
    private val current = File(dir, "events.log")
    private val previous = File(dir, "events.1.log")

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun append(line: String) {
        if (current.length() + line.length + 1 > maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(line + "\n")
    }

    /** The last [n] lines, oldest first. */
    @Synchronized
    fun tail(n: Int): List<String> = (lines(previous) + lines(current)).takeLast(n)

    /** Whole retained history, oldest first. */
    @Synchronized
    fun readAll(): String = text(previous) + text(current)

    private fun lines(f: File) = if (f.isFile) f.readLines() else emptyList()
    private fun text(f: File) = if (f.isFile) f.readText() else ""
}
