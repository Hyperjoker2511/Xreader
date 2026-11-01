package app.xreader.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.3 (V132-CHECK 21, scope addition 4): the DESIGNED
 * TITLE COVER's pure rules — a coverless book (Syosetu
 * serves no covers at all) renders its full title as cover
 * typography over a per-book violet-family gradient, the
 * same book wearing the same hue in the catalogue, the
 * Library and everywhere else; a real cover always wins.
 */
class V133TitleCoverTest {

    @Test
    fun `the same book derives the same hue everywhere`() {
        val first = TitleCoverDesign.hueFor("Shadow Slave")
        assertEquals(first, TitleCoverDesign.hueFor("Shadow Slave"))
        // Whitespace/case wobble in a stored title must not
        // repaint the book a different colour.
        assertEquals(first, TitleCoverDesign.hueFor("  shadow slave "))
        assertEquals(
            TitleCoverDesign.gradientColors("Shadow Slave"),
            TitleCoverDesign.gradientColors("Shadow Slave")
        )
    }

    @Test
    fun `hues stay inside the nocturne violet band`() {
        val titles = listOf(
            "Shadow Slave", "Omniscient Reader's Viewpoint",
            "The Beginning After The End", "Re:Zero",
            "Solo Leveling", "A Record of a Mortal's Journey",
            "Lord of the Mysteries", "Martial World", "x", ""
        )
        for (title in titles) {
            val hue = TitleCoverDesign.hueFor(title)
            assertTrue(
                "hue $hue for '$title' leaves the violet band",
                hue >= TitleCoverDesign.HUE_MIN &&
                    hue < TitleCoverDesign.HUE_MIN +
                    TitleCoverDesign.HUE_SPAN
            )
        }
    }

    @Test
    fun `a real cover wins and a missing one takes the title cover`() {
        assertTrue(TitleCoverDesign.usesTitleCover(null))
        assertTrue(TitleCoverDesign.usesTitleCover(""))
        assertTrue(TitleCoverDesign.usesTitleCover("   "))
        assertFalse(
            TitleCoverDesign.usesTitleCover(
                "https://covers.example/book.jpg"
            )
        )
        assertFalse(TitleCoverDesign.usesTitleCover(java.io.File("c.jpg")))
    }
}
