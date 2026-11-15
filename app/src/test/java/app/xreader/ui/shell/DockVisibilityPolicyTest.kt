package app.xreader.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock-visibility contract (Patch 6, the owner's
 * full-screen source rule): the dock belongs to the top-level
 * tabs; a source's catalogue and a book's page never carry it;
 * settings pages carry it only when reached from the Me tab.
 * Expected values are hardcoded literals — an independent
 * oracle, never the policy's own sets read back. */
class DockVisibilityPolicyTest {

    private val tabRoutes = listOf(
        "library", "browse", "history", "downloads", "me"
    )

    private val settingsRoutes = listOf(
        "customise", "appearance", "profile", "providers", "sync",
        "migration", "more", "updates", "labs", "about", "terms",
        "vocab", "appUpdates", "whatsNew",
        // Patch 10: the Generated art gallery is a Me destination
        // like the others (same literal-extension precedent as
        // Patch 6's vocab/appUpdates/whatsNew additions).
        "generatedArt"
    )

    @Test
    fun `every tab route shows the dock from every origin`() {
        for (route in tabRoutes) {
            for (origin in DockOrigin.entries) {
                assertTrue(
                    "$route from $origin",
                    DockVisibilityPolicy.dockVisible(route, origin)
                )
            }
        }
    }

    @Test
    fun `tab routes in the policy match the Tab enum`() {
        // The shell feeds the policy destination patterns; the
        // enum is the graph's own truth for the tab set.
        assertEquals(
            Tab.entries.map { it.route }.toSet(),
            tabRoutes.toSet()
        )
    }

    @Test
    fun `source catalogue hides the dock from every origin`() {
        for (origin in DockOrigin.entries) {
            assertFalse(
                "catalogue from $origin",
                DockVisibilityPolicy.dockVisible(
                    "source/{extId}/{sourceId}", origin
                )
            )
        }
    }

    @Test
    fun `book page hides the dock from every origin`() {
        for (origin in DockOrigin.entries) {
            assertFalse(
                "book page from $origin",
                DockVisibilityPolicy.dockVisible("book/{bookId}", origin)
            )
        }
    }

    @Test
    fun `settings routes show the dock only from the me tab`() {
        for (route in settingsRoutes) {
            assertTrue(
                "$route from Me",
                DockVisibilityPolicy.dockVisible(
                    route, DockOrigin.FROM_ME_TAB
                )
            )
            assertFalse(
                "$route from a tab root scan",
                DockVisibilityPolicy.dockVisible(
                    route, DockOrigin.TAB_ROOT
                )
            )
            assertFalse(
                "$route from elsewhere",
                DockVisibilityPolicy.dockVisible(
                    route, DockOrigin.FROM_ELSEWHERE
                )
            )
        }
    }

    @Test
    fun `customise shows the dock from me and hides it from elsewhere`() {
        // The spec's named case: the user previewing dock and
        // glass settings from Me must never judge them blind.
        assertTrue(
            DockVisibilityPolicy.dockVisible(
                "customise", DockOrigin.FROM_ME_TAB
            )
        )
        assertFalse(
            DockVisibilityPolicy.dockVisible(
                "customise", DockOrigin.FROM_ELSEWHERE
            )
        )
    }

    @Test
    fun `everything else hides the dock`() {
        // Every other real destination in the graph: source
        // management, repos, argument routes, and the immersive
        // routes (ShellChrome owns them, but the policy's own
        // answer is also "no dock").
        val hidden = listOf(
            "globalSearch", "manageSources", "addSource",
            "sourceStore", "repoBuiltIn", "opdsServers", "login",
            "addFromLink", "addList", "repo/{repoId}",
            "sourcePrefs/{extId}/{sourceId}", "extensions/{extId}",
            "opds/{serverId}", "lore/{bookId}", "tag/{slug}",
            "reader/{bookId}/{chapterIndex}",
            "local/{bookId}?target={target}",
            "ai/{bookId}/{chapterIndex}"
        )
        for (route in hidden) {
            for (origin in DockOrigin.entries) {
                assertFalse(
                    "$route from $origin",
                    DockVisibilityPolicy.dockVisible(route, origin)
                )
            }
        }
    }

    @Test
    fun `unknown and null routes hide the dock`() {
        for (origin in DockOrigin.entries) {
            assertFalse(
                DockVisibilityPolicy.dockVisible("noSuchRoute", origin)
            )
            assertFalse(DockVisibilityPolicy.dockVisible("", origin))
            assertFalse(DockVisibilityPolicy.dockVisible(null, origin))
        }
    }

    @Test
    fun `query strings are stripped before matching`() {
        // A positive match must survive a query string, and a
        // hidden pattern stays hidden with one attached.
        assertTrue(
            DockVisibilityPolicy.dockVisible(
                "library?from=widget", DockOrigin.FROM_ELSEWHERE
            )
        )
        assertFalse(
            DockVisibilityPolicy.dockVisible(
                "source/{extId}/{sourceId}?x={x}",
                DockOrigin.FROM_ME_TAB
            )
        )
    }

    @Test
    fun `origin is tab root on a tab destination`() {
        assertEquals(
            DockOrigin.TAB_ROOT,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "browse"), "browse"
            )
        )
        assertEquals(
            DockOrigin.TAB_ROOT,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("me"), "me"
            )
        )
    }

    @Test
    fun `origin is from me for a direct settings push`() {
        assertEquals(
            DockOrigin.FROM_ME_TAB,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "me", "customise"), "customise"
            )
        )
    }

    @Test
    fun `origin resolves through an intermediate settings page`() {
        // me → more → customise: the previous entry is "more",
        // not a tab — the scan must walk to the tab underneath.
        assertEquals(
            DockOrigin.FROM_ME_TAB,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "me", "more", "customise"),
                "customise"
            )
        )
    }

    @Test
    fun `origin is elsewhere when settings hang off another tab`() {
        assertEquals(
            DockOrigin.FROM_ELSEWHERE,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "appearance"), "appearance"
            )
        )
        assertEquals(
            DockOrigin.FROM_ELSEWHERE,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "browse", "more", "sync"), "sync"
            )
        )
    }

    @Test
    fun `origin scan skips nulls and finds the tab below`() {
        assertEquals(
            DockOrigin.FROM_ME_TAB,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("library", "me", null, "providers"),
                "providers"
            )
        )
    }

    @Test
    fun `origin is elsewhere with no tab underneath`() {
        assertEquals(
            DockOrigin.FROM_ELSEWHERE,
            DockVisibilityPolicy.computeDockOrigin(
                listOf("customise"), "customise"
            )
        )
        assertEquals(
            DockOrigin.FROM_ELSEWHERE,
            DockVisibilityPolicy.computeDockOrigin(
                emptyList(), "customise"
            )
        )
        assertEquals(
            DockOrigin.FROM_ELSEWHERE,
            DockVisibilityPolicy.computeDockOrigin(
                listOf(null, null), null
            )
        )
    }
}
