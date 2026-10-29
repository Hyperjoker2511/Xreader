package app.xreader.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.4 (D3): the Library empty-state gate. In v1.0.3 this decision
 * was an untested conjunction inside the composable reading a
 * combined-state `loading` flag; when that flag wedged, the chrome
 * rendered over a permanently empty list and "No books yet" never
 * appeared. The predicate is pure now and pinned here.
 */
class LibraryEmptyStateTest {

    @Test
    fun `loaded with no books and no candidates shows the empty state`() {
        assertTrue(LibraryEmptyState.show(loading = false, bookCount = 0, candidateCount = 0))
    }

    @Test
    fun `still loading never shows the empty state`() {
        assertFalse(LibraryEmptyState.show(loading = true, bookCount = 0, candidateCount = 0))
    }

    @Test
    fun `any book suppresses the empty state`() {
        assertFalse(LibraryEmptyState.show(loading = false, bookCount = 1, candidateCount = 0))
        assertFalse(LibraryEmptyState.show(loading = false, bookCount = 42, candidateCount = 0))
    }

    @Test
    fun `staged import candidates suppress the empty state`() {
        // A first-run user mid-import has no books yet, but the
        // import cards are the screen's content — not the empty state.
        assertFalse(LibraryEmptyState.show(loading = false, bookCount = 0, candidateCount = 1))
        assertFalse(LibraryEmptyState.show(loading = false, bookCount = 0, candidateCount = 3))
    }

    @Test
    fun `loading wins over books and candidates`() {
        assertFalse(LibraryEmptyState.show(loading = true, bookCount = 5, candidateCount = 2))
    }
}
