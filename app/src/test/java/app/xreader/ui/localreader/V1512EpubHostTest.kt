package app.xreader.ui.localreader

import android.app.Application
import android.graphics.PointF
import android.view.Menu
import android.view.MenuItem
import android.view.View
import app.xreader.fixtures.D3EpubFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.TapEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenu

/**
 * v1.5.12 gate — FRONT 3, EPUB half (E1–E4).
 *
 * E1: the drag-advance road is DELETED. The negative test the
 * spec demands — a slight vertical drag in scroll mode NEVER
 * produces a goForward/goBackward — is driven through the
 * REAL production input path that remains: [EpubReaderInputListener],
 * the one listener EpubBody registers on the fragment. Its
 * onDrag is fed real [DragEvent]s (slight, decisive, at a
 * boundary, on a plate shape) and must return false and turn
 * NOTHING, in scroll mode and paginated mode alike. The
 * wiring census then proves at source level that no other
 * drag road survives: no touch container, no
 * dispatchTouchEvent verdict, no drag controller class.
 *
 * The turn test drives 20 consecutive tap turns through the
 * same listener against a fake turner and asserts all 20
 * land; the boundary soak crosses 50 simulated chapter
 * boundaries (drag barrages at each) and asserts the turn
 * count never moves and the listener registration census in
 * production stays exactly one (keyed on the navigator, not
 * on chapters — crossings cannot accumulate registrations).
 *
 * E3: the native ActionMode callback builds the four-item
 * menu and routes taps; E2: the fresh-book default is scroll;
 * E4: MainActivity installs the dummy factory before
 * super.onCreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1512EpubHostTest {

    @get:Rule val temp = TemporaryFolder()

    /** A fake page turner: a 20-page paginated book. */
    private class FakeTurner(
        var sizePx: Pair<Int, Int>? = 1080 to 2000
    ) : EpubPageTurner {
        var forwardCalls = 0
        var backwardCalls = 0
        var position = 0

        override fun goForward(): Boolean {
            forwardCalls++
            if (position >= 19) return false
            position++
            return true
        }

        override fun goBackward(): Boolean {
            backwardCalls++
            if (position <= 0) return false
            position--
            return true
        }

        override fun viewSizePx(): Pair<Int, Int>? = sizePx
    }

    private fun listenerFor(
        turner: FakeTurner,
        toggles: () -> Unit = {}
    ) = EpubReaderInputListener(
        turner = turner,
        tapPreset = { TapLayoutPreset.MOON_DEFAULT },
        onToggleControls = toggles
    )

    private fun drag(
        listener: EpubReaderInputListener,
        offsetX: Float,
        offsetY: Float
    ): List<Boolean> = listOf(
        listener.onDrag(
            DragEvent(DragEvent.Type.Start, PointF(100f, 900f), PointF(0f, 0f))
        ),
        listener.onDrag(
            DragEvent(
                DragEvent.Type.Move, PointF(100f, 900f),
                PointF(offsetX / 2f, offsetY / 2f)
            )
        ),
        listener.onDrag(
            DragEvent(
                DragEvent.Type.End, PointF(100f, 900f),
                PointF(offsetX, offsetY)
            )
        )
    )

    // ── E1: the negative tests ───────────────────────────────

    @Test
    fun `slight vertical drag in scroll mode never turns`() {
        val turner = FakeTurner()
        val listener = listenerFor(turner)
        // A slight reading nudge: ~40 px of upward travel —
        // the gesture v1.5.11's 48 dp road converted into a
        // chapter jump.
        val results = drag(listener, offsetX = 0f, offsetY = -40f)
        assertEquals(listOf(false, false, false), results)
        assertEquals(0, turner.forwardCalls)
        assertEquals(0, turner.backwardCalls)
    }

    @Test
    fun `decisive drags at a boundary never turn either`() {
        val turner = FakeTurner()
        val listener = listenerFor(turner)
        // A full-height fling, up and down, on a dead
        // (non-overflowing) plate shape: the old native road's
        // exact trigger. Drags belong to the engine now.
        assertEquals(
            listOf(false, false, false),
            drag(listener, offsetX = 0f, offsetY = -900f)
        )
        assertEquals(
            listOf(false, false, false),
            drag(listener, offsetX = 0f, offsetY = 900f)
        )
        assertEquals(
            listOf(false, false, false),
            drag(listener, offsetX = -700f, offsetY = -120f)
        )
        assertEquals(0, turner.forwardCalls)
        assertEquals(0, turner.backwardCalls)
        assertEquals(0, turner.position)
    }

    @Test
    fun `boundary soak - 50 crossings accumulate no turns and no listeners`() {
        val turner = FakeTurner()
        val listener = listenerFor(turner)
        // Simulate 50 chapter-boundary crossings in scroll
        // mode: at each boundary the reader struggles with
        // repeated slight + decisive drags (the v1.5.11 ANR /
        // chapter-jump pattern). The drag road is gone, so the
        // turn counters must stay at zero throughout, and the
        // ONE listener instance serves every crossing —
        // crossings register nothing new.
        repeat(50) {
            drag(listener, offsetX = 0f, offsetY = -40f)
            drag(listener, offsetX = 0f, offsetY = -500f)
            drag(listener, offsetX = 0f, offsetY = 300f)
        }
        assertEquals(0, turner.forwardCalls)
        assertEquals(0, turner.backwardCalls)
        // Production census: exactly one addInputListener CALL,
        // in a DisposableEffect keyed on the navigator instance —
        // chapter crossings (locator changes inside the one
        // fragment) cannot re-register anything.
        val reader = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/EpubBookReader.kt"
        ).readText()
        assertEquals(
            1,
            reader.split(".addInputListener(").size - 1
        )
        assertTrue(
            "the listener effect is keyed on the navigator, not a chapter",
            reader.contains("DisposableEffect(navigator, registerInputListener)")
        )
        assertTrue(
            "the locator collector is one LaunchedEffect(navigator)",
            reader.contains("LaunchedEffect(navigator) {\n        navigator?.currentLocator")
        )
    }

    // ── Turns: the paginated road that remains ───────────────

    @Test
    fun `20 consecutive tap turns in paginated mode all land`() {
        val turner = FakeTurner()
        val listener = listenerFor(turner)
        // Right-third taps (MOON_DEFAULT TURN_NEXT) at
        // x = 0.9 × width.
        repeat(20) {
            assertTrue(
                listener.onTap(TapEvent(PointF(972f, 1000f)))
            )
        }
        assertEquals(20, turner.forwardCalls)
        assertEquals(0, turner.backwardCalls)
        // 19 real moves (the book has 20 pages); the 20th tap
        // still dispatches a turn attempt, whose verdict the
        // navigator owns — exactly the shipped tap semantics.
        assertEquals(19, turner.position)
        // And back: left-third taps retreat.
        repeat(3) {
            assertTrue(listener.onTap(TapEvent(PointF(50f, 1000f))))
        }
        assertEquals(3, turner.backwardCalls)
        assertEquals(16, turner.position)
    }

    @Test
    fun `centre tap toggles chrome and turns nothing`() {
        val turner = FakeTurner()
        var toggles = 0
        val listener = listenerFor(turner) { toggles++ }
        assertTrue(listener.onTap(TapEvent(PointF(540f, 1000f))))
        assertEquals(1, toggles)
        assertEquals(0, turner.forwardCalls)
        assertEquals(0, turner.backwardCalls)
    }

    // ── E1/E3/E4: the wiring census ──────────────────────────

    @Test
    fun `drag road classes and poll machinery are gone from production`() {
        val controllerFile = sourceFileOrNull(
            "app/src/main/java/app/xreader/ui/localreader/" +
                "EpubDragAdvanceController.kt"
        )
        assertTrue(
            "EpubDragAdvanceController.kt must not exist",
            controllerFile == null
        )
        val reader = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/EpubBookReader.kt"
        ).readText()
        assertFalse(reader.contains("class EpubTouchContainer"))
        assertFalse(reader.contains("override fun dispatchTouchEvent"))
        assertFalse(reader.contains("dragAdvanceController"))
        assertFalse(reader.contains("selectionPollArmedUntilMs"))
        assertFalse(reader.contains("SELECTION_POLL_INTERVAL_MS"))
        assertFalse(reader.contains("shouldPollSelection"))
        val selection = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/EpubSelection.kt"
        ).readText()
        assertFalse(selection.contains("SELECTION_POLL_INTERVAL_MS"))
        assertFalse(selection.contains("shouldPollSelection"))
        // The seam the drag controller consumed is trimmed too.
        val seam = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/EpubNavigatorSeam.kt"
        ).readText()
        assertFalse(seam.contains("fun currentProgression"))
        assertFalse(seam.contains("class EpubDragAdvanceController"))
    }

    @Test
    fun `native selection callback is wired into the fragment configuration`() {
        val reader = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/EpubBookReader.kt"
        ).readText()
        assertTrue(
            reader.contains("this.selectionActionModeCallback")
        )
        assertTrue(reader.contains("EpubSelectionActionModeCallback"))
        // No Compose selection popup renders for a live
        // selection any more.
        assertFalse(
            reader.contains("SelectionActionRow(")
        )
    }

    @Test
    fun `main activity installs the dummy factory before super onCreate`() {
        val activity = sourceFile(
            "app/src/main/java/app/xreader/MainActivity.kt"
        ).readText()
        val dummyAt = activity.indexOf("createDummyFactory()")
        val superAt = activity.indexOf("super.onCreate(savedInstanceState)")
        assertTrue("dummy factory is installed", dummyAt >= 0)
        assertTrue(
            "the dummy factory lands BEFORE super.onCreate " +
                "(restoration runs inside it)",
            dummyAt in 0 until superAt
        )
    }

    // ── E3: the native callback itself ───────────────────────

    private class FakeActionMode : android.view.ActionMode() {
        var finished = false
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun setCustomView(view: View?) = Unit
        override fun getCustomView(): View? = null
        override fun getMenu(): Menu = RoboMenu()
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun invalidate() = Unit
        override fun finish() {
            finished = true
        }
        override fun getMenuInflater(): android.view.MenuInflater =
            throw UnsupportedOperationException("fake")
    }

    @Test
    fun `action mode callback builds the four item menu and routes taps`() {
        val tapped = mutableListOf<EpubSelectionMenuAction>()
        val callback = EpubSelectionActionModeCallback { tapped += it }
        val menu = RoboMenu()
        val mode = FakeActionMode()
        assertTrue(callback.onCreateActionMode(mode, menu))
        assertEquals(
            listOf("Copy", "Highlight", "Note", "Dictionary"),
            (0 until menu.size()).map { menu.getItem(it).title.toString() }
        )
        // A tap on Highlight routes the action and finishes.
        val highlightItem: MenuItem = menu.getItem(1)
        assertEquals(
            EpubSelectionMenuAction.HIGHLIGHT.itemId, highlightItem.itemId
        )
        assertTrue(callback.onActionItemClicked(mode, highlightItem))
        assertEquals(listOf(EpubSelectionMenuAction.HIGHLIGHT), tapped)
        assertTrue(mode.finished)
        // An unknown item is not ours.
        val unknown: MenuItem = RoboMenu().add(0, 424_242, 0, "Nope")
        assertFalse(callback.onActionItemClicked(FakeActionMode(), unknown))
        assertEquals(1, tapped.size)
    }

    // ── E2: the default mode ─────────────────────────────────

    @Test
    fun `fresh epub settings default to smooth scroll`() {
        assertTrue(
            "a fresh book opens in SMOOTH SCROLL (owner Q1)",
            EpubBookSettings().scroll
        )
        // Paginated remains a selectable, persistable choice —
        // the setting itself is untouched, only the default
        // was ever in question (V158EpubScrollDefaultTest pins
        // the stored-value roads).
        assertFalse(EpubBookSettings(scroll = false).scroll)
    }

    // ── Fixture survival (re-homed from the deleted D1 test) ──

    @Test
    fun `d3 fixture spine shapes still open through the real session`() =
        runBlocking {
            assertEquals(
                D3EpubFixture.SpineKind.FITTED_IMAGE,
                D3EpubFixture.SPINE[0].kind
            )
            assertEquals(
                D3EpubFixture.SpineKind.OVERFLOWING_TEXT,
                D3EpubFixture.SPINE[3].kind
            )
            val root = temp.newFolder("files")
            val session = D3EpubFixture.openSession(
                root, D3EpubFixture.writeBook(root)
            )
            try {
                assertEquals(
                    D3EpubFixture.SPINE.size, session.sectionCount
                )
            } finally {
                session.close()
            }
        }

    private fun sourceFile(path: String): File =
        sourceFileOrNull(path) ?: error("cannot locate $path")

    private fun sourceFileOrNull(path: String): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, path)
            if (candidate.isFile) return candidate
            val stripped = File(base, path.removePrefix("app/"))
            if (stripped.isFile) return stripped
            dir = base.parentFile
        }
        return null
    }
}
