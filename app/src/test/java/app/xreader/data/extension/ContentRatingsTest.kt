package app.xreader.data.extension

import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (M1): the content-rating rules (Mihon
 * model) — the preference matrix every list/search seam
 * filters through, the badge a listed source wears, and the
 * ONE preference's storage round-trip (default: hide 18+).
 * The search seam filters handles by exactly
 * [ContentAccess.allows] per source; the repo-listing seam
 * by the same predicate per entry (see KeiyoushiIndexTest's
 * rows) — this file pins the shared predicate itself.
 */
class ContentRatingsTest {

    @Test
    fun `the allows matrix matches the Mihon model`() {
        // SHOW_ALL lists everything.
        for (rating in ContentRating.entries) {
            assertTrue(
                ContentAccess.allows(rating, ContentVisibility.SHOW_ALL)
            )
        }
        // HIDE_ADULT (the default) hides only 18+.
        assertTrue(
            ContentAccess.allows(ContentRating.SAFE, ContentVisibility.HIDE_ADULT)
        )
        assertTrue(
            ContentAccess.allows(ContentRating.MIXED, ContentVisibility.HIDE_ADULT)
        )
        assertFalse(
            ContentAccess.allows(ContentRating.ADULT, ContentVisibility.HIDE_ADULT)
        )
        // HIDE_MIXED_AND_ADULT lists only Safe.
        assertTrue(
            ContentAccess.allows(
                ContentRating.SAFE, ContentVisibility.HIDE_MIXED_AND_ADULT
            )
        )
        assertFalse(
            ContentAccess.allows(
                ContentRating.MIXED, ContentVisibility.HIDE_MIXED_AND_ADULT
            )
        )
        assertFalse(
            ContentAccess.allows(
                ContentRating.ADULT, ContentVisibility.HIDE_MIXED_AND_ADULT
            )
        )
    }

    @Test
    fun `a package is listed when any of its sources is`() {
        val mixedPackage = listOf(ContentRating.SAFE, ContentRating.ADULT)
        assertTrue(
            ContentAccess.anyAllowed(mixedPackage, ContentVisibility.HIDE_ADULT)
        )
        val adultOnly = listOf(ContentRating.ADULT, ContentRating.ADULT)
        assertFalse(
            ContentAccess.anyAllowed(adultOnly, ContentVisibility.HIDE_ADULT)
        )
        assertTrue(
            ContentAccess.anyAllowed(adultOnly, ContentVisibility.SHOW_ALL)
        )
        assertFalse(
            ContentAccess.anyAllowed(emptyList(), ContentVisibility.SHOW_ALL)
        )
    }

    @Test
    fun `the badge is the strongest rating still visible`() {
        // Safe never badges.
        assertNull(ContentRating.SAFE.badge)
        assertEquals("Mixed", ContentRating.MIXED.badge)
        assertEquals("18+", ContentRating.ADULT.badge)

        assertEquals(
            "18+",
            ContentAccess.visibleBadge(
                listOf(ContentRating.SAFE, ContentRating.ADULT),
                ContentVisibility.SHOW_ALL
            )
        )
        // Under hide-18+, an adult+safe package's visible
        // strongest is Safe → no badge.
        assertNull(
            ContentAccess.visibleBadge(
                listOf(ContentRating.SAFE, ContentRating.ADULT),
                ContentVisibility.HIDE_ADULT
            )
        )
        // A mixed source listed under the default wears Mixed.
        assertEquals(
            "Mixed",
            ContentAccess.visibleBadge(
                listOf(ContentRating.MIXED),
                ContentVisibility.HIDE_ADULT
            )
        )
        assertNull(
            ContentAccess.visibleBadge(
                emptyList(), ContentVisibility.SHOW_ALL
            )
        )
    }

    @Test
    fun `config ratings and kinds read through the access object`() {
        val manga = MangaFixture.config()
        assertEquals(ContentRating.MIXED, ContentAccess.ratingOf(manga))
        assertEquals(MediaKind.MANGA, ContentAccess.mediaKindOf(manga))
        assertTrue(ContentAccess.isManga(manga))

        val text = FixtureSite.config()
        assertEquals(ContentRating.SAFE, ContentAccess.ratingOf(text))
        assertFalse(ContentAccess.isManga(text))
    }

    @Test
    fun `the preference defaults to hide adult and round-trips`() = runBlocking {
        val prefs = SourcePrefs(FakePreferencesStore())
        assertEquals(
            ContentVisibility.HIDE_ADULT,
            prefs.contentVisibility.first()
        )
        assertEquals(
            ContentVisibility.HIDE_ADULT, ContentVisibility.DEFAULT
        )
        prefs.setContentVisibility(ContentVisibility.SHOW_ALL)
        assertEquals(
            ContentVisibility.SHOW_ALL,
            prefs.contentVisibility.first()
        )
        prefs.setContentVisibility(ContentVisibility.HIDE_MIXED_AND_ADULT)
        assertEquals(
            ContentVisibility.HIDE_MIXED_AND_ADULT,
            prefs.contentVisibility.first()
        )
        // Unknown stored names fall back to the default.
        assertEquals(
            ContentVisibility.HIDE_ADULT,
            ContentVisibility.fromName("NOPE")
        )
        assertEquals(
            ContentVisibility.HIDE_ADULT,
            ContentVisibility.fromName(null)
        )
    }
}
