package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.vision.ArenaDraftWatcher
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaDraftWatcherTest {
    private val db = TestCards.db
    private val index = CardNameIndex(db.deckCards.map { it.dbfId to it.name })
    private var time = 0L

    private fun line(text: String, x: Float, y: Float, h: Float = 0.045f, w: Float = 0.14f) =
        OcrLine(text, x - w / 2, y - h / 2, x + w / 2, y + h / 2)

    /** Every screen is shown for two frames, like a real capture of a still screen. */
    private fun ArenaDraftWatcher.show(vararg lines: OcrLine): Boolean {
        onFrame(OcrFrame(time++, lines.toList()))
        return onFrame(OcrFrame(time++, lines.toList()))
    }

    private fun draftRow(vararg names: String, y: Float = 0.545f) =
        names.mapIndexed { i, n -> line(n, 0.27f + i * 0.23f, y) }.toTypedArray()

    @Test
    fun findsThreeEnlargedCards() {
        val watcher = ArenaDraftWatcher(index)
        assertTrue(watcher.show(*draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")))
        assertEquals(
            listOf(listOf(TestCards.FIREBALL), listOf(TestCards.LEEROY), listOf(TestCards.RARE_NEUTRAL)),
            watcher.pick?.cards,
        )
    }

    @Test
    fun oneFrameIsNotEnough() {
        val watcher = ArenaDraftWatcher(index)
        val changed = watcher.onFrame(OcrFrame(time++, draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener").toList()))
        assertFalse(changed)
        assertNull(watcher.pick)
    }

    @Test
    fun oneBadFrameKeepsThePickButTwoClearIt() {
        val watcher = ArenaDraftWatcher(index)
        watcher.show(*draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener"))
        assertTrue(watcher.pick != null)

        // One frame with nothing recognized: the pick survives
        assertFalse(watcher.onFrame(OcrFrame(time++, emptyList())))
        assertTrue(watcher.pick != null)

        // A second empty frame in a row: the pick is cleared
        assertTrue(watcher.onFrame(OcrFrame(time++, emptyList())))
        assertNull(watcher.pick)
    }

    @Test
    fun ignoresTwoCards() {
        val watcher = ArenaDraftWatcher(index)
        assertFalse(watcher.show(*draftRow("Feuerball", "Leeroy Jenkins")))
        assertNull(watcher.pick)
    }

    @Test
    fun ignoresFourEquallySizedCards() {
        val watcher = ArenaDraftWatcher(index)
        val names = listOf("Feuerball", "Arkane Geschosse", "Leeroy Jenkins", "Seltener Diener")
        val xs = listOf(0.2f, 0.4f, 0.6f, 0.8f)
        val row = names.zip(xs) { n, x -> line(n, x, 0.545f) }
        assertFalse(watcher.show(*row.toTypedArray()))
        assertNull(watcher.pick)
    }

    @Test
    fun ignoresACollectionGrid() {
        val watcher = ArenaDraftWatcher(index)
        val grid = (0..1).flatMap { row ->
            (0..3).map { col ->
                line(TestCards.db.deckCards[(row * 4 + col) % TestCards.db.deckCards.size].name, 0.2f + col * 0.2f, 0.3f + row * 0.2f, h = 0.025f)
            }
        }
        assertFalse(watcher.show(*grid.toTypedArray()))
        assertNull(watcher.pick)
    }

    @Test
    fun ignoresSmallerStrayTextThatNamesACard() {
        val watcher = ArenaDraftWatcher(index)
        val row = draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")
        // A smaller line below one card, happening to read as another card's name without a trailing period.
        val stray = line("Arkane Geschosse", 0.27f, 0.635f, h = 0.025f)
        assertTrue(watcher.show(*row, stray))
        assertEquals(
            listOf(listOf(TestCards.FIREBALL), listOf(TestCards.LEEROY), listOf(TestCards.RARE_NEUTRAL)),
            watcher.pick?.cards,
        )
    }

    @Test
    fun ignoresCardBodyTextEndingInAPeriod() {
        val watcher = ArenaDraftWatcher(index)
        val row = draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")
        val text = line("Verursacht 6 Schaden.", 0.27f, 0.635f, h = 0.03f)
        assertTrue(watcher.show(*row, text))
        assertEquals(
            listOf(listOf(TestCards.FIREBALL), listOf(TestCards.LEEROY), listOf(TestCards.RARE_NEUTRAL)),
            watcher.pick?.cards,
        )
    }

    @Test
    fun aNewPickReplacesTheOld() {
        val watcher = ArenaDraftWatcher(index)
        assertTrue(watcher.show(*draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")))
        assertFalse(watcher.show(*draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")))
        assertTrue(watcher.show(*draftRow("Arkane Geschosse", "Leeroy Jenkins", "Seltener Diener")))
        assertEquals(
            listOf(listOf(TestCards.ARCANE_MISSILES), listOf(TestCards.LEEROY), listOf(TestCards.RARE_NEUTRAL)),
            watcher.pick?.cards,
        )
    }

    @Test
    fun resetClearsThePick() {
        val watcher = ArenaDraftWatcher(index)
        watcher.show(*draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener"))
        assertTrue(watcher.pick != null)
        watcher.reset()
        assertNull(watcher.pick)
    }

    @Test
    fun aspectIsCorrected() {
        val watcher = ArenaDraftWatcher(index)
        val aspect = 2.17f
        // The board keeps the reference (2.22) aspect; raw screen x must be un-transformed to land there.
        fun toRaw(boardX: Float) = 0.5f + (boardX - 0.5f) * REFERENCE_ASPECT / aspect
        val row = draftRow("Feuerball", "Leeroy Jenkins", "Seltener Diener")
            .map { l -> l.copy(left = toRaw(l.left + l.width / 2) - l.width / 2, right = toRaw(l.right - l.width / 2) + l.width / 2) }
        val changed = (1..2).map { watcher.onFrame(OcrFrame(time++, row, aspect = aspect)) }.last()
        assertTrue(changed)
        assertEquals(
            listOf(listOf(TestCards.FIREBALL), listOf(TestCards.LEEROY), listOf(TestCards.RARE_NEUTRAL)),
            watcher.pick?.cards,
        )
    }

    private companion object {
        const val REFERENCE_ASPECT = 2.22f
    }
}
