package app.xreader.data.extension

import app.xreader.data.extension.MetadataFallback.Candidate
import app.xreader.data.extension.MetadataFallback.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R12 foundation: the metadata fallback chain's precedence rules. */
class MetadataFallbackTest {

    @Test
    fun `own source beats other source beats file`() {
        val resolved = MetadataFallback.resolve(
            listOf(
                Candidate("from file", Origin.FILE),
                Candidate("from other", Origin.OTHER_SOURCE_EXACT),
                Candidate("from own", Origin.OWN_SOURCE)
            )
        )
        assertEquals("from own", resolved?.value)
        assertEquals(Origin.OWN_SOURCE, resolved?.origin)
    }

    @Test
    fun `blank never wins at any priority`() {
        val resolved = MetadataFallback.resolve(
            listOf(
                Candidate("   ", Origin.OWN_SOURCE),
                Candidate("real value", Origin.FILE)
            )
        )
        assertEquals("real value", resolved?.value)
        assertNull(
            MetadataFallback.resolve(listOf(Candidate(null, Origin.OWN_SOURCE)))
        )
    }

    @Test
    fun `user locked value wins over everything`() {
        val resolved = MetadataFallback.resolve(
            listOf(
                Candidate("official synopsis", Origin.OWN_SOURCE),
                Candidate("my synopsis", Origin.INFERRED, userLocked = true)
            )
        )
        assertEquals("my synopsis", resolved?.value)
        assertTrue(resolved!!.userLocked)
    }

    @Test
    fun `locked stored field is never replaced`() {
        val stored = Candidate("mine", Origin.USER_EDIT, userLocked = true)
        val resolved = MetadataFallback.resolve(
            listOf(Candidate("theirs", Origin.OWN_SOURCE))
        )
        assertFalse(MetadataFallback.shouldReplace(stored, resolved))
    }

    @Test
    fun `blank stored field is filled by any origin`() {
        val stored = Candidate("", Origin.OWN_SOURCE)
        val resolved = MetadataFallback.resolve(
            listOf(Candidate("filled", Origin.INFERRED))
        )
        assertTrue(MetadataFallback.shouldReplace(stored, resolved))
    }

    @Test
    fun `lower origin does not replace a higher stored value`() {
        val stored = Candidate("own", Origin.OWN_SOURCE)
        val resolved = MetadataFallback.resolve(
            listOf(Candidate("file guess", Origin.FILE))
        )
        assertFalse(MetadataFallback.shouldReplace(stored, resolved))
    }
}
