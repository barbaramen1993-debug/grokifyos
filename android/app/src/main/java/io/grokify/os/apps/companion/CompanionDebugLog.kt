package io.grokify.os.apps.companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ring buffer of Companion AI traffic for the in-app debug overlay.
 *
 * - [Dir.Out] — host → model (instructions, tool results the AI "reads")
 * - [Dir.In] — model → host (transcripts, tool calls the AI "sends")
 * - [Dir.Sys] — session lifecycle / notes
 */
object CompanionDebugLog {
    enum class Dir { Out, In, Sys }

    data class Entry(
        val id: Long,
        val atMs: Long,
        val dir: Dir,
        val kind: String,
        val summary: String,
        val detail: String,
    )

    /** Larger buffer so joint-motion samples don't push out AI traffic too fast. */
    private const val CAP = 200
    private val lock = Any()
    private val ring = ArrayDeque<Entry>(CAP)
    private var seq = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (_: Throwable) {
        null
    }

    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(on: Boolean) {
        val was = enabled
        enabled = on
        if (on && !was) {
            append(Dir.Sys, "debug", "Debug overlay on", "AI traffic + skeleton wireframe")
        }
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        notifyListeners()
    }

    fun append(dir: Dir, kind: String, summary: String, detail: String = "") {
        if (!enabled) return
        val entry = Entry(
            id = synchronized(lock) { ++seq },
            atMs = SystemClock.elapsedRealtime(),
            dir = dir,
            kind = kind.trim().ifBlank { "note" },
            summary = summary.trim().take(240),
            detail = detail.trim().take(8_000),
        )
        synchronized(lock) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(entry)
        }
        notifyListeners()
    }

    /** Snapshot newest-last (chronological). */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    /** Plain-text export of one entry (for clipboard). */
    fun formatEntry(e: Entry): String {
        val dirMark = when (e.dir) {
            Dir.Out -> "→"
            Dir.In -> "←"
            Dir.Sys -> "·"
        }
        return buildString {
            append(dirMark)
            append(" [")
            append(e.kind)
            append("] ")
            append(e.summary)
            if (e.detail.isNotBlank()) {
                append('\n')
                append(e.detail)
            }
        }
    }

    /** Plain-text export of the whole ring (newest last). */
    fun formatAll(entries: List<Entry> = snapshot()): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n\n") { formatEntry(it) }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val run = Runnable {
            for (l in listeners) {
                runCatching { l.invoke() }
            }
        }
        val h = mainHandler
        if (h != null && Looper.myLooper() != Looper.getMainLooper()) {
            h.post(run)
        } else {
            run.run()
        }
    }
}
