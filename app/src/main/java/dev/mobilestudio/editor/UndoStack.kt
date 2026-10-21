package dev.mobilestudio.editor

/**
 * Snapshot-based undo/redo for one document.
 *
 * Typing produces many tiny changes; consecutive changes that arrive within [mergeWindowMs]
 * of each other are merged into one undo step so Undo removes a burst of typing, not a letter.
 * Memory is bounded by [maxEntries].
 */
class UndoStack(
    initial: String,
    private val maxEntries: Int = 200,
    private val mergeWindowMs: Long = 800,
) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var current: String = initial
    private var lastChangeAt: Long = Long.MIN_VALUE

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Record that the document is now [text]. No-op if it did not change. */
    fun record(text: String, nowMs: Long = System.currentTimeMillis()) {
        if (text == current) return
        val merge = undo.isNotEmpty() && nowMs - lastChangeAt <= mergeWindowMs
        if (!merge) {
            undo.addLast(current)
            if (undo.size > maxEntries) undo.removeFirst()
        }
        redo.clear()
        current = text
        lastChangeAt = nowMs
    }

    /** Returns the previous text, or null when there is nothing to undo. */
    fun undo(): String? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = previous
        lastChangeAt = Long.MIN_VALUE // the next edit starts a new step
        return previous
    }

    /** Returns the next text, or null when there is nothing to redo. */
    fun redo(): String? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = next
        lastChangeAt = Long.MIN_VALUE
        return next
    }
}
