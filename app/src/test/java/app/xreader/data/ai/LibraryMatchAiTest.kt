package app.xreader.data.ai

import app.xreader.data.extension.AccountBook
import app.xreader.data.extension.ExistingBookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bring-my-library AI matching (v1.0.3): the pure shortlist gate
 * (the AI is only ever asked about plausible candidates) and the
 * adjudication parse (an unclear answer is NO proposal — a match is
 * never invented).
 */
class LibraryMatchAiTest {

    private val account = AccountBook(
        remoteKey = "site-1",
        title = "Martial God Asura",
        author = "Kindhearted Bee"
    )

    private val library = listOf(
        ExistingBookState(
            bookId = 7, remoteKey = null,
            title = "Martial God Asura II", author = ""
        ),
        ExistingBookState(
            bookId = 8, remoteKey = null,
            title = "Tales of Demons and Gods", author = "Mad Snail"
        ),
        ExistingBookState(
            bookId = 9, remoteKey = null,
            title = "Martial World", author = "Cocooned Cow"
        )
    )

    @Test
    fun `shortlist ranks the near-identical title first`() {
        val shortlist = FuzzyShortlist.shortlist(account, library, emptySet())
        assertTrue(shortlist.isNotEmpty())
        assertEquals(7L, shortlist.first().bookId)
    }

    @Test
    fun `shortlist excludes books the exact planner already claimed`() {
        val shortlist = FuzzyShortlist.shortlist(account, library, setOf(7L))
        assertTrue(shortlist.none { it.bookId == 7L })
    }

    @Test
    fun `unrelated books make no shortlist`() {
        val other = AccountBook(
            remoteKey = "site-2",
            title = "A Completely Different Story About Cooking",
            author = "Chef"
        )
        assertTrue(FuzzyShortlist.shortlist(other, library, emptySet()).isEmpty())
    }

    @Test
    fun `author agreement lifts the score`() {
        val withAuthor = FuzzyShortlist.score(
            "Martial God Asura", "Kindhearted Bee",
            "Martial God Asura II", "Kindhearted Bee"
        )
        val withoutAuthor = FuzzyShortlist.score(
            "Martial God Asura", "Kindhearted Bee",
            "Martial God Asura II", "Someone Else"
        )
        assertTrue(withAuthor > withoutAuthor)
    }

    @Test
    fun `parse choice reads the numbered match`() {
        val proposal = LibraryMatchAi.parseChoice(
            """{"match": 1}""", account, library
        )
        assertEquals(7L, proposal?.existing?.bookId)
    }

    @Test
    fun `parse choice treats zero, out-of-range and junk as no proposal`() {
        assertNull(LibraryMatchAi.parseChoice("""{"match": 0}""", account, library))
        assertNull(LibraryMatchAi.parseChoice("""{"match": 9}""", account, library))
        assertNull(
            LibraryMatchAi.parseChoice(
                "I am not sure about any of these", account, library
            )
        )
    }
}
