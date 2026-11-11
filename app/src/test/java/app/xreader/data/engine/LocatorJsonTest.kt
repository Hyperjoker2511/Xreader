package app.xreader.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.6 (§3.2): the fixed locator forms round-trip on the JVM —
 * PDF page locators, outline targets, and the fields the reader
 * reads out of a Readium locator's own JSON.
 */
class LocatorJsonTest {

    @Test fun pdfLocatorRoundTrips() {
        val json = LocatorJson.pdfLocator(page = 41, inPageFraction = 0.25f)
        assertEquals("""{"kind":"pdfPage","page":41,"inPageFraction":0.25}""", json)
        val (page, fraction) = LocatorJson.parsePdfLocator(json)!!
        assertEquals(41, page)
        assertEquals(0.25f, fraction, 0.0001f)
        assertTrue(LocatorJson.isPdfLocator(json))
    }

    @Test fun pdfLocatorRejectsOtherShapes() {
        assertNull(LocatorJson.parsePdfLocator(null))
        assertNull(LocatorJson.parsePdfLocator(""))
        assertNull(LocatorJson.parsePdfLocator("""{"kind":"legacyChapter","chapterIndex":3}"""))
        assertNull(LocatorJson.parsePdfLocator("""{"href":"chap1.xhtml"}"""))
    }

    @Test fun outlineTargetsRoundTrip() {
        assertEquals(3, LocatorJson.targetIndex(LocatorJson.epubSectionTarget(3)))
        assertEquals(7, LocatorJson.targetIndex(LocatorJson.pdfPageTarget(7)))
        assertNull(LocatorJson.targetIndex(null))
        assertNull(LocatorJson.targetIndex("""{"kind":"legacyChapter","chapterIndex":3}"""))
    }

    @Test fun readiumLocatorFieldsRead() {
        // The shape Locator.toJSON() emits (href + locations).
        val json = """
            {"href":"OEBPS/Text/ch2.xhtml","type":"application/xhtml+xml",
             "locations":{"progression":0.5,"totalProgression":0.25,
             "position":12,"fragments":["s1"]}}
        """.trimIndent()
        assertEquals("OEBPS/Text/ch2.xhtml", LocatorJson.epubLocatorHref(json))
        assertEquals(0.25f, LocatorJson.epubTotalProgression(json)!!, 0.0001f)
        assertEquals(0.5f, LocatorJson.epubProgression(json)!!, 0.0001f)
    }

    @Test fun hrefMatchingToleratesFragmentsAndBases() {
        assertTrue(hrefMatches("OEBPS/ch1.xhtml", "OEBPS/ch1.xhtml#frag"))
        assertTrue(hrefMatches("https://x/OEBPS/ch1.xhtml", "OEBPS/ch1.xhtml"))
        assertTrue(!hrefMatches("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml"))
    }
}
