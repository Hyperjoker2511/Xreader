package app.xreader.ui.localreader

import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.navigator.input.KeyEvent as ReadiumKeyEvent

/**
 * v1.5.10 (D1 + F3): THE narrow navigator seam for the EPUB
 * reader — the small set of fragment capabilities the
 * selection wiring needs, extracted so it is driveable against
 * a fake in tests (production implements it over the live
 * [EpubNavigatorFragment]; tests fake it). [EpubBookReader]
 * owns the production implementation
 * ([FragmentEpubNavigatorSeam]); the selection components
 * consume this interface — they never touch the fragment
 * directly.
 *
 * v1.5.12 (E1 / contract §2.2): the seam's drag-era members
 * (currentProgression + the go verdicts the drag-advance
 * controller consumed) are GONE with the controller. No drag
 * is ever interpreted as a chapter change any more: in scroll
 * mode the Readium engine owns the vertical drag wholly, and
 * page turns come from tap zones, keys and the navigator's
 * own paginated machinery. What remains here is the locator
 * (for decorations) and the on-demand selection surface the
 * native ActionMode menu actions call — once per action the
 * user actually taps, never on a timer.
 */
interface EpubNavigatorSeam {
    /** The CURRENT locator, or null before the first locator. */
    fun currentLocator(): Locator?

    /** The live text selection (locator + on-screen rect), or
     * null when nothing is selected. Suspend: a WebView bridge
     * round-trip — called ON DEMAND by a native selection-menu
     * action, never polled (v1.5.12, E3). */
    suspend fun currentSelection(): org.readium.r2.navigator.Selection?

    /** Clear the live text selection. */
    fun clearSelection()

    /** Apply [decorations] under [group] (Readium's decoration
     * groups replace wholesale per group). */
    suspend fun applyDecorations(
        decorations: List<Decoration>,
        group: String
    )
}

/**
 * The production [EpubNavigatorSeam] over the live fragment.
 * The fragment is read through [fragment] on every call — the
 * fragment instance is captured outside the creation branch
 * and can be replaced on recreation, so nothing here may
 * cache it.
 */
class FragmentEpubNavigatorSeam(
    private val fragment: () -> EpubNavigatorFragment?
) : EpubNavigatorSeam {

    override fun currentLocator(): Locator? =
        fragment()?.currentLocator?.value

    override suspend fun currentSelection():
        org.readium.r2.navigator.Selection? =
        fragment()?.currentSelection()

    override fun clearSelection() {
        fragment()?.clearSelection()
    }

    override suspend fun applyDecorations(
        decorations: List<Decoration>,
        group: String
    ) {
        fragment()?.applyDecorations(decorations, group)
    }
}

/**
 * v1.5.12 (E1): the page-turn surface the reader's ONE input
 * listener acts on — extracted (like the seam above) so the
 * listener's event handling is driveable against a fake in
 * gate tests: 20 consecutive tap turns land on a counter, and
 * drags of any size land NOWHERE.
 */
internal interface EpubPageTurner {
    /** Advance one page/resource (animated); false at the end
     * of the book. */
    fun goForward(): Boolean

    /** Retreat one page/resource (animated); false at the
     * start of the book. */
    fun goBackward(): Boolean

    /** The navigator view's (width, height) in px, or null
     * before layout — tap zones are fractions of it. */
    fun viewSizePx(): Pair<Int, Int>?
}

/** The production [EpubPageTurner] over the live fragment. */
internal class FragmentEpubPageTurner(
    private val fragment: () -> EpubNavigatorFragment?
) : EpubPageTurner {

    override fun goForward(): Boolean =
        fragment()?.goForward(animated = true) ?: false

    override fun goBackward(): Boolean =
        fragment()?.goBackward(animated = true) ?: false

    override fun viewSizePx(): Pair<Int, Int>? {
        val view = fragment()?.view ?: return null
        val width = view.width.takeIf { it > 0 } ?: return null
        val height = view.height.takeIf { it > 0 } ?: return null
        return width to height
    }
}

/**
 * v1.5.18 (F3 / C-E1): whether the reader registers its
 * [InputListener] at all, as a pure decision over the reading
 * mode so the gate can assert it without a fragment. In
 * Readium 3.1.2 EVERY gesture verdict delivered to a
 * registered listener — drags included — is a synchronous
 * `runBlocking` JS→main round-trip, so a registered listener
 * taxes every scroll attempt even though its drag verdict is
 * always false. Scroll mode therefore registers NOTHING: the
 * engine owns drags natively, its chrome toggle rides the
 * watch-only Compose tap observer (see
 * [epubScrollModeTapAction] + EpubBody), link taps were never
 * routed through this listener (the navigator's own link
 * handling owns them — no host link listener exists), text
 * selection is the WebView's native ActionMode plus an
 * on-demand `currentSelection()`, and keys were never
 * consumed here (volume-key turns ride the comfort host).
 * Paginated mode keeps the listener: tap-turns are its
 * primary turn road.
 */
internal fun epubShouldRegisterInputListener(scrollMode: Boolean): Boolean =
    !scrollMode

/**
 * v1.5.18 (F3 / C-E1): what a clean tap does in SCROLL mode,
 * where no [InputListener] is registered. The preset zone map
 * still decides WHERE chrome toggles ([tapActionFor]'s
 * TOGGLE_CHROME zone, per preset), but the TURN zones are
 * inert: in scroll mode navigation is the scroll itself (plus
 * volume keys and the chrome's own chapter controls), and a
 * tap must never become a resource jump. Pure, so the gate
 * pins the map without a WebView.
 */
internal fun epubScrollModeTapAction(
    preset: TapLayoutPreset,
    xFraction: Float,
    yFraction: Float
): TapZoneAction =
    if (tapActionFor(preset, xFraction, yFraction) ==
        TapZoneAction.TOGGLE_CHROME
    ) {
        TapZoneAction.TOGGLE_CHROME
    } else {
        TapZoneAction.NONE
    }

/**
 * v1.5.12 (E1 / contract §2.1+§2.2): the reader's ONE input
 * listener, in the reference host's shape (Readium testapp):
 * taps and keys only. Centre/edge taps follow the per-reader
 * PRESET zone map ([tapActionFor]); keys stay the toolkit's.
 *
 * v1.5.18 (F3 / C-E1): registered ONLY in paginated mode
 * ([epubShouldRegisterInputListener]) — in scroll mode its
 * per-drag bridge round-trips were the convicted ANR scaler
 * and nothing it served there required the bridge.
 *
 * [onDrag] returns false for EVERY drag — slight, decisive,
 * at a chapter boundary, on a plate. There is deliberately no
 * drag-advance controller behind it any more: the v1.5.11
 * host converted vertical drags into `goForward()` calls from
 * TWO roads (this listener and a native touch container
 * deciding inside `dispatchTouchEvent`), which is what made a
 * slight scroll jump a chapter and wedged the reader at
 * chapter boundaries. In scroll mode the engine owns the
 * vertical drag WITHIN a resource only — corrected v1.5.34:
 * the earlier claim that it also owned the boundary "through
 * its own scrolling machinery" was false (the v1.5.19
 * forensics convicted it: 3.1.2's scroll mode has NO
 * cross-resource continuation; the boundary-push apparatus
 * existed precisely because of that). The river is the
 * scroll surface now and has no boundaries; this listener
 * serves paginated mode, where turns come from the
 * navigator's own input handling, these tap zones, volume
 * keys and explicit controls.
 */
internal class EpubReaderInputListener(
    private val turner: EpubPageTurner,
    private val tapPreset: () -> TapLayoutPreset,
    private val onToggleControls: () -> Unit
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        val (width, height) = turner.viewSizePx() ?: return false
        return when (
            tapActionFor(
                tapPreset(),
                event.point.x / width,
                event.point.y / height
            )
        ) {
            TapZoneAction.TURN_PREV -> {
                turner.goBackward()
                true
            }
            TapZoneAction.TURN_NEXT -> {
                turner.goForward()
                true
            }
            TapZoneAction.TOGGLE_CHROME -> {
                onToggleControls()
                true
            }
            TapZoneAction.NONE -> false
        }
    }

    /** Drags are NEVER consumed and NEVER converted — the
     * engine owns them (class doc above). Returning false is
     * the whole implementation, by design. */
    override fun onDrag(event: DragEvent): Boolean = false

    override fun onKey(event: ReadiumKeyEvent): Boolean = false
}
