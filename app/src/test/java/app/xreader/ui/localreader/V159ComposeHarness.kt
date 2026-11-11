package app.xreader.ui.localreader

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.robolectric.Robolectric
import org.robolectric.Shadows

/**
 * v1.5.9: the compose-interaction harness for the local-reader
 * device-shape tests. This tree has NO compose ui-test
 * artifact on its (offline) test classpath — no
 * `createComposeRule`, no `onNodeWithText` — so the tests
 * compose the REAL composables in a Robolectric
 * [ComponentActivity] via `setContent` and drive them through
 * the semantics tree the composition itself publishes: text
 * lookup, OnClick and ScrollBy semantics actions, and
 * bounds-in-root for "displayed". Same assertions the ui-test
 * shapes would make, one level down.
 *
 * Owner access: this compose version (1.7.6) has no
 * `LocalSemanticsOwner`, and its `AndroidComposeView` is
 * Kotlin-internal — so the owner is reached by REFLECTION on
 * the compose view's public `getSemanticsOwner()` (the class
 * and method are public in bytecode; only the Kotlin metadata
 * hides them). Test-only and contained here.
 */
internal object V159ComposeHarness {

    class Handle(
        val activity: ComponentActivity
    ) {
        val owner: SemanticsOwner
            get() = semanticsOwnerOf(activity.window.decorView)
                ?: error("no compose view in the activity hierarchy")

        private fun semanticsOwnerOf(view: View): SemanticsOwner? {
            if (view.javaClass.name ==
                "androidx.compose.ui.platform.AndroidComposeView"
            ) {
                return runCatching {
                    view.javaClass.getMethod("getSemanticsOwner")
                        .invoke(view) as? SemanticsOwner
                }.getOrNull()
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    semanticsOwnerOf(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }

        /** All semantics nodes, merged where the composition
         * merges — a tree walk from the (merged) root, the
         * traversal this compose version's owner exposes. */
        fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                out += node
                node.children.forEach(::walk)
            }
            walk(owner.rootSemanticsNode)
            return out
        }

        fun rootBounds(): androidx.compose.ui.geometry.Rect =
            owner.rootSemanticsNode.boundsInRoot
    }

    fun compose(content: @Composable () -> Unit): Handle {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        activity.setContent { content() }
        idle()
        return Handle(activity)
    }

    fun idle() {
        // Compose dispatches through the choreographer-backed
        // AndroidUiDispatcher: a plain looper idle does NOT run
        // frame callbacks under Robolectric. Advance the
        // foreground scheduler (which also advances the shadow
        // SystemClock the Choreographer schedules against) in
        // slices so frame callbacks — scroll application,
        // animations — actually execute.
        repeat(10) {
            Robolectric.getForegroundThreadScheduler()
                .advanceBy(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            Robolectric.flushForegroundThreadScheduler()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** The semantics node carrying [text] (exact match), if any. */
    fun findByText(handle: Handle, text: String): SemanticsNode? =
        handle.nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == text } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it == text } == true
        }

    /** "Displayed": semantics `boundsInRoot` is intersected
     * with ancestor clip bounds (the scroll viewport), so a
     * node scrolled out of view reports an EMPTY rect and a
     * visible one a non-empty rect inside the root — the same
     * rule ui-test's `isDisplayed` applies. */
    fun isDisplayed(handle: Handle, node: SemanticsNode): Boolean {
        val bounds = node.boundsInRoot
        return bounds.width > 0f && bounds.height > 0f
    }

    /** Clicks the node carrying [label] (text or content
     * description), climbing to the nearest OnClick ancestor —
     * the merged chip/button node. Every matching node is tried
     * (a label text under a button carries the same words as
     * the button's icon description; only one of them climbs
     * to a click). Returns false when none does. */
    fun clickByLabel(handle: Handle, label: String): Boolean {
        val matches = handle.nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == label } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it == label } == true
        }
        for (match in matches) {
            var node: SemanticsNode? = match
            while (node != null) {
                val fn = node.config.getOrNull(SemanticsActions.OnClick)?.action
                if (fn != null) {
                    val invoked = runCatching { fn.invoke() }
                        .getOrDefault(false)
                    idle()
                    if (invoked) return true
                    break
                }
                node = node.parent
            }
        }
        return false
    }

    /** Scrolls the MAIN scrollable by [dy]: the ScrollBy node
     * with the largest bounds — the sheet column, not a slider
     * (sliders also carry a ScrollBy action and must not be
     * slammed by sheet scrolling). */
    fun scrollBy(handle: Handle, dy: Float) {
        val scroller = handle.nodes()
            .filter {
                it.config.getOrNull(SemanticsActions.ScrollBy) != null
            }
            .maxByOrNull {
                it.boundsInRoot.width * it.boundsInRoot.height
            } ?: return
        val fn = scroller.config
            .getOrNull(SemanticsActions.ScrollBy)?.action ?: return
        runCatching { fn.invoke(0f, dy) }
        idle()
    }

    /**
     * Scrolls until the node carrying [text] is displayed (the
     * `performScrollTo` shape): steps toward it, re-querying
     * after every step, bounded so a failure is a failure and
     * not a hang.
     */
    fun scrollUntilDisplayed(handle: Handle, text: String, maxSteps: Int = 24): Boolean {
        repeat(maxSteps) {
            val node = findByText(handle, text) ?: return false
            if (isDisplayed(handle, node)) return true
            swipeUp(handle)
        }
        val node = findByText(handle, text) ?: return false
        return isDisplayed(handle, node)
    }

    /**
     * A real upward swipe (finger drag) dispatched to the
     * compose view. NOTE (measured under Robolectric): the
     * semantics ScrollBy action only LAUNCHES a coroutine on
     * the node's AndroidUiDispatcher scope, and that
     * dispatcher never progresses in this sandbox — the
     * action returns true and nothing ever moves. Drag
     * deltas, by contrast, are applied synchronously by
     * Compose's pointer pipeline during event dispatch, so
     * gestures are the working scroll driver here (and the
     * more faithful one: it is what a finger does).
     */
    fun swipeUp(handle: Handle) {
        val view = findComposeView(handle.activity.window.decorView)
            ?: return
        val width = view.width
        val height = view.height
        if (width == 0 || height == 0) return
        val x = width / 2f
        val yStart = height * 0.8f
        val yEnd = height * 0.25f
        val t0 = android.os.SystemClock.uptimeMillis()
        fun event(time: Long, action: Int, y: Float) =
            android.view.MotionEvent.obtain(t0, time, action, x, y, 0)
        view.dispatchTouchEvent(
            event(t0, android.view.MotionEvent.ACTION_DOWN, yStart)
        )
        val steps = 8
        for (i in 1..steps) {
            val y = yStart + (yEnd - yStart) * i / steps
            view.dispatchTouchEvent(
                event(
                    t0 + i * 16L,
                    android.view.MotionEvent.ACTION_MOVE, y
                )
            )
        }
        view.dispatchTouchEvent(
            event(
                t0 + (steps + 1) * 16L,
                android.view.MotionEvent.ACTION_UP, yEnd
            )
        )
        idle()
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name ==
            "androidx.compose.ui.platform.AndroidComposeView"
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
