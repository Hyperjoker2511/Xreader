/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * v1.5.7 (Komikku full): the donor reader's View host, adapted
 * from Komikku's ui/reader/viewer (PagerViewer + WebtoonViewer +
 * their adapters/holders). The donor's item flow is reproduced:
 * a Prev transition item, the chapter's pages (with dual-page
 * split InsertPages and double-page spreads), and a Next
 * transition item; holders bind pages through the chapter's
 * PageLoader and render them in ReaderPageImageView (SSIV).
 * Chapter crossing fires when the reader settles on a
 * transition item, as in the donor.
 *
 * Named adaptations (§4(b)):
 *  - The donor's custom Pager widget (a ViewPager) is replaced
 *    by a RecyclerView + PagerSnapHelper; the webtoon viewer is
 *    a RecyclerView + LinearLayoutManager, as the donor's is.
 *    Item flow, holders, gestures and transitions are the
 *    donor's; the paging widget is the platform equivalent.
 *  - The donor WebtoonFrame's pinch/double-tap zoom of the
 *    whole strip frame is reproduced on the RecyclerView, and
 *    the KMK webtoon scale type applies the donor's own
 *    recycler scaleTo computation (WebtoonViewer.kt:181-206).
 *  - Double-page spreads bind two page views side by side
 *    instead of the donor's byte-merge into one view
 *    (PagerPageHolder.mergePages); pairing/shift/invert rules
 *    are the donor's.
 */
package app.xreader.komikku.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.xreader.komikku.model.ChapterTransition
import app.xreader.komikku.model.InsertPage
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.presentation.ChapterTransitionCard
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.viewer.pager.PagerConfig
import app.xreader.komikku.viewer.webtoon.WebtoonConfig
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One item in the reader's flow (donor ReaderItem). */
sealed interface DonorItem {
    data class TransitionItem(val transition: ChapterTransition) : DonorItem
    data class PageItem(
        val page: ReaderPage,
        val half: Int = -1,
        val secondPage: ReaderPage? = null
    ) : DonorItem
}

class DonorReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onCurrentPageChanged(pageIndex: Int)
        fun onMenuTap()
        fun onChapterCross(next: Boolean)
        fun onTransitionVisible(next: Boolean)
        fun onLongPressPage(page: ReaderPage)
        fun onNearEnd()
        /** A page's image file for the animated fallback and
         * page actions (downloaded file or page-cache entry). */
        fun imageFileFor(page: ReaderPage): File?
    }

    var listener: Listener? = null

    private val recycler = RecyclerView(context)
    private val snapHelper = PagerSnapHelper()
    private var snapAttached: Boolean = false
    private var adapter: ReaderAdapter? = null

    private var mode: ReadingMode = ReadingMode.WEBTOON
    private var pagerConfig: PagerConfig? = null
    private var webtoonConfig: WebtoonConfig? = null
    private var viewerConfig: ViewerConfig? = null
    private var isWebtoon: Boolean = true
    private var isContinuous: Boolean = true
    private var items: List<DonorItem> = emptyList()
    private var currentPosition: Int = -1
    private var userHasDragged: Boolean = false

    // Webtoon frame zoom (donor WebtoonFrame) + scale type base.
    private var frameScale: Float = 1f
    private var scaleTypeBase: Float = 1f
    // v1.5.35 (item 3): the frame's pan, as view translation
    // (the donor WebtoonRecyclerView carries it as its x/y).
    // The pivot stays at the recycler's centre — the donor
    // never moves it — and the translation is clamped to
    // the scaled bounds (see clampFrameTranslation).
    private var frameTransX: Float = 0f
    private var frameTransY: Float = 0f
    private var frameZoomAnimator: android.animation.ValueAnimator? = null
    // v1.5.38 (item 4): the donor's originalHeight
    // (WebtoonRecyclerView.onMeasure) — the recycler's full
    // height, captured once from this view's first real
    // size (the recycler is MATCH_PARENT in us, so our
    // height IS its full height). The sub-1 height
    // compensation divides it by the frame scale.
    private var frameOriginalHeight: Int = 0

    // v1.5.39 (item 3): the frame-level detectors (donor
    // WebtoonFrame) — fed from dispatchTouchEvent in HOST
    // coordinates for every touch, never from the scaled
    // child (V1538-MANGA-ZOOM-FORENSICS §6.1). Assigned by
    // installWebtoonGestures in init, before any touch can
    // arrive.
    private var tapDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    /** v1.5.39 (item 3): the donor zoom()'s isZooming —
     * true while a double-tap zoom animation runs; the
     * double-tap handler ignores a tap inside it (the
     * donor's onDoubleTapConfirmed guard). */
    private var frameZooming: Boolean = false

    // v1.5.39 (item 3): the donor Detector's zoom-drag
    // tracking (WebtoonRecyclerView.kt:324–365), run at
    // host level by trackZoomDrag — the tracked pointer is
    // the latest one down, exactly as the donor's
    // scrollPointerId.
    private var dragPointerId: Int = -1
    private var dragDownX: Float = 0f
    private var dragDownY: Float = 0f
    private var dragLastX: Float = 0f
    private var dragLastY: Float = 0f
    private var zoomDragging: Boolean = false
    private val touchSlop: Int =
        ViewConfiguration.get(context).scaledTouchSlop

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (frameOriginalHeight == 0 && h > 0) {
            frameOriginalHeight = h
        }
    }

    // Auto-scroll (donor SY engine, E-14).
    private val handler = Handler(Looper.getMainLooper())
    private var autoScrollEnabled: Boolean = false
    private var autoScrollIntervalSeconds: Float = 3f
    private var autoScrollSmooth: Boolean = true
    private val autoScrollTick = object : Runnable {
        override fun run() {
            if (!autoScrollEnabled) return
            val step = if (autoScrollSmooth) {
                (height / (autoScrollIntervalSeconds * 60f)).toInt().coerceAtLeast(2)
            } else {
                (height * 0.9f).toInt()
            }
            recycler.scrollBy(0, step)
            handler.postDelayed(
                this,
                if (autoScrollSmooth) 16L
                else (autoScrollIntervalSeconds * 1000).toLong()
            )
        }
    }

    private val transitionCrossRunnable = Runnable {
        val item = items.getOrNull(currentPosition) as? DonorItem.TransitionItem
            ?: return@Runnable
        listener?.onChapterCross(item.transition is ChapterTransition.Next)
    }

    init {
        recycler.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        recycler.setHasFixedSize(false)
        // v1.5.39 (item 3): the donor nulls the item
        // animator (WebtoonViewer.kt:106) — item change
        // animations on a zoomed strip are a second,
        // unsynchronised draw path.
        recycler.itemAnimator = null
        addView(recycler)
        installScrollTracking()
        installWebtoonGestures()
        installPagerTurnDecider()
    }

    // ------------------------------------------------------------ submit

    /**
     * Binds a loaded chapter window into the viewer — the
     * donor's Viewer.setChapters + adapter build.
     */
    fun submit(
        viewerChapters: app.xreader.komikku.model.ViewerChapters,
        mode: ReadingMode,
        pagerConfig: PagerConfig?,
        webtoonConfig: WebtoonConfig?,
        initialPage: Int
    ) {
        this.mode = mode
        this.pagerConfig = pagerConfig
        this.webtoonConfig = webtoonConfig
        this.viewerConfig = pagerConfig ?: webtoonConfig
        isWebtoon = mode.type == ReadingMode.ViewerType.Webtoon
        isContinuous = mode != ReadingMode.CONTINUOUS_VERTICAL
        frameScale = 1f
        scaleTypeBase = 1f
        frameTransX = 0f
        frameTransY = 0f
        frameZoomAnimator?.cancel()
        frameZoomAnimator = null
        frameZooming = false
        recycler.scaleX = 1f
        recycler.scaleY = 1f
        recycler.translationX = 0f
        recycler.translationY = 0f
        // v1.5.38 (item 4): a fresh chapter starts at the
        // full height again — never at a previous chapter's
        // sub-1 grown height (the donor's scaleTo state is
        // per-viewer-session; submit is our session start).
        recycler.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )

        val chapter = viewerChapters.currChapter
        val pages = chapter.pages ?: emptyList()
        items = buildItems(viewerChapters, pages)

        val layoutManager = if (isWebtoon) {
            // v1.5.35 (item 3): the donor's layout manager —
            // ¾ of the screen height as extra layout space
            // beyond the viewport (the donor's
            // scrollDistance, WebtoonViewer), so incoming
            // pages bind and load BEFORE they scroll into
            // view (see WebtoonLayoutManager).
            app.xreader.komikku.viewer.webtoon.WebtoonLayoutManager(
                context,
                (resources.displayMetrics.heightPixels * 3 / 4)
            )
        } else {
            when (mode) {
                ReadingMode.VERTICAL ->
                    LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                ReadingMode.RIGHT_TO_LEFT ->
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
                else ->
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
        }
        recycler.layoutManager = layoutManager
        if (isWebtoon) {
            if (snapAttached) {
                snapHelper.attachToRecyclerView(null)
                snapAttached = false
            }
        } else if (!snapAttached) {
            snapHelper.attachToRecyclerView(recycler)
            snapAttached = true
        }
        val newAdapter = ReaderAdapter()
        adapter = newAdapter
        recycler.adapter = newAdapter

        val startPosition = positionOfPage(initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        currentPosition = startPosition
        recycler.post {
            layoutManager.scrollToPositionWithOffset(
                startPosition.coerceAtLeast(0), 0
            )
            if (isWebtoon) applyWebtoonScaleType()
        }
    }

    private fun buildItems(
        viewerChapters: app.xreader.komikku.model.ViewerChapters,
        pages: List<ReaderPage>
    ): List<DonorItem> {
        val result = mutableListOf<DonorItem>()
        val config = viewerConfig
        val showTransitions = config?.alwaysShowChapterTransition ?: true
        if (showTransitions) {
            result += DonorItem.TransitionItem(
                ChapterTransition.Prev(
                    viewerChapters.currChapter, viewerChapters.prevChapter
                )
            )
        }
        val pager = pagerConfig
        val spread = !isWebtoon && pager != null &&
            (pager.doublePages || (pager.autoDoublePages && isLandscape()))
        if (spread && pager != null) {
            result += spreadItems(pages, pager)
        } else {
            for (page in pages) {
                result += DonorItem.PageItem(page)
            }
        }
        if (showTransitions) {
            result += DonorItem.TransitionItem(
                ChapterTransition.Next(
                    viewerChapters.currChapter, viewerChapters.nextChapter
                )
            )
        }
        return result
    }

    /** Donor double-page pairing: wide pages stand alone, the
     * rest pair up; shift moves the pairing by one, invert
     * swaps within a pair. */
    private fun spreadItems(
        pages: List<ReaderPage>,
        pager: PagerConfig
    ): List<DonorItem> {
        val result = mutableListOf<DonorItem>()
        var index = 0
        if (pager.shiftDoublePage && pages.isNotEmpty()) {
            result += DonorItem.PageItem(pages[0])
            index = 1
        }
        while (index < pages.size) {
            val first = pages[index]
            val second = pages.getOrNull(index + 1)
            if (second == null || first.fullPage || second.fullPage) {
                result += DonorItem.PageItem(first)
                index += 1
            } else {
                val (a, b) = if (pager.invertDoublePages) {
                    second to first
                } else {
                    first to second
                }
                result += DonorItem.PageItem(a, secondPage = b)
                index += 2
            }
        }
        return result
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Adapter position of the item showing page [pageIndex]
     * (its first half when split). */
    fun positionOfPage(pageIndex: Int): Int {
        var fallback = -1
        items.forEachIndexed { position, item ->
            if (item is DonorItem.PageItem) {
                if (item.page.index == pageIndex && fallback < 0) fallback = position
                if (item.page.index == pageIndex && item.half <= 0) return position
                if (item.secondPage?.index == pageIndex) return position
            }
        }
        return fallback
    }

    /** The reading-order page index shown at adapter [position]
     * (transitions report the nearest page). */
    private fun pageIndexAt(position: Int): Int {
        val item = items.getOrNull(position) ?: return 0
        return when (item) {
            is DonorItem.PageItem -> item.page.index
            is DonorItem.TransitionItem ->
                if (item.transition is ChapterTransition.Next) {
                    (items.filterIsInstance<DonorItem.PageItem>()
                        .maxOfOrNull { it.page.index } ?: 0)
                } else {
                    0
                }
        }
    }

    fun seekToPage(pageIndex: Int) {
        val position = positionOfPage(pageIndex)
        if (position < 0) return
        if (isWebtoon) {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        } else {
            recycler.smoothScrollToPosition(position)
        }
    }

    /** Volume-key turn (E-08, both surfaces): +1 = next. */
    fun handleVolumeTurn(direction: Int) {
        if (isWebtoon) {
            recycler.smoothScrollBy(0, direction * (height * 0.9f).toInt())
        } else {
            val target = (currentPosition + direction)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (target != currentPosition) recycler.smoothScrollToPosition(target)
        }
    }

    fun setAutoScroll(enabled: Boolean, intervalSeconds: Float, smooth: Boolean) {
        autoScrollEnabled = enabled
        autoScrollIntervalSeconds = intervalSeconds.coerceAtLeast(0.5f)
        autoScrollSmooth = smooth
        handler.removeCallbacks(autoScrollTick)
        if (enabled) handler.post(autoScrollTick)
    }

    val isAutoScrollEnabled: Boolean get() = autoScrollEnabled

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(autoScrollTick)
        handler.removeCallbacks(transitionCrossRunnable)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ scrolling

    private fun installScrollTracking() {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userHasDragged = true
                    if (autoScrollEnabled) {
                        autoScrollEnabled = false
                        handler.removeCallbacks(autoScrollTick)
                    }
                }
                if (!isWebtoon && newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val snapView = snapHelper.findSnapView(rv.layoutManager) ?: return
                    val position = rv.layoutManager?.getPosition(snapView) ?: return
                    onSettledPosition(position)
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!isWebtoon) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION && first != currentPosition) {
                    currentPosition = first
                    val pageIndex = pageIndexAt(first)
                    listener?.onCurrentPageChanged(pageIndex)
                    checkNearEnd(pageIndex)
                }
                // Webtoon transition crossing: the Next card
                // fully on screen after real scrolling.
                val last = lm.findLastVisibleItemPosition()
                val lastItem = items.getOrNull(last)
                if (userHasDragged &&
                    lastItem is DonorItem.TransitionItem &&
                    lastItem.transition is ChapterTransition.Next &&
                    lm.findLastCompletelyVisibleItemPosition() == last
                ) {
                    currentPosition = last
                    handler.removeCallbacks(transitionCrossRunnable)
                    handler.postDelayed(transitionCrossRunnable, 600)
                } else if (lastItem !is DonorItem.TransitionItem) {
                    handler.removeCallbacks(transitionCrossRunnable)
                }
            }
        })
    }

    private fun onSettledPosition(position: Int) {
        val previous = currentPosition
        currentPosition = position
        val item = items.getOrNull(position)
        if (item is DonorItem.TransitionItem) {
            listener?.onTransitionVisible(item.transition is ChapterTransition.Next)
            if (userHasDragged) {
                handler.removeCallbacks(transitionCrossRunnable)
                handler.postDelayed(transitionCrossRunnable, 400)
            }
            return
        }
        handler.removeCallbacks(transitionCrossRunnable)
        val pageIndex = pageIndexAt(position)
        listener?.onCurrentPageChanged(pageIndex)
        checkNearEnd(pageIndex)
        // Landscape zoom on selection (donor onPageSelected).
        val holder = recycler.findViewHolderForAdapterPosition(position)
            as? PageViewHolder
        holder?.onSelected(forward = position >= previous)
    }

    private fun checkNearEnd(pageIndex: Int) {
        val total = items.filterIsInstance<DonorItem.PageItem>()
            .maxOfOrNull { it.page.index } ?: return
        if (pageIndex >= total - 1) listener?.onNearEnd()
    }

    // ------------------------------------------------------------ gestures

    private fun handleZone(
        region: ViewerNavigation.NavigationRegion,
        pageView: ReaderPageImageView?
    ) {
        when (region) {
            ViewerNavigation.NavigationRegion.MENU -> listener?.onMenuTap()
            ViewerNavigation.NavigationRegion.NEXT -> advance(forward = true, pageView)
            ViewerNavigation.NavigationRegion.PREV -> advance(forward = false, pageView)
            ViewerNavigation.NavigationRegion.LEFT -> {
                // Physical edges: in RTL the left edge moves
                // FORWARD (the donor viewer's rule).
                advance(forward = mode == ReadingMode.RIGHT_TO_LEFT, pageView)
            }
            ViewerNavigation.NavigationRegion.RIGHT -> {
                advance(forward = mode != ReadingMode.RIGHT_TO_LEFT, pageView)
            }
        }
    }

    private fun advance(forward: Boolean, pageView: ReaderPageImageView?) {
        if (isWebtoon) {
            val step = if (webtoonConfig?.continuousTappingByPage == true) {
                height
            } else {
                (height * 0.9f).toInt()
            }
            recycler.smoothScrollBy(0, if (forward) step else -step)
            return
        }
        // Pan-before-turn (donor navigateToPan): a zoomed page
        // pans across before the turn is allowed.
        val config = pagerConfig
        if (config?.navigateToPan == true && pageView != null) {
            val vertical = mode == ReadingMode.VERTICAL
            val canPan = if (vertical) {
                if (forward) pageView.canPanDown() else pageView.canPanUp()
            } else {
                val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                when {
                    forward && rtl -> pageView.canPanLeft()
                    forward -> pageView.canPanRight()
                    rtl -> pageView.canPanRight()
                    else -> pageView.canPanLeft()
                }
            }
            if (canPan) {
                if (vertical) {
                    if (forward) pageView.panDown() else pageView.panUp()
                } else {
                    val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                    when {
                        forward && rtl -> pageView.panLeft()
                        forward -> pageView.panRight()
                        rtl -> pageView.panRight()
                        else -> pageView.panLeft()
                    }
                }
                return
            }
        }
        val target = currentPosition + if (forward) 1 else -1
        if (target in items.indices) {
            recycler.smoothScrollToPosition(target)
        } else if (forward) {
            listener?.onChapterCross(next = true)
        } else {
            listener?.onChapterCross(next = false)
        }
    }

    /**
     * v1.5.12 (M2, Lane 1 — bytecode-verified conviction):
     * Recycler-level turn decisions for the PAGED modes,
     * mirroring the webtoon path's OnItemTouchListener
     * arrangement in this same file. The page's SSIV calls
     * requestDisallowInterceptTouchEvent(true) on DOWN, which
     * suppresses listener consultation for the gesture's
     * MOVEs — so on DOWN this listener (consulted before the
     * child sees the event) posts a re-arm that clears the
     * disallow flag the SSIV is about to set, and from then on
     * every MOVE is offered here first. The decision itself
     * lives in [PagerTurnDecider] (pure, gate-proven): a
     * turn-shaped swipe turns when the page cannot pan
     * further that way; a genuinely zoomed page keeps its
     * pans until its edge. On TURN the listener intercepts —
     * the SSIV gets ACTION_CANCEL, its latched isPanning
     * dies — and fires the same [advance] the tap zones use.
     * Webtoon / continuous-vertical are untouched (their
     * strip gestures already work through the webtoon
     * listener), and a gesture that does not start on a page
     * item (chapter-transition cards scroll natively) never
     * arms the decider.
     */
    private fun installPagerTurnDecider() {
        var decider: PagerTurnDecider? = null
        var gesturePageView: ReaderPageImageView? = null
        var gestureTurned = false
        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(
                rv: RecyclerView,
                e: MotionEvent
            ): Boolean {
                if (isWebtoon) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureTurned = false
                        gesturePageView = null
                        decider = null
                        val child = rv.findChildViewUnder(e.x, e.y)
                        val position = child?.let {
                            rv.getChildAdapterPosition(it)
                        } ?: RecyclerView.NO_POSITION
                        val item = items.getOrNull(position)
                        if (item is DonorItem.PageItem) {
                            val holder = rv.findViewHolderForAdapterPosition(
                                position
                            ) as? PageViewHolder
                            gesturePageView = holder?.firstPageView()
                            decider = PagerTurnDecider(
                                touchSlopPx = ViewConfiguration.get(context)
                                    .scaledTouchSlop.toFloat(),
                                horizontal = mode != ReadingMode.VERTICAL,
                                forwardSign = when (mode) {
                                    ReadingMode.RIGHT_TO_LEFT -> 1f
                                    else -> -1f
                                }
                            )
                            decider?.onDown(e.x, e.y)
                            // The SSIV is about to disallow
                            // interception for this gesture
                            // (its DOWN handling); re-arm
                            // consultation so the MOVEs are
                            // offered here. Posted: the clear
                            // must land AFTER the child's DOWN
                            // handling set the flag, and before
                            // the first MOVE is dispatched —
                            // both ride the same main queue in
                            // that order.
                            rv.post {
                                rv.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        return false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        decider?.onMultiTouch()
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val active = decider ?: return false
                        if (gestureTurned) return true
                        val view = gesturePageView
                        return when (
                            active.onMove(
                                e.x, e.y,
                                canPanNext = view?.let {
                                    pageCanPan(it, forward = true)
                                } ?: false,
                                canPanPrev = view?.let {
                                    pageCanPan(it, forward = false)
                                } ?: false
                            )
                        ) {
                            PagerTurnDecider.Decision.TURN_NEXT -> {
                                gestureTurned = true
                                advance(forward = true, view)
                                true
                            }
                            PagerTurnDecider.Decision.TURN_PREV -> {
                                gestureTurned = true
                                advance(forward = false, view)
                                true
                            }
                            else -> false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        decider?.onGestureEnd()
                        decider = null
                        return false
                    }
                    else -> return false
                }
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                // Reached only once this listener has
                // intercepted (a turn fired): the rest of the
                // gesture is swallowed — the turn already
                // happened, and nothing may scroll behind it.
                if (e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    decider?.onGestureEnd()
                    decider = null
                    gestureTurned = false
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(
                disallowIntercept: Boolean
            ) {
                // Consultation stays with the framework's own
                // bookkeeping; the decider re-arms per gesture
                // on DOWN (see above).
            }
        })
    }

    /** Whether [view]'s image can still pan toward the NEXT
     * (forward = true) or PREV page — the same edges
     * [advance]'s navigateToPan block consults, in reading
     * direction for the current mode. */
    private fun pageCanPan(view: ReaderPageImageView, forward: Boolean): Boolean {
        return if (mode == ReadingMode.VERTICAL) {
            if (forward) view.canPanDown() else view.canPanUp()
        } else {
            val rtl = mode == ReadingMode.RIGHT_TO_LEFT
            when {
                forward && rtl -> view.canPanLeft()
                forward -> view.canPanRight()
                rtl -> view.canPanRight()
                else -> view.canPanLeft()
            }
        }
    }

    /** Tap observation for pager page views: the detector only
     * OBSERVES (never consumes), so SSIV keeps its own zoom/pan
     * gestures — the donor's arrangement (click → onViewClicked,
     * zones decided by the viewer). */
    private fun observingTapListener(
        pageProvider: () -> ReaderPage?,
        pageViewProvider: () -> ReaderPageImageView?
    ): OnTouchListener {
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val config = viewerConfig ?: return false
                    val view = pageViewProvider() ?: return false
                    val w = view.width.takeIf { it > 0 } ?: return false
                    val h = view.height.takeIf { it > 0 } ?: return false
                    val region = config.navigator.getAction(
                        KPointF(e.x / w, e.y / h)
                    )
                    handleZone(region, pageViewProvider())
                    return false
                }

                override fun onLongPress(e: MotionEvent) {
                    val config = viewerConfig ?: return
                    if (!config.longTapEnabled) return
                    val page = pageProvider() ?: return
                    listener?.onLongPressPage(page)
                }
            }
        )
        return OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    /** v1.5.39 (item 3): the donor WebtoonFrame's dispatch
     * contract, ported whole (WebtoonFrame.kt:50–71; spec
     * V1538-MANGA-ZOOM-FORENSICS §6.1). The detectors are
     * fed HERE — at the unscaled host, in host coordinates,
     * for EVERY touch — and the event's location is then
     * clamped into the recycler's current hit rect before
     * the children see it, so a gesture starting on the
     * black bars (outside a zoomed-out strip's shrunken hit
     * rect) still reaches the strip, clamped onto its edge:
     * recovery from anywhere. The pre-v1.5.39 child-level
     * OnItemTouchListener feeding was the trap: at the 0.5
     * rest the child's hit rect is the screen's middle
     * half, bar touches never reached the listener at all,
     * and a pinch-in starting on the bars was dead on
     * arrival. Pager modes keep the plain dispatch — the
     * frame zoom is a webtoon-only surface. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isWebtoon) {
            scaleDetector?.onTouchEvent(ev)
            tapDetector?.onTouchEvent(ev)
            trackZoomDrag(ev)
            val recyclerRect = android.graphics.Rect()
            recycler.getHitRect(recyclerRect)
            // Shrink the box to account for any rounding
            // issues (the donor's own inset).
            recyclerRect.inset(1, 1)
            if (recyclerRect.right >= recyclerRect.left &&
                recyclerRect.bottom >= recyclerRect.top
            ) {
                ev.setLocation(
                    ev.x.coerceIn(
                        recyclerRect.left.toFloat(),
                        recyclerRect.right.toFloat()
                    ),
                    ev.y.coerceIn(
                        recyclerRect.top.toFloat(),
                        recyclerRect.bottom.toFloat()
                    )
                )
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** v1.5.39 (item 3): the donor Detector's zoom-drag rule
     * (WebtoonRecyclerView.kt:324–365), run at host level:
     * while the frame is zoomed past its base, a drag of
     * the tracked pointer beyond the touch slop pans the
     * frame through the SAME clamped position function the
     * scale path uses (clampFrameTranslation); a vertical
     * drag pans the frame only where the strip itself
     * cannot scroll further (the donor's atFirstPosition /
     * atLastPosition gate) — inside the strip, vertical pan
     * rides the scroll channel. Deltas are per-event: host
     * coordinates don't shift under the gesture the way the
     * donor's child-local ones do, so per-event deltas are
     * what keep the content glued under the fingers here. */
    private fun trackZoomDrag(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointerId = ev.getPointerId(0)
                dragDownX = ev.x
                dragDownY = ev.y
                dragLastX = ev.x
                dragLastY = ev.y
                zoomDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = ev.actionIndex
                dragPointerId = ev.getPointerId(actionIndex)
                dragDownX = ev.getX(actionIndex)
                dragDownY = ev.getY(actionIndex)
                dragLastX = dragDownX
                dragLastY = dragDownY
            }
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(dragPointerId)
                if (index < 0) return
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (!zoomDragging && frameScale > scaleTypeBase) {
                    var startScroll = false
                    if (kotlin.math.abs(x - dragDownX) > touchSlop) {
                        startScroll = true
                    }
                    if (kotlin.math.abs(y - dragDownY) > touchSlop) {
                        startScroll = true
                    }
                    if (startScroll) {
                        zoomDragging = true
                        dragLastX = x
                        dragLastY = y
                    }
                }
                if (zoomDragging) {
                    val dx = x - dragLastX
                    val dy = y - dragLastY
                    dragLastX = x
                    dragLastY = y
                    frameTransX += dx
                    val atStripEdge =
                        (dy < 0f && !recycler.canScrollVertically(1)) ||
                            (dy > 0f && !recycler.canScrollVertically(-1))
                    if (atStripEdge) {
                        frameTransY += dy
                    }
                    clampFrameTranslation()
                    applyFrameTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                zoomDragging = false
                dragPointerId = -1
            }
        }
    }

    /** Webtoon frame gestures: tap zones + pinch/double-tap
     * zoom of the whole strip frame (donor WebtoonFrame). */
    private fun installWebtoonGestures() {
        tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!isWebtoon) return false
                    val config = viewerConfig ?: return false
                    if (width <= 0 || height <= 0) return false
                    val region = config.navigator.getAction(
                        KPointF(e.x / width, e.y / height)
                    )
                    handleZone(region, null)
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!isWebtoon) return false
                    val config = webtoonConfig ?: return false
                    if (!config.doubleTapZoom) return false
                    // v1.5.39 (item 3): the donor's isZooming
                    // guard (WebtoonRecyclerView
                    // .onDoubleTapConfirmed): a double-tap
                    // landing while a zoom animation runs is
                    // ignored, never stacked onto it.
                    if (frameZooming) return false
                    // v1.5.35 (item 3): the donor's
                    // double-tap is an ANIMATED zoom — out
                    // to 2× about the tap, or home to the
                    // base with the pan cleared (see
                    // animateFrameZoomTo). The shipped
                    // instant scale-set jumped the strip
                    // around whatever pivot the last pinch
                    // had left behind it.
                    // v1.5.38 (item 4): the donor's toggle
                    // test is INEQUALITY (WebtoonRecyclerView
                    // .onDoubleTapConfirmed: scaleX !=
                    // DEFAULT_RATE → home) — a double-tap
                    // from a sub-1 overview rest goes HOME
                    // to the base, not out to 2×; only a
                    // double-tap exactly at the base zooms
                    // in.
                    val target = if (frameScale != scaleTypeBase) {
                        scaleTypeBase
                    } else {
                        (scaleTypeBase * 2f)
                            .coerceAtMost(MAX_FRAME_SCALE)
                    }
                    animateFrameZoomTo(target, e.x, e.y)
                    return false
                }
            }
        )
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {
                    if (!isWebtoon) return false
                    // v1.5.35 (item 3): the donor's frame
                    // listener BEGINS the gesture (returns
                    // true). The shipped listener never
                    // overrode this — the base class's
                    // false meant the detector never
                    // formally entered the gesture, and
                    // scale reports arrived ragged.
                    frameZoomAnimator?.cancel()
                    frameZoomAnimator = null
                    frameZooming = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!isWebtoon) return false
                    val config = webtoonConfig ?: return false
                    if (!config.pinchToZoom) return false
                    var next = frameScale * detector.scaleFactor
                    val min = if (config.zoomOutDisabled) scaleTypeBase else 0.5f
                    // Donor MAX_SCALE_RATE (WebtoonRecyclerView).
                    next = next.coerceIn(min, MAX_FRAME_SCALE)
                    frameScale = next
                    // v1.5.39 (item 3): the donor's scaleTo
                    // shape, exactly — the position is only
                    // RE-CLAMPED, never translated by the
                    // gesture's focus. The v1.5.35 focus-delta
                    // translation this replaces was an
                    // invention with a 1/s gain error (it ran
                    // in inverse-transformed local coordinates
                    // and fed its own output back as the next
                    // event's base); the donor never
                    // translates by focus (V1538-MANGA-ZOOM-
                    // FORENSICS §6.2). Finger tracking rides
                    // the scroll channel and the zoom-drag
                    // rule (trackZoomDrag).
                    clampFrameTranslation()
                    applyFrameTransform()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (!isWebtoon) return
                    // v1.5.38 (item 4): the donor's release
                    // contract, verbatim in effect
                    // (WebtoonRecyclerView.onScaleEnd): the
                    // strip RESTS where the fingers leave it —
                    // including zoomed out, where the sub-1
                    // height compensation (applyFrameTransform)
                    // holds it as a full-screen overview. The
                    // ONLY animated landing is a release below
                    // the minimum rate, which zooms to the
                    // minimum with the pan run out to zero.
                    // (The v1.5.37 snap-home this replaces was
                    // an invention, and the owner rejected it:
                    // "Same as Komikku means same — we don't
                    // need to invent anything." Since onScale
                    // already coerces into [min, MAX], the
                    // below-min case is the zoomOutDisabled
                    // flip while resting zoomed out — the
                    // donor's own guard case.)
                    val config = webtoonConfig ?: return
                    val min = if (config.zoomOutDisabled) {
                        scaleTypeBase
                    } else {
                        0.5f
                    }
                    if (frameScale < min) {
                        animateFrameZoomTo(
                            min,
                            recycler.width / 2f,
                            recycler.height / 2f
                        )
                    }
                }
            }
        )
        // v1.5.39 (item 3): the child-level
        // OnItemTouchListener that used to feed these
        // detectors is DELETED — feeding lives in
        // dispatchTouchEvent now (the donor WebtoonFrame's
        // contract; see its doc for the trap it removes).
    }

    /** Donor WebtoonViewer scale-type rule (KMK): FIT leaves the
     * strip alone; a ratio type scales the recycler so the
     * strip's effective width matches the ratio when the screen
     * is wider than it. */
    private fun applyWebtoonScaleType() {
        val config = webtoonConfig ?: return
        if (!isContinuous && !config.longStripGapSmartScale) return
        val scaleType = config.webtoonScaleType
        if (scaleType == ReaderPreferences.WebtoonScaleType.FIT) {
            scaleTypeBase = 1f
            return
        }
        val currentWidth = recycler.width
        val currentHeight = recycler.height
        if (currentWidth <= 0 || currentHeight <= 0) return
        val screenRatio = currentWidth.toFloat() / currentHeight
        val desiredScale = (currentHeight * scaleType.ratio) / currentWidth
        scaleTypeBase = if (screenRatio > scaleType.ratio) desiredScale else 1f
        frameScale = scaleTypeBase
        frameTransX = 0f
        frameTransY = 0f
        // v1.5.38 (item 4): the scale type lands through
        // the same clamp + apply as every other transform —
        // the donor applies it BY CALLING scaleTo, whose
        // sub-1 branches (height growth, overview centring)
        // are exactly what a sub-1 ratio base needs to fill
        // the screen.
        clampFrameTranslation()
        applyFrameTransform()
    }

    /** v1.5.35 (item 3): apply the frame transform — scale
     * about the recycler's centre (the pivot is NEVER
     * reassigned; the donor WebtoonRecyclerView zooms the
     * same way) plus the clamped pan as translation.
     * v1.5.38 (item 4): the donor's sub-1 HEIGHT
     * COMPENSATION rides the same application
     * (WebtoonRecyclerView.scaleTo): below 1× the
     * recycler's layout height grows to
     * originalHeight / scale, so the scaled-down strip's
     * window covers the whole screen vertically — the
     * zoomed-out view is a full-screen overview that RESTS
     * there, never a narrowed column floating in black.
     * At or above 1× the height is the original height.
     * Applied on every transform application (pinch events
     * and animator frames alike), exactly as the donor's
     * scaleTo applies it per call.
     *
     * v1.5.39 (item 3): the ONE-DRAW GUARANTEE
     * (V1538-MANGA-ZOOM-FORENSICS §6.3). The pivot is set
     * EXPLICITLY to (width/2, originalHeight/2) — it no
     * longer defaults to the centre of whatever height the
     * view happens to be laid out at — and below 1× the
     * translation is T_y = (originalHeight/2)·(scale − 1)
     * (see clampFrameTranslation). Together they make the
     * presented image s·y for ANY laid-out height: while a
     * layout pass trails a pinch event, the draw that the
     * stale layout produces and the draw the settled layout
     * produces are the SAME image, so the mid-pinch ghost
     * (two anchorings alternating frame to frame) has no
     * second anchoring to show. */
    private fun applyFrameTransform() {
        if (frameOriginalHeight > 0 && recycler.width > 0) {
            recycler.pivotX = recycler.width / 2f
            recycler.pivotY = frameOriginalHeight / 2f
        }
        recycler.scaleX = frameScale
        recycler.scaleY = frameScale
        recycler.translationX = frameTransX
        recycler.translationY = frameTransY
        val original = frameOriginalHeight
        if (original > 0) {
            val targetHeight = if (frameScale < 1f) {
                (original / frameScale).toInt()
            } else {
                original
            }
            if (recycler.layoutParams.height != targetHeight) {
                recycler.layoutParams.height = targetHeight
                recycler.requestLayout()
            }
        }
    }

    /** v1.5.35 (item 3): the donor's pan clamp
     * (WebtoonRecyclerView.getPositionX/Y, in translation
     * terms): at or below the base scale there is no pan at
     * all; above it the strip may move only within its
     * oversize over the base fit — half the oversize on
     * each axis.
     * v1.5.38 (item 4): BELOW 1× the donor's own branches
     * govern (getPositionX/Y key on the absolute scale):
     * X is pinned to 0 and Y is the overview centring —
     * originalHeight/2 − halfHeight, with halfHeight the
     * GROWN height's half — which is what holds the
     * overview centred on the screen while it rests.
     * v1.5.39 (item 3): the centring is now EXPRESSED
     * against the explicit pivot (applyFrameTransform):
     * with the pivot pinned at originalHeight/2, the
     * translation that presents the donor's composed state
     * (screen = scale · y) is (originalHeight/2)·(scale−1)
     * — the same rest image the v1.5.38 formula produced
     * once layout settled, now produced at every layout
     * height, settled or not (§6.3's one-draw guarantee). */
    private fun clampFrameTranslation() {
        if (frameScale < 1f) {
            val original = frameOriginalHeight
            frameTransX = 0f
            frameTransY = if (original > 0) {
                (original / 2f) * (frameScale - 1f)
            } else {
                0f
            }
            return
        }
        if (frameScale <= scaleTypeBase) {
            frameTransX = 0f
            frameTransY = 0f
            return
        }
        val maxX = recycler.width *
            (frameScale - scaleTypeBase) / 2f
        val maxY = recycler.height *
            (frameScale - scaleTypeBase) / 2f
        frameTransX = frameTransX.coerceIn(-maxX, maxX)
        frameTransY = frameTransY.coerceIn(-maxY, maxY)
    }

    /** v1.5.35 (item 3): the donor's animated frame zoom
     * (WebtoonRecyclerView.zoom — 200 ms): the scale runs
     * to the target while the translation follows so the
     * tapped point stays put; landing on the base clears
     * the pan entirely (the donor's x = y = 0 at
     * DEFAULT_RATE — zoom-out lands flush, never small and
     * stranded mid-screen). */
    private fun animateFrameZoomTo(
        targetScale: Float,
        focusX: Float,
        focusY: Float
    ) {
        val startScale = frameScale
        val startTransX = frameTransX
        val startTransY = frameTransY
        if (startScale == targetScale) return
        frameZoomAnimator?.cancel()
        val centreX = recycler.width / 2f
        val centreY = recycler.height / 2f
        frameZoomAnimator = android.animation.ValueAnimator
            .ofFloat(0f, 1f).apply {
                duration = 200
                addUpdateListener { animator ->
                    val t = animator.animatedValue as Float
                    val scale = startScale +
                        (targetScale - startScale) * t
                    frameScale = scale
                    if (targetScale <= scaleTypeBase) {
                        frameTransX = startTransX * (1f - t)
                        frameTransY = startTransY * (1f - t)
                    } else {
                        // T = P − C − (s/s0)·(P − C − T0):
                        // the tapped content point stays
                        // under the reader's finger.
                        val ratio = scale / startScale
                        frameTransX = (focusX - centreX) -
                            ratio * (focusX - centreX - startTransX)
                        frameTransY = (focusY - centreY) -
                            ratio * (focusY - centreY - startTransY)
                    }
                    clampFrameTranslation()
                    applyFrameTransform()
                }
                addListener(
                    object :
                        android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(
                            animation: android.animation.Animator
                        ) {
                            // v1.5.39 (item 3): the donor's
                            // zoom() clears isZooming at the
                            // end of the homing.
                            frameZooming = false
                            if (targetScale <= scaleTypeBase) {
                                frameTransX = 0f
                                frameTransY = 0f
                            }
                            clampFrameTranslation()
                            applyFrameTransform()
                        }

                        override fun onAnimationCancel(
                            animation: android.animation.Animator
                        ) {
                            frameZooming = false
                        }
                    }
                )
                // v1.5.39 (item 3): the donor's zoom() sets
                // isZooming for the animation's whole run.
                frameZooming = true
                start()
            }
    }

    // ------------------------------------------------------------ adapter

    private inner class ReaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int =
            when (items[position]) {
                is DonorItem.TransitionItem -> TYPE_TRANSITION
                is DonorItem.PageItem -> TYPE_PAGE
            }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder = when (viewType) {
            TYPE_TRANSITION -> TransitionViewHolder(ComposeView(parent.context))
            else -> PageViewHolder(FrameLayout(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DonorItem.TransitionItem -> {
                    (holder as TransitionViewHolder).bind(item.transition)
                    if (item.transition is ChapterTransition.Next) {
                        listener?.onTransitionVisible(next = true)
                    }
                }
                is DonorItem.PageItem -> (holder as PageViewHolder).bind(item)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? PageViewHolder)?.recycle()
            super.onViewRecycled(holder)
        }
    }

    private inner class TransitionViewHolder(
        private val composeView: ComposeView
    ) : RecyclerView.ViewHolder(composeView) {

        init {
            composeView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        fun bind(transition: ChapterTransition) {
            val show = viewerConfig?.alwaysShowChapterTransition ?: true
            if (!show) return
            composeView.setContent {
                app.xreader.ui.theme.XreaderTheme(darkTheme = true) {
                    ChapterTransitionCard(
                        transition = transition,
                        onCross = {
                            listener?.onChapterCross(
                                transition is ChapterTransition.Next
                            )
                        }
                    )
                }
            }
        }
    }

    private inner class PageViewHolder(
        private val container: FrameLayout
    ) : RecyclerView.ViewHolder(container) {

        private var scope: CoroutineScope? = null
        private var pageViews = mutableListOf<ReaderPageImageView>()
        private var progress: ProgressBar? = null
        private var errorLayout: LinearLayout? = null
        private var boundPages: List<ReaderPage> = emptyList()

        init {
            container.layoutParams = if (isWebtoon) {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        fun bind(item: DonorItem.PageItem) {
            recycle()
            // Holders come from the pool across mode switches —
            // restate the container shape for THIS viewer kind.
            container.layoutParams = if (isWebtoon) {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            currentHalf = item.half
            val pages = listOfNotNull(item.page, item.secondPage)
            boundPages = pages
            val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = holderScope
            if (item.secondPage != null) {
                val row = LinearLayout(container.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                container.addView(row)
                pages.forEach { page ->
                    val pageView = createPageView(page, half = -1)
                    row.addView(
                        pageView,
                        LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                        )
                    )
                    pageViews += pageView
                    observePage(page, pageView, holderScope)
                }
            } else {
                val pageView = createPageView(item.page, half = item.half)
                container.addView(
                    pageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        if (isWebtoon) {
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        } else {
                            FrameLayout.LayoutParams.MATCH_PARENT
                        }
                    )
                )
                pageViews += pageView
                applyWebtoonItemHeight(pageView, item.page)
                observePage(item.page, pageView, holderScope)
            }
            if (!isWebtoon) {
                container.setOnTouchListener(
                    observingTapListener(
                        pageProvider = { boundPages.firstOrNull() },
                        pageViewProvider = { pageViews.firstOrNull() }
                    )
                )
            }
        }

        private fun createPageView(page: ReaderPage, half: Int): ReaderPageImageView {
            val view = ReaderPageImageView(container.context)
            view.isWebtoon = isWebtoon
            view.tag = page.index
            if (!isWebtoon) {
                // v1.5.12 (M1, Lane 1): the tap wiring the
                // donor viewer's arrangement assumes — the
                // page image's OWN click (the only channel
                // the consuming SSIV reports taps on) routed
                // to THIS viewer's tap-zone handling, zones
                // decided from the tap point by the mode's
                // navigator, exactly as the container's
                // observing detector computes them for the
                // events that do reach it. Pager modes only:
                // webtoon taps already arrive through the
                // recycler-level strip listener, and wiring
                // both roads would double-fire zone actions.
                view.onViewClicked = { fx, fy ->
                    val config = viewerConfig
                    if (config != null) {
                        handleZone(
                            config.navigator.getAction(KPointF(fx, fy)),
                            view
                        )
                    }
                }
            }
            return view
        }

        /** The holder's first page view (the decider's
         * pan-remaining source). */
        fun firstPageView(): ReaderPageImageView? = pageViews.firstOrNull()

        /** Reserved height from the page's recorded dimensions
         * (Finding 2's F2): no 240dp voids, no layout jumps. */
        private fun applyWebtoonItemHeight(view: View, page: ReaderPage) {
            if (!isWebtoon) return
            val config = webtoonConfig ?: return
            val parentWidth = recycler.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val margin = parentWidth * (config.sidePadding / 100f)
            val contentWidth = parentWidth - (margin * 2)
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = margin.toInt()
            params.rightMargin = margin.toInt()
            if (!isContinuous) params.bottomMargin = (15 * resources.displayMetrics.density).toInt()
            params.height = if (page.imageWidth > 0 && page.imageHeight > 0) {
                (contentWidth * page.imageHeight / page.imageWidth).toInt()
            } else {
                (contentWidth * 1.4f).toInt()
            }
            view.layoutParams = params
        }

        private fun observePage(
            page: ReaderPage,
            pageView: ReaderPageImageView,
            holderScope: CoroutineScope
        ) {
            val loader = runCatching { page.chapter.pageLoader }.getOrNull()
            holderScope.launch { loader?.loadPage(page) }
            holderScope.launch {
                page.statusFlow.collectLatest { status ->
                    when (status) {
                        is Page.State.Error -> showError(page, pageView)
                        Page.State.Ready -> showImage(page, pageView)
                        else -> {
                            if (page.stream != null) {
                                showImage(page, pageView)
                            } else {
                                showProgress(pageView)
                            }
                        }
                    }
                }
            }
        }

        private fun showProgress(pageView: ReaderPageImageView) {
            removeError()
            if (progress == null) {
                progress = ProgressBar(container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }
                container.addView(progress)
            }
            progress?.visibility = View.VISIBLE
        }

        private fun hideProgress() {
            progress?.visibility = View.GONE
        }

        private fun showError(page: ReaderPage, pageView: ReaderPageImageView) {
            hideProgress()
            pageView.visibility = View.INVISIBLE
            removeError()
            val layout = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val message = TextView(container.context).apply {
                text = "This page couldn't be loaded."
                gravity = Gravity.CENTER
            }
            val retry = Button(container.context).apply {
                text = "Retry"
                setOnClickListener {
                    removeError()
                    pageView.visibility = View.VISIBLE
                    val loader = runCatching { page.chapter.pageLoader }.getOrNull()
                    loader?.retryPage(page)
                }
            }
            layout.addView(message)
            layout.addView(retry)
            errorLayout = layout
            container.addView(layout)
        }

        private fun removeError() {
            errorLayout?.let { container.removeView(it) }
            errorLayout = null
        }

        private fun showImage(page: ReaderPage, pageView: ReaderPageImageView) {
            val stream = page.stream ?: return
            if (pageView.tag == "shown-${page.index}") return
            pageView.tag = "shown-${page.index}"
            hideProgress()
            removeError()
            pageView.visibility = View.VISIBLE
            val config = pageImageConfig(page)
            pageView.onImageLoaded = {
                val loader = runCatching { page.chapter.pageLoader }.getOrNull()
                loader?.onImageReady(page)
                if (pageView.imageWidth > 0) {
                    page.imageWidth = pageView.imageWidth
                    page.imageHeight = pageView.imageHeight
                    if (isWebtoon) applyWebtoonItemHeight(pageView, page)
                    maybeSplitWidePage(page)
                }
            }
            pageView.onImageLoadError = { error ->
                val loader = runCatching { page.chapter.pageLoader }.getOrNull()
                loader?.onImageError(
                    page, error ?: IllegalStateException("image load failed")
                )
            }
            val half = (boundItemHalf(page))
            pageView.setImage(
                openStream = stream,
                coilModel = listener?.imageFileFor(page) ?: page.url,
                config = config,
                half = half,
                rotateToFit = rotateToFitFor(page),
                rotateInvert = rotateInvertFor(),
                referer = page.referer
            )
        }

        private fun boundItemHalf(page: ReaderPage): Int {
            // The item this holder is bound to carries the half;
            // spread pages are whole pages (half -1).
            return currentHalf
        }

        private var currentHalf: Int = -1

        private fun pageImageConfig(page: ReaderPage): ReaderPageImageView.Config {
            val pager = pagerConfig
            val webtoon = webtoonConfig
            return if (isWebtoon && webtoon != null) {
                ReaderPageImageView.Config(
                    zoomDuration = webtoon.doubleTapAnimDuration,
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                    cropBorders = if (isContinuous) {
                        webtoon.imageCropBorders
                    } else {
                        webtoon.continuousCropBorders
                    },
                    zoomStartPosition = app.xreader.komikku.viewer.pager
                        .ZoomStartPosition.CENTER,
                    landscapeZoom = false,
                    disableZoomIn = false,
                    doubleTapZoom = webtoon.doubleTapZoom
                )
            } else if (pager != null) {
                ReaderPageImageView.Config(
                    zoomDuration = pager.doubleTapAnimDuration,
                    minimumScaleType = pager.imageScaleType,
                    cropBorders = pager.imageCropBorders,
                    zoomStartPosition = pager.imageZoomType,
                    landscapeZoom = pager.landscapeZoom,
                    disableZoomIn = pager.disableZoomIn,
                    doubleTapZoom = pager.doubleTapZoom,
                    landscapeZoomScaleType = pager.landscapeZoomScaleType
                )
            } else {
                ReaderPageImageView.Config(zoomDuration = 500)
            }
        }

        private fun rotateToFitFor(page: ReaderPage): Boolean {
            val config = viewerConfig ?: return false
            return config.dualPageRotateToFit
        }

        private fun rotateInvertFor(): Boolean =
            viewerConfig?.dualPageRotateToFitInvert ?: false

        /** Donor splitDoublePages: a wide page that turns out
         * to be wide splits into two half items in place. */
        private fun maybeSplitWidePage(page: ReaderPage) {
            val config = viewerConfig ?: return
            if (!config.dualPageSplit) return
            if (page.imageWidth <= page.imageHeight) return
            if (page.fullPage) return
            page.fullPage = true
            val position = items.indexOfFirst {
                it is DonorItem.PageItem && it.page == page && it.half < 0 &&
                    it.secondPage == null
            }
            if (position < 0) return
            val insert = InsertPage(page)
            val mutable = items.toMutableList()
            mutable[position] = DonorItem.PageItem(page, half = 0)
            mutable.add(position + 1, DonorItem.PageItem(insert, half = 1))
            items = mutable
            adapter?.notifyItemChanged(position)
            adapter?.notifyItemInserted(position + 1)
        }

        fun onSelected(forward: Boolean) {
            pageViews.forEach { it.onPageSelected(forward) }
        }

        fun recycle() {
            scope?.cancel()
            scope = null
            pageViews.forEach { it.recycle() }
            pageViews.clear()
            container.removeAllViews()
            container.setOnTouchListener(null)
            progress = null
            errorLayout = null
            boundPages = emptyList()
            currentHalf = -1
        }
    }

    companion object {
        private const val TYPE_TRANSITION = 0
        private const val TYPE_PAGE = 1

        /** The donor's webtoon frame ceiling
         * (WebtoonRecyclerView.MAX_SCALE_RATE). */
        private const val MAX_FRAME_SCALE = 3f
    }
}
