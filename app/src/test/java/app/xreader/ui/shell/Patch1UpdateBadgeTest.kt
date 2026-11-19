package app.xreader.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7 Patch 1 (item 4): release notifications ride the
 * EXISTING dock badge system — a newer release raises the
 * Profile tab's badge (where Updates & about lives), while
 * the Explore tab keeps its pending manga-source update
 * count and every other tab stays unbadged.
 */
class Patch1UpdateBadgeTest {

    @Test
    fun `explore keeps its manga-source count`() {
        assertEquals(
            3, tabBadgeCount(Tab.Browse, 3, updateAvailable = false)
        )
        assertEquals(
            3, tabBadgeCount(Tab.Browse, 3, updateAvailable = true)
        )
    }

    @Test
    fun `profile badges 1 while a newer release is known`() {
        assertEquals(
            1, tabBadgeCount(Tab.Me, 0, updateAvailable = true)
        )
    }

    @Test
    fun `profile is unbadged with no newer release`() {
        assertEquals(
            0, tabBadgeCount(Tab.Me, 0, updateAvailable = false)
        )
    }

    @Test
    fun `other tabs stay unbadged`() {
        for (tab in listOf(Tab.Library, Tab.History, Tab.Downloads)) {
            assertEquals(
                0, tabBadgeCount(tab, 5, updateAvailable = true)
            )
        }
    }
}
