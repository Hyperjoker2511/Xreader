package app.xreader.komikku.viewer

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.widget.ImageView
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.model.ViewerChapters
import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.viewer.PagerTurnDecider.Decision
import app.xreader.komikku.viewer.pager.PagerConfig
import app.xreader.komikku.viewer.pager.PagerDirection
import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.5.12 gate — FRONT 2 (manga) gesture proofs (M1 + M2,
 * the v1.5.11 Lane-1 convictions):
 *
 *  - [PagerTurnDecider]: the pager's Recycler-level turn
 *    decision in its pure form — a fit-scale short / diagonal
 *    swipe (the shape the page's SSIV latched as a no-op pan
 *    on device) decides TURN; a genuinely zoomed page keeps
 *    its pans until its edge, then the same gesture turns;
 *    cross-axis drags and pinches never turn.
 *  - Tap routing: a tap delivered through
 *    [ReaderPageImageView.onViewClicked] reaches the
 *    viewer's tap-zone handling — proven end-to-end on a
 *    real [DonorReaderView]: a centre tap on the page image
 *    (an animated page, the plain-ImageView creation site)
 *    fires the listener's menu action, which pre-v1.5.12 was
 *    unreachable because onViewClicked was assigned nowhere.
 */
class V1512PagerTurnDeciderTest {

    private fun decider(
        horizontal: Boolean = true,
        forwardSign: Float = -1f,
        slop: Float = 24f
    ) = PagerTurnDecider(
        touchSlopPx = slop, horizontal = horizontal, forwardSign = forwardSign
    )

    @Test
    fun `fit scale short swipe turns next`() {
        val d = decider()
        d.onDown(500f, 500f)
        // 30px left, just past the 24px slop, slightly diagonal.
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(470f, 512f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `fit scale diagonal swipe turns - the device no-op shape`() {
        val d = decider()
        d.onDown(500f, 500f)
        // The convicted shape: the first threshold-crossing
        // move is diagonal (axis 40, cross 30) — the SSIV
        // latched isPanning and the page never moved.
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(460f, 530f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `fit scale rightward swipe turns prev`() {
        val d = decider()
        d.onDown(500f, 500f)
        assertEquals(
            Decision.TURN_PREV,
            d.onMove(545f, 495f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `below slop decides nothing`() {
        val d = decider()
        d.onDown(500f, 500f)
        assertEquals(
            Decision.UNDECIDED,
            d.onMove(490f, 505f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `zoomed page pan belongs to the page until its edge then turns`() {
        val d = decider()
        d.onDown(500f, 500f)
        // Zoomed, image can still pan toward next: the swipe
        // is the page's pan, NOT a turn…
        assertEquals(
            Decision.PASS_TO_PAGE,
            d.onMove(450f, 505f, canPanNext = true, canPanPrev = false)
        )
        // …but the pass does not latch: later in the SAME
        // gesture the image reaches its edge (canPanNext now
        // false) and the turn proceeds — the donor's
        // pan-until-edge behaviour.
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(420f, 508f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `mostly cross-axis drag never turns`() {
        val d = decider()
        d.onDown(500f, 500f)
        assertEquals(
            Decision.PASS_TO_PAGE,
            d.onMove(515f, 580f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `pinch never turns`() {
        val d = decider()
        d.onDown(500f, 500f)
        d.onMultiTouch()
        assertEquals(
            Decision.PASS_TO_PAGE,
            d.onMove(400f, 500f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `a fired turn latches for the rest of the gesture`() {
        val d = decider()
        d.onDown(500f, 500f)
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(460f, 500f, canPanNext = false, canPanPrev = false)
        )
        // Finger reverses: still the same turn, never a
        // mid-gesture second turn the other way.
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(560f, 500f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `rtl pager - rightward swipe is toward next`() {
        // RTL: items stack leftwards; forwardSign = +1, so a
        // positive (rightward) axis delta reads toward NEXT.
        val d = decider(forwardSign = 1f)
        d.onDown(500f, 500f)
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(545f, 500f, canPanNext = false, canPanPrev = false)
        )
    }

    @Test
    fun `vertical pager - upward swipe turns next`() {
        val d = decider(horizontal = false, forwardSign = -1f)
        d.onDown(500f, 500f)
        assertEquals(
            Decision.TURN_NEXT,
            d.onMove(505f, 455f, canPanNext = false, canPanPrev = false)
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1512TapRoutingTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** A 1x1 GIF: enough for the animated-page road (the
     * plain-ImageView creation site of ReaderPageImageView). */
    private val gifBytes = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(),
        0xff.toByte(), 0xff.toByte(), 0x21, 0xf9.toByte(), 0x04,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00,
        0x3b
    )

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Poll until [ready] — the page image's prepare runs on
     * a real IO thread and lands back on the main looper. */
    private fun awaitTrue(what: String, ready: () -> Boolean) {
        repeat(200) {
            idleMain()
            if (ready()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `page image click reports the tap point fractions`() {
        val view = ReaderPageImageView(context)
        view.isWebtoon = false
        val taps = mutableListOf<Pair<Float, Float>>()
        view.onViewClicked = { fx, fy -> taps += fx to fy }
        view.setImage(
            openStream = { gifBytes.inputStream() },
            coilModel = null,
            config = ReaderPageImageView.Config(zoomDuration = 300)
        )
        awaitTrue("the animated page view to be created") {
            view.childCount > 0
        }
        val inner = view.getChildAt(0) as ImageView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                1000, android.view.View.MeasureSpec.EXACTLY
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                2000, android.view.View.MeasureSpec.EXACTLY
            )
        )
        view.layout(0, 0, 1000, 2000)
        idleMain()
        // The tap: DOWN lands at (100, 500) — the tracker
        // remembers it non-consumingly — then the click fires.
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 500f, 0)
        inner.dispatchTouchEvent(down)
        down.recycle()
        assertTrue("the inner image view must be clickable", inner.performClick())
        assertEquals(1, taps.size)
        assertEquals(0.1f, taps[0].first, 0.001f)
        assertEquals(0.25f, taps[0].second, 0.001f)
    }

    @Test
    fun `donor viewer - centre tap on the page image reaches the menu zone`() {
        val prefs = ReaderPreferences(InMemoryKomikkuStore())
        // Chapter-transition cards are ComposeViews; a bare
        // Robolectric view tree has no window recomposer for
        // them. The pref is a real user setting — turning
        // transitions off keeps this proof on the page-item
        // road it claims (tap → zone), nothing else.
        prefs.alwaysShowChapterTransition().set(false)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val config = PagerConfig(PagerDirection.L2R, scope, prefs)
        val chapter = ReaderChapter(chapterId = 1L, title = "Chapter 1")
        val pages = (0 until 3).map { i ->
            ReaderPage(i, url = "/read/p${i + 1}").also { page ->
                page.stream = { gifBytes.inputStream() }
                page.status = Page.State.Ready
            }
        }
        pages.forEach { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(pages)

        var menuTaps = 0
        val view = DonorReaderView(context)
        view.listener = object : DonorReaderView.Listener {
            override fun onCurrentPageChanged(pageIndex: Int) {}
            override fun onMenuTap() {
                menuTaps++
            }
            override fun onChapterCross(next: Boolean) {}
            override fun onTransitionVisible(next: Boolean) {}
            override fun onLongPressPage(page: ReaderPage) {}
            override fun onNearEnd() {}
            override fun imageFileFor(page: ReaderPage): File? = null
        }
        view.submit(
            ViewerChapters(null, chapter, null),
            ReadingMode.LEFT_TO_RIGHT,
            config,
            null,
            initialPage = 0
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                1000, android.view.View.MeasureSpec.EXACTLY
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                2000, android.view.View.MeasureSpec.EXACTLY
            )
        )
        view.layout(0, 0, 1000, 2000)
        // The first page's animated image view must appear
        // (holder bind → Ready → showImage → setImage → the
        // plain-ImageView creation site).
        awaitTrue("the first page's image view to be created") {
            findImageView(view) != null
        }
        val image = findImageView(view)!!
        // Centre of the page: the MENU zone of the default
        // tap navigation.
        val down = MotionEvent.obtain(
            0, 0, MotionEvent.ACTION_DOWN,
            image.width / 2f, image.height / 2f, 0
        )
        image.dispatchTouchEvent(down)
        down.recycle()
        assertTrue(image.performClick())
        idleMain()
        assertEquals(
            "the centre tap must travel page click → onViewClicked" +
                " → tap-zone handling → the menu action (M1)",
            1, menuTaps
        )
    }

    /** Depth-first search for the page's animated ImageView. */
    private fun findImageView(root: android.view.View): ImageView? {
        if (root is ImageView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findImageView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
