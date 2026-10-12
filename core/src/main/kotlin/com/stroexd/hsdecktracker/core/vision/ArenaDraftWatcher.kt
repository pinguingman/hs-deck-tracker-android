package com.stroexd.hsdecktracker.core.vision

import kotlin.math.abs

/** Three cards offered to pick from, left to right; each entry holds all printings of one matched name. */
data class DraftPick(val cards: List<List<Int>>)

/**
 * Finds the three enlarged cards of an Arena draft outside of a match. Duels' bucket draft looks the
 * same and is found too - that's fine, both offer cards to rate. A pick only counts once two frames in
 * a row agree, and clearing it needs two frames without a row too, so one bad OCR frame doesn't flicker
 * the overlay.
 */
class ArenaDraftWatcher(private val index: CardNameIndex) {
    var decisionLog: ((String) -> Unit)? = null

    /** The cards currently offered, or null when the screen shows no draft choice. */
    var pick: DraftPick? = null
        private set

    private var previous: List<List<Int>>? = null
    private var missing = 0

    /** True when [pick] changed in this frame. */
    fun onFrame(frame: OcrFrame): Boolean {
        val read = read(frame)
        val stable = read == previous
        previous = read
        if (read != null) {
            missing = 0
            if (!stable || pick?.cards == read) return false
            pick = DraftPick(read)
            decisionLog?.invoke("Pick: $read")
            return true
        }
        // No row this frame: count misses directly, so a flicker mid-draft doesn't need yet another
        // repeat to register before it starts counting toward clearing the pick.
        if (pick == null) return false
        if (++missing < MISSING_FRAMES) return false
        pick = null
        decisionLog?.invoke("Pick: gone")
        return true
    }

    fun reset() {
        pick = null
        previous = null
        missing = 0
    }

    private fun read(frame: OcrFrame): List<List<Int>>? {
        val lines = frame.lines.map { ScreenRegions.toBoard(it, frame.aspect) }
        val candidates = mutableListOf<Pair<OcrLine, List<Int>>>()
        for (line in lines) {
            if (line.height < MIN_NAME_HEIGHT) continue
            if (ScreenRegions.isTopBar(line) || line.text.trimEnd().endsWith('.')) continue
            if (!ScreenRegions.isDraftCard(line)) continue
            if (line.continuesText(lines)) continue
            val match = index.match(line.text) ?: continue
            candidates += line to match.dbfIds
        }
        if (candidates.size > MAX_NAMES) return null
        val sorted = candidates.sortedByDescending { it.first.height }
        if (sorted.size < REQUIRED_CARDS) return null
        // A 4th candidate nearly as tall as the 3rd means an ambiguous row, not noise to discard.
        if (sorted.size > REQUIRED_CARDS &&
            sorted[REQUIRED_CARDS - 1].first.height - sorted[REQUIRED_CARDS].first.height < MIN_HEIGHT_GAP
        ) {
            return null
        }
        val top = sorted.take(REQUIRED_CARDS)
        if (!isRow(top.map { it.first })) return null
        return top.sortedBy { it.first.centerX }.map { it.second }
    }

    private fun isRow(lines: List<OcrLine>): Boolean {
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            if (abs(lines[i].centerY - lines[j].centerY) > ROW_Y_TOLERANCE) return false
        }
        val sortedByX = lines.sortedBy { it.centerX }
        for (i in 0 until sortedByX.size - 1) {
            if (sortedByX[i + 1].centerX - sortedByX[i].centerX < MIN_X_GAP) return false
        }
        return true
    }

    private companion object {
        const val REQUIRED_CARDS = 3
        /** More matched names than this means a collection page, not a draft. */
        const val MAX_NAMES = 5
        const val ROW_Y_TOLERANCE = 0.04f
        const val MIN_X_GAP = 0.12f
        const val MIN_NAME_HEIGHT = 0.022f
        const val MIN_HEIGHT_GAP = 0.01f
        const val MISSING_FRAMES = 2
    }
}
