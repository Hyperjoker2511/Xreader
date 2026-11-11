package app.xreader.ui.localreader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xreader.data.engine.EpubRiverMerger
import app.xreader.data.engine.EpubRiverUserCss
import app.xreader.data.engine.RIVER_ORIGIN
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.engine.RiverDocument
import app.xreader.data.engine.canonicalHrefKey
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * v1.5.31 (EPUB RIVER R1, plan §2.3): the river surface — ONE
 * WebView holding the merger's single document, in the reader's
 * root so the chrome overlays it and the page geometry never
 * depends on chrome (§7.1's structure).
 *
 * Serving: a fake origin, no socket server. Requests under
 * `https://xreader.river/…` are answered by
 * [WebViewClient.shouldInterceptRequest] from the Readium
 * container (`publication.get(...)`) — the foliate-server
 * behaviour pattern implemented fresh against Readium's BSD-3
 * container API.
 *
 * v1.5.34 (item 0 — the REA §5 reporting rebuild): v1.5.31–33
 * served the document with `loadDataWithBaseURL` and learned
 * position from UNSOLICITED bridge posts; the bridge died
 * silently in both v1.5.32 (non-public class) and v1.5.33
 * (cause never convicted; every checkable joint sound). The
 * rebuild follows the one model proven inside this very app —
 * Readium's own navigator WebView, which never trusts an
 * unsolicited post:
 *  - the document is served BY URL: the interceptor answers
 *    `https://xreader.river/book` and the view `loadUrl`s it,
 *    the load mode every working bridge in the app rides;
 *  - position is HOST-OBSERVED + HOST-PULLED: the WebView's
 *    own `onScrollChanged` says scrolling is happening, and a
 *    throttled `evaluateJavascript` pull (WITH result
 *    callback — the WebViewRenderedFetcher pattern) reads
 *    `__xrAnchor()`'s "section:block" string, converted by
 *    [EpubRiverPosition] into the ViewModel seam. The bridge
 *    is no longer the position of record;
 *  - plate taps are HOST-SIDE: the tap observer's clean taps
 *    consult the WebView's `HitTestResult`; the JS click road
 *    survives only as a fallback (SVG `<image>`), String
 *    payloads only;
 *  - a bounded SELF-CHECK at open (typeof probe + one live
 *    anchor pull + three-signature bridge pings, all logged)
 *    convicts a dead channel in the open instead of letting
 *    the reader freeze silently — on failure the reader shows
 *    a plain note. Console messages are logged permanently
 *    through a WebChromeClient (Librera's standing pattern).
 *
 * v1.5.34 also lands R4 (selection + highlights: the stock
 * floating ActionMode with Xreader's actions, ONE
 * `__xrSelection()` pull per action, `<mark>` reapplication),
 * R6 (search-hit marks, read-aloud follow, footnote
 * jump-and-return) and R7 (windowed long books: gap sections
 * filled around the anchor by the window manager below).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EpubRiverView(
    session: ReadiumEpubSession,
    bookId: Long,
    bookFingerprint: String?,
    settings: EpubBookSettings,
    initialSection: Int,
    initialProgression: Double?,
    initialQuote: String?,
    driver: EpubRiverDriver,
    onPositionReport: (Locator) -> Unit,
    onFirstPaint: () -> Unit,
    onUnavailable: () -> Unit,
    highlights: List<StoredSelectionHighlight>,
    onHighlightTap: (String) -> Unit,
    onSelectionAction: (EpubSelectionMenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userCss = remember(settings, session) {
        EpubRiverUserCss.generate(settings, session)
    }
    val userCssState = rememberUpdatedState(userCss)
    val firstPaintState = rememberUpdatedState(onFirstPaint)
    val unavailableState = rememberUpdatedState(onUnavailable)
    val positionState = rememberUpdatedState(onPositionReport)
    val highlightTapState = rememberUpdatedState(onHighlightTap)
    val selectionActionState = rememberUpdatedState(onSelectionAction)
    val highlightsState = rememberUpdatedState(highlights)

    var document by remember(session, bookId) {
        mutableStateOf<RiverDocument?>(null)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageFinished by remember { mutableStateOf(false) }
    var pageCommitted by remember { mutableStateOf(false) }

    // v1.5.34 (item 0): the self-check's states. bridgeAlive =
    // the typeof probe saw the bridge object; documentReady =
    // the page script is known to have run (probe) or the load
    // finished; positionUnavailable = the self-check's failure
    // verdict, surfaced as the plain note.
    var bridgeAlive by remember { mutableStateOf(false) }
    // v1.5.35 (restore §C.1): the PAGE SCRIPT's readiness —
    // true only once `typeof window.__xrStart` answers
    // "function". The injected bridge object exists from
    // JS-context creation and proves nothing about the
    // book's own trailing script (forensics B1); the restore
    // gates on this, never on the bridge probe.
    var scriptReady by remember { mutableStateOf(false) }
    var positionUnavailable by remember { mutableStateOf(false) }
    var positionNoteDismissed by remember { mutableStateOf(false) }
    val documentReady = pageFinished || bridgeAlive

    // v1.5.32 (§6): the plate viewer. platePath is the tapped
    // image's container path while the viewer is open;
    // shownPlate keeps the last path so the exit fade still
    // has its image after platePath clears.
    var platePath by remember { mutableStateOf<String?>(null) }
    var shownPlate by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(platePath) {
        if (platePath != null) shownPlate = platePath
    }
    val plateOpenState = rememberUpdatedState(platePath != null)

    // v1.5.34 (R6): a footnote jump's return anchor — the
    // (section, block) the reader stood on when the footnote
    // was tapped, offered back by the floating chip.
    var footnoteReturn by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    LaunchedEffect(footnoteReturn) {
        if (footnoteReturn != null) {
            delay(20_000)
            footnoteReturn = null
        }
    }

    // v1.5.34 (R7): the initial window's fills have settled —
    // the restore waits for this on windowed books only.
    var windowFilled by remember { mutableStateOf(false) }

    // One-shot guards, the host trackers and the CSS currently
    // baked into the loaded page, held outside composition
    // state (plain holders, the house pattern for callback
    // wiring).
    val guards = remember {
        object {
            var readyFired = false
            var unavailableFired = false
            var selfChecked = false
            var restoreStarted = false
            var appliedCss: String? = null
            var bridge: RiverBridge? = null
        }
    }
    // The restore as STATE (the self-check effect keys on it —
    // a plain guard var would never re-trigger the effect).
    var restoredState by remember { mutableStateOf(false) }
    val tracker = remember { RiverHostTracker() }
    val fireReady: () -> Unit = remember {
        {
            if (!guards.readyFired) {
                guards.readyFired = true
                firstPaintState.value()
            }
        }
    }

    val imageTapState = rememberUpdatedState<(String) -> Unit> { url ->
        riverContainerPath(Uri.parse(url))?.let {
            tracker.plateClaimMs = System.currentTimeMillis()
            platePath = it
        }
    }
    // v1.5.34 (R6): a footnote tap (bridge, String payload):
    // record where the reader stands (one host anchor pull),
    // then scroll the note into view in-page. The chip offers
    // the way back.
    val footnoteTapState = rememberUpdatedState<(String) -> Unit> { targetId ->
        val view = webView
        if (view != null) {
            // v1.5.35 (restore §C.4): a footnote jump is the
            // reader moving — the restore sequence stands
            // down permanently.
            tracker.readerTookOver = true
            scope.launch {
                pullAnchorRaw(view)?.let { footnoteReturn = it }
                val landed = suspendEvaluate(
                    view,
                    "(function(){var el=document.getElementById(" +
                        JSONObject.quote(targetId) +
                        ");if(el){el.scrollIntoView();return '1';}" +
                        "return '0';})()"
                )
                if (landed != "1") {
                    // The target lives in an unfilled gap
                    // (windowed books) or is gone: land on its
                    // section — the window manager follows the
                    // anchor there, and the note arrives with
                    // the fill.
                    val section = Regex("^xr-(\\d+)-")
                        .find(targetId)?.groupValues?.get(1)?.toIntOrNull()
                    if (section != null) {
                        view.evaluateJavascript(
                            "window.__xrGoto && window.__xrGoto(" +
                                "$section,0,null)",
                            null
                        )
                    }
                }
            }
        }
    }
    val highlightBridgeState = rememberUpdatedState<(String) -> Unit> { id ->
        highlightTapState.value(id)
    }

    LaunchedEffect(session, bookId) {
        val cacheDir = File(context.cacheDir, "local-epub-river/$bookId")
        val fingerprint = (bookFingerprint ?: "book:$bookId") + "|river"
        val cssUsed = userCssState.value
        val prepared = EpubRiverMerger.prepare(
            session = session,
            cacheDir = cacheDir,
            fingerprint = fingerprint,
            userCss = cssUsed
        )
        if (prepared == null) {
            if (!guards.unavailableFired) {
                guards.unavailableFired = true
                unavailableState.value()
            }
        } else {
            // The page loads with THIS css baked in; the live
            // swap effect diffs against it.
            guards.appliedCss = cssUsed
            if (prepared.windowed) {
                // R7: measured heights from earlier reads of
                // this book at this typography, if any — the
                // gap compensation's memory.
                tracker.heightsKey = fingerprint + "|" + cssUsed.hashCode()
                withContext(Dispatchers.IO) {
                    loadRiverHeights(cacheDir, tracker.heightsKey!!)
                        ?.let { tracker.heights.putAll(it) }
                }
            }
            document = prepared
        }
    }

    // v1.5.34 (item 0): the bridge probe — from first paint,
    // poll the typeof check until the page script is known to
    // have run (or the load finishes and settles it the other
    // way). This is what lets the restore + the position loop
    // start at first paint instead of whole-document load.
    LaunchedEffect(webView, pageCommitted, document) {
        val view = webView
        if (view != null && pageCommitted && document != null &&
            !bridgeAlive && !pageFinished
        ) {
            while (!bridgeAlive && !pageFinished) {
                val type = suspendEvaluate(
                    view, "typeof window.XRiverBridge"
                )
                if (type == "object") {
                    bridgeAlive = true
                    Log.i(RIVER_LOG_TAG, "bridge probe: object present")
                    break
                }
                delay(250)
            }
        }
    }

    // v1.5.35 (restore §C.1): the SCRIPT probe — poll the
    // typeof of the page's own __xrStart until it is a
    // function. One final check lands after pageFinished
    // (the trailing script has run by then on any healthy
    // load); if it still isn't a function, scriptReady stays
    // false, the restore never fires, and the deadline
    // watcher below raises the plain note — a named failure,
    // never a silent Cover overwrite.
    LaunchedEffect(webView, pageCommitted, document) {
        val view = webView
        if (view != null && pageCommitted && document != null &&
            !scriptReady
        ) {
            val deadlineMs = System.currentTimeMillis() + 10_000
            while (!scriptReady) {
                val type = suspendEvaluate(
                    view, "typeof window.__xrStart"
                )
                if (type == "function") {
                    scriptReady = true
                    Log.i(
                        RIVER_LOG_TAG,
                        "script probe: __xrStart is a function"
                    )
                    break
                }
                if (pageFinished) {
                    Log.w(
                        RIVER_LOG_TAG,
                        "script probe: __xrStart=$type after " +
                            "pageFinished — restore cannot run"
                    )
                    break
                }
                if (System.currentTimeMillis() > deadlineMs) {
                    Log.w(
                        RIVER_LOG_TAG,
                        "script probe: __xrStart=$type after " +
                            "10 s — restore cannot run"
                    )
                    break
                }
                delay(200)
            }
        }
    }

    // v1.5.35 (restore §C.7): the deadline watcher — if the
    // restore has neither confirmed nor been taken over
    // ~6 s after first paint (script never arrived, window
    // never filled), position keeping IS unavailable for
    // this open and the reader is told so, in the existing
    // plain note. A late confirmation clears it again (the
    // restore sequence does), so the note always states the
    // current truth.
    LaunchedEffect(webView, pageCommitted, document) {
        if (webView != null && pageCommitted && document != null) {
            delay(6_000)
            if (!restoredState && !tracker.readerTookOver) {
                Log.w(
                    RIVER_LOG_TAG,
                    "restore: unconfirmed 6 s after first " +
                        "paint — position keeping unavailable"
                )
                positionUnavailable = true
            }
        }
    }

    // R7: on a windowed book, hold the gaps at their measured
    // heights and fill the opening window BEFORE the restore
    // scrolls (the restore effect below waits on windowFilled).
    // v1.5.35 (restore §C.1): gated on scriptReady too — the
    // fill calls the page's own script, and marking the
    // window "filled" after a script-less no-op was the
    // same silent-miss shape as B1.
    LaunchedEffect(documentReady, scriptReady, document) {
        val view = webView
        val doc = document
        if (view != null && doc != null && doc.windowed &&
            documentReady && scriptReady && !windowFilled
        ) {
            applyGapHeights(view, tracker)
            ensureWindow(
                view = view,
                document = doc,
                tracker = tracker,
                centre = initialSection,
                onFillsSettled = { applyMarks(view, session, doc, highlightsState.value) }
            )
            windowFilled = true
            tracker.pullRequested = true
        }
    }

    // The settle beat the restore waits out between
    // applications (v1.5.35, restore §C.2): the load has
    // finished AND no scroll event has landed for ~600 ms
    // (the page has stopped moving under the landing), or
    // the ~4 s cap from the first application runs out, or
    // the reader takes over — whichever comes first.
    suspend fun awaitRiverSettle(deadlineMs: Long) {
        while (System.currentTimeMillis() < deadlineMs) {
            if (tracker.readerTookOver) return
            val quietMs = System.currentTimeMillis() -
                tracker.lastScrollAtMs
            if (pageFinished && quietMs >= 600) return
            delay(100)
        }
    }

    // The opening restore (§4.3 ladder + v1.5.35 restore
    // §C.1–§C.4): the stored locator's section, its
    // progression resolved through the char map to the
    // paragraph block, and its text quote when the record
    // carries one — the JS prefers an exact quote match
    // inside the section, the block otherwise.
    //
    // The v1.5.34 shape — restoredState set BEFORE one
    // fire-and-forget `window.__xrStart && …` call keyed on
    // the bridge probe — is forensics B1/B2 (V1534-RESTORE-
    // FORENSICS): the shot could silently never happen and
    // the position loop then overwrote the stored place
    // with the cover; a landed shot could still be walked
    // away from by late layout. Now: the effect gates on
    // scriptReady (the page's own script, §C.1) and hands
    // the sequence to the composition scope (an effect
    // restart on pageFinished must never kill it
    // mid-flight); each application is ACKNOWLEDGED through
    // suspendEvaluate (__xrStart returns the post-scroll
    // anchor, §C.2); the SAME element anchor is re-applied
    // after the pageFinished + settle beat while the
    // landing keeps drifting; one verification pull of
    // __xrAnchor() follows the final application (cap: 3
    // applications, ~4 s); any user touch or jump cancels
    // the sequence permanently (§C.4); and no report
    // reaches the save seam until the landing confirms
    // (§C.3 — the tracker gate in pullAnchor).
    LaunchedEffect(webView, scriptReady, windowFilled, document) {
        val view = webView
        val doc = document
        val windowReady = doc != null && (!doc.windowed || windowFilled)
        if (view != null && doc != null && scriptReady &&
            windowReady && !guards.restoreStarted
        ) {
            guards.restoreStarted = true
            scope.launch {
                val target = EpubRiverPosition.anchorFor(
                    doc, initialSection, initialProgression
                )
                val block = target.second
                val quoteArg = initialQuote
                    ?.takeIf { it.isNotBlank() }
                    ?.let { JSONObject.quote(it) } ?: "null"
                val deadlineMs = System.currentTimeMillis() + 4_000
                var applications = 0
                var confirmed = false
                var expected: Pair<Int, Int>? = null
                while (applications < 3 && !confirmed) {
                    if (tracker.readerTookOver) break
                    val typeNow = suspendEvaluate(
                        view, "typeof window.__xrStart"
                    )
                    applications++
                    val reply = suspendEvaluate(
                        view,
                        "window.__xrStart(" +
                            "$initialSection,$block,$quoteArg)"
                    )
                    val landed = parseAnchor(reply)
                        ?: pullAnchorRaw(view)
                    if (expected == null) {
                        expected = landed ?: target
                    }
                    Log.i(
                        RIVER_LOG_TAG,
                        "restore: attempt $applications, " +
                            "__xrStart=$typeNow, anchor=" +
                            (landed?.let {
                                "${it.first}:${it.second}"
                            } ?: "none")
                    )
                    if (applications >= 3) break
                    awaitRiverSettle(deadlineMs)
                    if (tracker.readerTookOver) break
                    val verify = pullAnchorRaw(view)
                    if (verify != null &&
                        anchorMatches(verify, expected!!)
                    ) {
                        confirmed = true
                    }
                }
                if (!confirmed && !tracker.readerTookOver) {
                    // The final application's own verification
                    // pull (§C.2), after its settle beat.
                    awaitRiverSettle(deadlineMs)
                    val verify = pullAnchorRaw(view)
                    if (verify != null && expected != null &&
                        anchorMatches(verify, expected!!)
                    ) {
                        confirmed = true
                    }
                }
                when {
                    tracker.readerTookOver -> Log.i(
                        RIVER_LOG_TAG,
                        "restore: reader took over — " +
                            "sequence cancelled permanently"
                    )
                    confirmed -> {
                        tracker.reportsAllowed = true
                        tracker.pullRequested = true
                        if (positionUnavailable) {
                            positionUnavailable = false
                        }
                        Log.i(
                            RIVER_LOG_TAG,
                            "restore: confirmed at " +
                                "${expected?.first}:" +
                                "${expected?.second} after " +
                                "$applications application(s)"
                        )
                    }
                    else -> {
                        Log.w(
                            RIVER_LOG_TAG,
                            "restore: UNCONFIRMED after " +
                                "$applications application(s) " +
                                "— stored place left untouched"
                        )
                        positionUnavailable = true
                    }
                }
                restoredState = true
                scope.launch { measureOffsets(view, tracker) }
                applyMarks(view, session, doc, highlightsState.value)
            }
        }
    }

    // v1.5.34 (item 0.4): the SELF-CHECK — bounded (~1.5 s from
    // document-ready), fully logged. One live anchor pull +
    // the three bridge ping signatures. A dead position
    // channel raises the plain note; nothing freezes silently
    // ever again.
    LaunchedEffect(webView, documentReady, windowFilled, restoredState, document) {
        val view = webView
        val doc = document
        val windowReady = doc != null && (!doc.windowed || windowFilled)
        if (view != null && doc != null && documentReady &&
            windowReady && restoredState && !guards.selfChecked
        ) {
            guards.selfChecked = true
            val verdict = withTimeoutOrNull(1_500) {
                val type = suspendEvaluate(
                    view, "typeof window.XRiverBridge"
                )
                val typeOk = type == "object"
                val anchor = suspendEvaluate(
                    view,
                    "window.__xrAnchor ? window.__xrAnchor() : null"
                )
                val anchorOk = parseAnchor(anchor) != null
                var pings = "skipped"
                if (typeOk) {
                    view.evaluateJavascript(
                        "window.XRiverBridge && XRiverBridge.ping()",
                        null
                    )
                    view.evaluateJavascript(
                        "window.XRiverBridge && " +
                            "XRiverBridge.ping('xr-selfcheck')",
                        null
                    )
                    view.evaluateJavascript(
                        "window.XRiverBridge && XRiverBridge.ping(1, 2)",
                        null
                    )
                    delay(350)
                    val bridge = guards.bridge
                    pings = "noArg=${bridge?.pingNoArg ?: -1} " +
                        "string=${bridge?.pingString ?: -1} " +
                        "ints=${bridge?.pingInts ?: -1}"
                }
                Log.i(
                    RIVER_LOG_TAG,
                    "self-check: typeof=$type anchor=$anchor pings=$pings"
                )
                typeOk && anchorOk
            } ?: false
            if (!verdict) {
                Log.w(
                    RIVER_LOG_TAG,
                    "self-check FAILED — position keeping unavailable"
                )
                positionUnavailable = true
            }
        }
    }

    // v1.5.34 (item 0.2): the position loop — the host pulls
    // the anchor while scrolling runs and once more when it
    // settles, so the recorded place is the resting place.
    // Between pulls nothing moves: a dropped pull holds the
    // last anchor, never a freeze and never a guess.
    LaunchedEffect(webView, documentReady, document) {
        val view = webView
        val doc = document
        if (view != null && doc != null && documentReady) {
            var wasScrolling = false
            // R7: the window follows the anchor section.
            val onSectionChange: (Int) -> Unit = { section ->
                if (doc.windowed) {
                    scope.launch {
                        ensureWindow(view, doc, tracker, section) {
                            applyMarks(
                                view, session, doc,
                                highlightsState.value
                            )
                        }
                    }
                }
            }
            while (true) {
                val now = System.currentTimeMillis()
                val scrolling = now - tracker.lastScrollAtMs < 600
                if (scrolling || tracker.pullRequested) {
                    pullAnchor(
                        view, session, doc, tracker,
                        onSectionChange
                    ) { positionState.value(it) }
                    tracker.pullRequested = false
                    wasScrolling = scrolling
                } else if (wasScrolling) {
                    pullAnchor(
                        view, session, doc, tracker,
                        onSectionChange
                    ) { positionState.value(it) }
                    wasScrolling = false
                }
                delay(160)
            }
        }
    }

    // Live typography: swap the #xr-user block in place on every
    // settings change — the liveness the audit verified for the
    // toolkit submission road (§2.4). Section offsets are
    // re-measured afterwards (the window manager's arithmetic
    // and the coarse fallback both read them).
    LaunchedEffect(userCss, webView, pageFinished) {
        val view = webView
        if (view != null && pageFinished && guards.appliedCss != userCss) {
            guards.appliedCss = userCss
            view.evaluateJavascript(
                "(function(){var s=document.getElementById('xr-user');" +
                    "if(s){s.textContent=" + JSONObject.quote(userCss) +
                    ";}})()",
                null
            )
            tracker.offsets = null
            scope.launch { measureOffsets(view, tracker) }
        }
    }

    // R4: stored highlights reapply on load and on every store
    // change (a fresh highlight appears the moment it is made).
    // v1.5.35 (restore §C.1): scriptReady is a key — a marks
    // pass fired before the page script exists no-ops
    // silently; keying on it re-runs the pass when the
    // script arrives.
    LaunchedEffect(highlights, documentReady, scriptReady, windowFilled) {
        val view = webView
        val doc = document
        val windowReady = doc != null && (!doc.windowed || windowFilled)
        if (view != null && doc != null && documentReady &&
            scriptReady && windowReady
        ) {
            applyMarks(view, session, doc, highlights)
        }
    }

    val currentDocument = document ?: return
    Box(modifier.fillMaxSize()) {
    AndroidView(
        factory = { ctx ->
            val view = RiverWebView(ctx)
            view.settings.javaScriptEnabled = true
            // The CSS owns text size; the WebView's own text
            // zoom must not compound it.
            view.settings.textZoom = 100
            view.scrollListener = { y ->
                tracker.lastScrollY = y
                tracker.lastScrollAtMs = System.currentTimeMillis()
            }
            // v1.5.35 (restore §C.4): any touch on the page
            // is the reader taking over — the restore
            // sequence stands down permanently.
            view.touchDownHandler = { tracker.readerTookOver = true }
            view.selectionActionHandler = { action ->
                selectionActionState.value(action)
            }
            view.webChromeClient = RiverWebChromeClient()
            view.webViewClient = RiverWebViewClient(
                session = session,
                document = currentDocument,
                onPageFinished = {
                    pageFinished = true
                },
                onPageCommitVisible = {
                    // v1.5.33 (V1532-PROOF-FORENSICS Q4): the
                    // opening card dismisses at the first
                    // PAINTED frame, not at whole-document
                    // load-complete (~9 s on the 32 MB proof
                    // book, the river already scrollable
                    // behind the card).
                    pageCommitted = true
                    fireReady()
                },
                onExternalLink = { uri ->
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            )
            val bridge = RiverBridge(
                handler = Handler(Looper.getMainLooper()),
                onReady = fireReady,
                onImageTap = { url -> imageTapState.value(url) },
                onFootnoteTap = { targetId ->
                    footnoteTapState.value(targetId)
                },
                onHighlightTap = { id -> highlightBridgeState.value(id) }
            )
            guards.bridge = bridge
            view.addJavascriptInterface(bridge, "XRiverBridge")
            // The imperative driver EpubBody's collectors and
            // auto-scroll surface act through (items 5/6 in
            // v1.5.32; extended by v1.5.34 for marks, selection,
            // follow and plate arbitration).
            driver.goToProgression = { section, progression, markQuery, markCharOffset ->
                // v1.5.35 (restore §C.4): a jump is the
                // reader taking over — the restore sequence
                // stands down permanently.
                tracker.readerTookOver = true
                val block = EpubRiverPosition.anchorFor(
                    currentDocument, section, progression
                ).second
                val goto: () -> Unit = {
                    view.evaluateJavascript(
                        "window.__xrGoto && window.__xrGoto(" +
                            "$section,$block,null)",
                        null
                    )
                }
                if (currentDocument.windowed) {
                    // Fill the landing window first — a jump
                    // into a gap section lands on estimates
                    // until its content exists.
                    scope.launch {
                        ensureWindow(
                            view, currentDocument, tracker, section
                        ) {
                            applyMarks(
                                view, session, currentDocument,
                                highlightsState.value
                            )
                        }
                        goto()
                        tracker.pullRequested = true
                    }
                } else {
                    goto()
                    tracker.pullRequested = true
                }
                if (markQuery != null) {
                    // R6: land ON the hit with a transient mark.
                    scope.launch {
                        delay(150)
                        var landed = suspendEvaluate(
                            view,
                            "window.__xrMarkSearch ? " +
                                "window.__xrMarkSearch($section," +
                                "$markCharOffset," +
                                JSONObject.quote(markQuery) + ") : null"
                        )
                        if (landed != "1" && currentDocument.windowed) {
                            delay(600)
                            landed = suspendEvaluate(
                                view,
                                "window.__xrMarkSearch ? " +
                                    "window.__xrMarkSearch($section," +
                                    "$markCharOffset," +
                                    JSONObject.quote(markQuery) +
                                    ") : null"
                            )
                        }
                        if (landed == "1") {
                            tracker.pullRequested = true
                        }
                    }
                } else {
                    view.evaluateJavascript(
                        "window.__xrClearSearch && " +
                            "window.__xrClearSearch()",
                        null
                    )
                }
            }
            driver.stepBy = { dy ->
                view.evaluateJavascript(
                    "window.scrollBy({top:$dy,behavior:'smooth'})",
                    null
                )
            }
            driver.advanceBy = { dy ->
                if (!view.canScrollVertically(1)) {
                    false
                } else {
                    view.scrollBy(0, dy)
                    true
                }
            }
            driver.viewportHeightPx = { view.height }
            // Item 0.3 + 3c: plate arbitration. The probe is
            // synchronous with the gesture — hitTestResult
            // reflects the WebView's own last touch point (this
            // tap) — so a hit consumes the tap BEFORE any
            // chrome decision, with no deferral. The fractions
            // are unused: the WebView's recorded point is the
            // authoritative one.
            driver.probePlateTap = { _, _ ->
                val hit = runCatching { view.hitTestResult }.getOrNull()
                val isImage = hit != null &&
                    (hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                        hit.type ==
                            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                val extra = hit?.extra
                val path = if (isImage && extra != null) {
                    riverContainerPath(Uri.parse(extra))
                } else {
                    null
                }
                if (path != null) {
                    tracker.plateClaimMs = System.currentTimeMillis()
                    platePath = path
                    true
                } else {
                    false
                }
            }
            driver.plateViewerOpen = { plateOpenState.value }
            driver.lastPlateClaimMs = { tracker.plateClaimMs }
            // R4: ONE on-demand selection pull per action.
            driver.fetchSelection = { onResult ->
                scope.launch {
                    val raw = suspendEvaluate(
                        view,
                        "window.__xrSelection ? " +
                            "window.__xrSelection() : null"
                    )
                    onResult(
                        riverSelectionSnapshot(
                            session, currentDocument, raw
                        )
                    )
                }
            }
            driver.clearSelection = {
                view.evaluateJavascript(
                    "window.getSelection && " +
                        "window.getSelection().removeAllRanges()",
                    null
                )
            }
            // R6: read-aloud follow — gentle: the page script
            // scrolls only when the spoken section is off-screen.
            // v1.5.35 (restore §C.4): a follow scroll is a
            // jump too — the restore sequence stands down.
            driver.followSection = { section ->
                tracker.readerTookOver = true
                if (currentDocument.windowed) {
                    scope.launch {
                        ensureWindow(
                            view, currentDocument, tracker, section
                        ) {
                            applyMarks(
                                view, session, currentDocument,
                                highlightsState.value
                            )
                        }
                        view.evaluateJavascript(
                            "window.__xrFollowSection && " +
                                "window.__xrFollowSection($section)",
                            null
                        )
                    }
                } else {
                    view.evaluateJavascript(
                        "window.__xrFollowSection && " +
                            "window.__xrFollowSection($section)",
                        null
                    )
                }
            }
            // v1.5.35 (restore §C.6): the exit pull — ONE
            // last anchor read for the ON_STOP sequence in
            // EpubBookReader (it flushes only from [done]).
            // The report rides pullAnchor's normal road, so
            // the save-eligibility gate (§C.3) applies to it
            // exactly as to any loop pull: an unconfirmed,
            // untouched open reports nothing.
            driver.requestFinalPull = { done ->
                scope.launch {
                    try {
                        pullAnchor(
                            view, session, currentDocument, tracker,
                            onSectionChange = {}
                        ) { locator -> positionState.value(locator) }
                    } finally {
                        done()
                    }
                }
            }
            webView = view
            // v1.5.34 (item 0.1): the document is served BY URL
            // through the interceptor's /book route —
            // loadDataWithBaseURL leaves the river path
            // entirely (REA §5.1).
            view.loadUrl("$RIVER_ORIGIN/book")
            view
        },
        onRelease = { view ->
            if (webView === view) webView = null
            driver.goToProgression = null
            driver.stepBy = null
            driver.advanceBy = null
            driver.viewportHeightPx = null
            driver.probePlateTap = null
            driver.plateViewerOpen = null
            driver.lastPlateClaimMs = null
            driver.fetchSelection = null
            driver.clearSelection = null
            driver.followSection = null
            driver.requestFinalPull = null
            view.destroy()
        },
        modifier = Modifier.fillMaxSize()
    )
    // v1.5.34 (item 0.4): the self-check's plain word — shown
    // when position keeping failed its check, never a silent
    // frozen bar. Tap dismisses it for this open.
    if (positionUnavailable && !positionNoteDismissed) {
        Surface(
            onClick = { positionNoteDismissed = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp, start = 24.dp, end = 24.dp)
        ) {
            Text(
                "Position keeping is unavailable for this book — " +
                    "your place won't be saved",
                modifier = Modifier.padding(
                    horizontal = 14.dp, vertical = 10.dp
                )
            )
        }
    }
    // v1.5.34 (R6): the footnote return chip — floats above
    // the reader's bars, vanishes on its own after a while.
    val returnAnchor = footnoteReturn
    if (returnAnchor != null) {
        Surface(
            onClick = {
                footnoteReturn = null
                // v1.5.35 (restore §C.4): the return jump is
                // the reader moving — restore stands down.
                tracker.readerTookOver = true
                webView?.evaluateJavascript(
                    "window.__xrGoto && window.__xrGoto(" +
                        "${returnAnchor.first},${returnAnchor.second},null)",
                    null
                )
                tracker.pullRequested = true
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 118.dp)
        ) {
            Text(
                "← Back to your place",
                modifier = Modifier.padding(
                    horizontal = 14.dp, vertical = 10.dp
                )
            )
        }
    }
    // §6.4: the viewer overlays the river — the WebView never
    // navigates, scrolls or reloads underneath it, so Back
    // returns to the exact line the reader left, by
    // construction. Entry/exit is a fade.
    AnimatedVisibility(
        visible = platePath != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val path = shownPlate
        if (path != null) {
            EpubPlateViewer(
                session = session,
                document = currentDocument,
                containerPath = path,
                plateLabel = plateLabelFor(currentDocument, path),
                cacheDir = File(
                    context.cacheDir, "local-epub-river/$bookId"
                ),
                onDismiss = { platePath = null }
            )
        }
    }
    }
}

private const val RIVER_LOG_TAG = "EpubRiver"

/** v1.5.34 (R7): the window manager's neighbourhood budget —
 * sections fill around the anchor until each side carries
 * about this much XHTML (the current section and its
 * immediate neighbours always fill). */
private const val WINDOW_BUDGET_BYTES = 2 * 1024 * 1024

/**
 * The river host's working state (v1.5.34): scroll observation,
 * the last reported anchor, measured section offsets, the
 * window manager's filled set + measured heights, and the
 * plate-claim clock the chrome arbitration reads. Plain fields
 * behind one holder — callbacks write them off the composition
 * path; volatility is marked where a second thread (the JS
 * bridge thread's posts land on main, but the driver slots are
 * read from engine coroutines) can look.
 */
private class RiverHostTracker {
    @Volatile var lastScrollY: Int = 0
    @Volatile var lastScrollAtMs: Long = 0L
    @Volatile var pullRequested: Boolean = false
    @Volatile var plateClaimMs: Long = 0L
    // v1.5.35 (restore §C.3/§C.4): the save-eligibility gate.
    // reportsAllowed flips true ONLY when the opening restore
    // confirms its landing; readerTookOver flips on the
    // reader's first touch or jump (the restore stands down
    // permanently, and the place the reader is actually
    // standing becomes the record again). Until one of them
    // is set, pullAnchor pulls for the window manager and
    // the bar's own mechanics but reports NOTHING to the
    // save seam — a failed open can never overwrite the
    // stored place with the cover (forensics B1).
    @Volatile var reportsAllowed: Boolean = false
    @Volatile var readerTookOver: Boolean = false
    var lastAnchorSection: Int = -1
    var lastAnchorBlock: Int = -1
    var offsets: IntArray? = null
    val filled: MutableSet<Int> = mutableSetOf()
    val heights: MutableMap<Int, Int> = mutableMapOf()
    var heightsKey: String? = null
}

/** evaluateJavascript hands back a JSON-encoded value; unwrap
 * a string result (the WebViewRenderedFetcher pattern). */
private fun decodeJsString(raw: String?): String? {
    if (raw == null || raw == "null") return null
    return runCatching {
        JSONTokener(raw).nextValue() as? String
    }.getOrNull()
}

/** ONE evaluateJavascript WITH its result callback, suspended —
 * the in-app-proven pattern (WebViewRenderedFetcher.evaluate).
 * Fire-and-forget callers use evaluateJavascript directly;
 * anything that READS the page comes through here. */
private suspend fun suspendEvaluate(
    view: WebView,
    script: String
): String? = suspendCancellableCoroutine { cont ->
    view.evaluateJavascript(script) { value ->
        if (cont.isActive) {
            cont.resumeWith(Result.success(decodeJsString(value)))
        }
    }
    cont.invokeOnCancellation { /* the callback checks isActive */ }
}

/** Parse an "__xrAnchor" reply ("section:block"). */
private fun parseAnchor(raw: String?): Pair<Int, Int>? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val section = parts[0].trim().toIntOrNull() ?: return null
    val block = parts[1].trim().toIntOrNull() ?: return null
    return section to block
}

/**
 * v1.5.35 (restore §C.2): the restore's confirmation test —
 * a pulled anchor confirms the landing when it names the
 * SAME section and a block within a small window of the
 * expected one. Exact equality is too strict: the anchor
 * names the first block whose bottom is on screen, which
 * can sit a block either side of the scrolled-to element
 * when a tall block straddles the viewport top. A section
 * miss, or a drift beyond the window, never confirms.
 */
private fun anchorMatches(
    actual: Pair<Int, Int>,
    expected: Pair<Int, Int>
): Boolean =
    actual.first == expected.first &&
        kotlin.math.abs(actual.second - expected.second) <= 2

private suspend fun pullAnchorRaw(view: WebView): Pair<Int, Int>? =
    parseAnchor(
        suspendEvaluate(
            view, "window.__xrAnchor ? window.__xrAnchor() : null"
        )
    )

/**
 * One position pull (item 0.2): read the anchor, convert it
 * through the char map, hand the synthesised locator to the
 * ViewModel seam — but only when the anchor MOVED (the seam's
 * own gate would throttle the rest anyway; not emitting at
 * all keeps the loop free). When a pull comes back empty
 * mid-scroll, the coarse fallback reports the section under
 * the scroll offset from the measured offsets — block 0, and
 * only on a section change — so a transient pull failure
 * never freezes the bar.
 */
private suspend fun pullAnchor(
    view: WebView,
    session: ReadiumEpubSession,
    document: RiverDocument,
    tracker: RiverHostTracker,
    onSectionChange: (Int) -> Unit,
    onReport: (Locator) -> Unit
) {
    val anchor = pullAnchorRaw(view)
    if (anchor != null) {
        val (section, block) = anchor
        if (section != tracker.lastAnchorSection ||
            block != tracker.lastAnchorBlock
        ) {
            val sectionChanged = section != tracker.lastAnchorSection
            tracker.lastAnchorSection = section
            tracker.lastAnchorBlock = block
            // v1.5.35 (restore §C.3): the report reaches the
            // save seam only once the landing has confirmed
            // (or the reader has taken over) — see the
            // tracker's gate. The anchor bookkeeping itself
            // always runs, so the first post-confirmation
            // pull diffs against the true last anchor.
            if (tracker.reportsAllowed || tracker.readerTookOver) {
                EpubRiverPosition.locator(session, document, section, block)
                    ?.let(onReport)
            }
            if (sectionChanged) onSectionChange(section)
        }
        return
    }
    // Coarse fallback (see doc): measured offsets + scrollY.
    val offsets = tracker.offsets ?: return
    val scrolling = System.currentTimeMillis() - tracker.lastScrollAtMs < 600
    if (!scrolling || offsets.isEmpty()) return
    val scale = runCatching { view.scale }.getOrDefault(1f)
    if (scale <= 0f) return
    val cssY = tracker.lastScrollY / scale
    var section = 0
    for (i in offsets.indices) {
        if (offsets[i] <= cssY) section = i else break
    }
    if (section != tracker.lastAnchorSection) {
        tracker.lastAnchorSection = section
        tracker.lastAnchorBlock = 0
        // v1.5.35 (restore §C.3): the coarse road obeys the
        // same save-eligibility gate as the anchor road.
        if (tracker.reportsAllowed || tracker.readerTookOver) {
            EpubRiverPosition.locator(session, document, section, 0)
                ?.let(onReport)
        }
        onSectionChange(section)
    }
}

private suspend fun measureOffsets(view: WebView, tracker: RiverHostTracker) {
    val raw = suspendEvaluate(
        view, "window.__xrOffsets ? window.__xrOffsets() : null"
    ) ?: return
    val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (parsed.isNotEmpty()) {
        tracker.offsets = parsed.toIntArray()
    }
}

// ── R4: highlight marks ──────────────────────────────────────

/**
 * Reapply the stored highlights as in-page <mark> elements
 * (R4): the specs ride ONE evaluateJavascript — each carries
 * the highlight id, its section (resolved from the stored
 * locator's href) and the quote + context the page script
 * resolves through its ladder. Idempotent in-page: a mark
 * already present is skipped, so reapplying on every store
 * change is safe.
 */
private fun applyMarks(
    view: WebView,
    session: ReadiumEpubSession,
    document: RiverDocument,
    highlights: List<StoredSelectionHighlight>
) {
    if (highlights.isEmpty()) return
    val specs = JSONArray()
    for (highlight in highlights) {
        val locatorJson = runCatching {
            JSONObject(highlight.locatorJson)
        }.getOrNull() ?: continue
        val href = locatorJson.optString("href")
            .takeIf { it.isNotBlank() } ?: continue
        val section = session.sectionIndexOfHref(href) ?: continue
        val text = locatorJson.optJSONObject("text") ?: continue
        val quote = text.optString("highlight")
            .takeIf { it.isNotBlank() } ?: continue
        val spec = JSONObject()
        spec.put("id", highlight.id)
        spec.put("s", section)
        spec.put("q", quote)
        spec.put("b", text.optString("before"))
        spec.put("a", text.optString("after"))
        specs.put(spec)
    }
    if (specs.length() == 0) return
    view.evaluateJavascript(
        "window.__xrApplyMarks && window.__xrApplyMarks(" +
            JSONObject.quote(specs.toString()) + ")",
        { applied ->
            Log.i(
                RIVER_LOG_TAG,
                "marks: applied ${decodeJsString(applied)} " +
                    "of ${specs.length()}"
            )
        }
    )
}

/** Build the selection snapshot from one `__xrSelection()`
 * pull (R4): the page's JSON names the section + normalised
 * char offsets + context; the host synthesises the locator
 * through [EpubRiverPosition.locatorAtChar] so the stored
 * highlight carries the quote ladder's rungs. */
private fun riverSelectionSnapshot(
    session: ReadiumEpubSession,
    document: RiverDocument,
    raw: String?
): SelectionSnapshot? {
    if (raw.isNullOrBlank()) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val text = json.optString("text")
    if (text.isBlank()) return null
    val section = json.optInt("s", -1)
    if (section < 0) return null
    val start = json.optInt("start", 0)
    val locator = EpubRiverPosition.locatorAtChar(
        session = session,
        document = document,
        section = section,
        charOffset = start,
        text = Locator.Text(
            after = json.optString("after").ifBlank { null },
            before = json.optString("before").ifBlank { null },
            highlight = text
        )
    ) ?: return null
    return SelectionSnapshot(
        locatorJson = runCatching { locator.toJSON().toString() }
            .getOrNull() ?: return null,
        text = text,
        rect = null,
        href = locator.href.toString()
    )
}

// ── R7: the window manager ───────────────────────────────────

/** The sections that should hold content around [centre]:
 * the centre and its neighbours always; expansion each way
 * until that side carries about [WINDOW_BUDGET_BYTES]. */
private fun desiredWindow(sizes: List<Int>, centre: Int): Set<Int> {
    if (sizes.isEmpty()) return emptySet()
    val c = centre.coerceIn(0, sizes.size - 1)
    val desired = sortedSetOf(c)
    if (c - 1 >= 0) desired += c - 1
    if (c + 1 < sizes.size) desired += c + 1
    var acc = sizes[c]
    var left = c - 1
    while (left >= 0) {
        desired += left
        acc += sizes[left]
        if (acc >= WINDOW_BUDGET_BYTES) break
        left--
    }
    acc = sizes[c]
    var right = c + 1
    while (right < sizes.size) {
        desired += right
        acc += sizes[right]
        if (acc >= WINDOW_BUDGET_BYTES) break
        right++
    }
    return desired
}

private suspend fun pullFilledSet(view: WebView): Set<Int> {
    val raw = suspendEvaluate(
        view,
        "(function(){var m=window.__xrFilledMap||{};var o=[];" +
            "for(var k in m){if(m[k]){o.push(k);}}" +
            "return o.join(',');})()"
    ) ?: return emptySet()
    if (raw.isBlank()) return emptySet()
    return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/**
 * Bring the filled window to [centre] (R7): empty the
 * sections that fell out (measuring each first — the measured
 * height becomes its gap's held height and is persisted), fill
 * the sections that entered, and wait (bounded) for the fills
 * to land before [onFillsSettled]. All height changes ride
 * the page script's own anchor compensation, so the reading
 * line does not jump.
 */
private suspend fun ensureWindow(
    view: WebView,
    document: RiverDocument,
    tracker: RiverHostTracker,
    centre: Int,
    onFillsSettled: () -> Unit
) {
    if (!document.windowed) return
    val desired = desiredWindow(document.sectionSizes, centre)
    if (desired.isEmpty()) return
    val toEmpty = tracker.filled - desired
    for (index in toEmpty) {
        val measured = suspendEvaluate(
            view,
            "window.__xrSectionHeight ? " +
                "window.__xrSectionHeight($index) : null"
        )?.toIntOrNull()
        if (measured != null && measured > 0) {
            tracker.heights[index] = measured
        }
        val held = tracker.heights[index]
            ?: document.sections.getOrNull(index)?.estimatedHeightPx
            ?: 240
        view.evaluateJavascript(
            "window.__xrEmptySection && " +
                "window.__xrEmptySection($index,$held)",
            null
        )
        tracker.filled.remove(index)
    }
    if (toEmpty.isNotEmpty()) {
        val dir = document.cacheDirPath
        val key = tracker.heightsKey
        if (dir != null && key != null) {
            withContext(Dispatchers.IO) {
                persistRiverHeights(File(dir), key, tracker.heights)
            }
        }
    }
    val toFill = desired - tracker.filled
    for (index in toFill) {
        view.evaluateJavascript(
            "window.__xrFillSection && window.__xrFillSection($index)",
            null
        )
        tracker.filled.add(index)
    }
    if (toFill.isNotEmpty()) {
        // The page's own filled map is the truth (it claims a
        // section synchronously and un-claims it on empty or
        // fetch failure); reconcile the mirror against it.
        val deadline = System.currentTimeMillis() + 3_000
        var filledNow = pullFilledSet(view)
        while (!filledNow.containsAll(toFill) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(200)
            filledNow = pullFilledSet(view)
        }
        tracker.filled.clear()
        tracker.filled.addAll(filledNow)
        tracker.offsets = null
        measureOffsets(view, tracker)
        onFillsSettled()
    }
}

/** Hold the gaps at their measured heights (R7): one script,
 * generated from Int keys/values only. */
private fun applyGapHeights(view: WebView, tracker: RiverHostTracker) {
    if (tracker.heights.isEmpty()) return
    val json = JSONObject()
    for ((index, height) in tracker.heights) {
        json.put(index.toString(), height)
    }
    view.evaluateJavascript(
        "(function(){var h=" + json.toString() + ";" +
            "for(var k in h){" +
            "var s=document.getElementById('xr-sec-'+k);" +
            "if(s&&s.classList.contains('xr-gap')){" +
            "s.style.minHeight=h[k]+'px';}}})()",
        null
    )
}

private fun loadRiverHeights(
    cacheDir: File,
    key: String
): Map<Int, Int>? {
    val file = File(cacheDir, "river.heights.json")
    if (!file.isFile) return null
    val root = runCatching { JSONObject(file.readText()) }
        .getOrNull() ?: return null
    if (root.optString("key") != key) return null
    val heights = root.optJSONObject("h") ?: return null
    val out = mutableMapOf<Int, Int>()
    val keys = heights.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val index = k.toIntOrNull() ?: continue
        out[index] = heights.optInt(k)
    }
    return out
}

private fun persistRiverHeights(
    cacheDir: File,
    key: String,
    heights: Map<Int, Int>
) {
    runCatching {
        val heightsJson = JSONObject()
        for ((index, height) in heights) {
            heightsJson.put(index.toString(), height)
        }
        val root = JSONObject()
        root.put("key", key)
        root.put("h", heightsJson)
        File(cacheDir, "river.heights.json").writeText(root.toString())
    }
}

// ── The WebView: scroll observation + selection wrap ─────────

/**
 * The river's WebView (v1.5.34): [onScrollChanged] is the
 * host's scroll observation (item 0.2 — the view reports its
 * own scrolling; no JS listener is involved), and the
 * selection ActionMode the WebView starts is WRAPPED so
 * Xreader's actions ride the stock floating toolbar (R4).
 */
private class RiverWebView(
    context: android.content.Context
) : WebView(context) {

    @Volatile
    var scrollListener: ((scrollY: Int) -> Unit)? = null

    @Volatile
    var selectionActionHandler: ((EpubSelectionMenuAction) -> Unit)? =
        null

    /** v1.5.35 (restore §C.4): the reader's hand on the
     * page — fired on every ACTION_DOWN, observed only
     * (the event is never consumed here). The host wires
     * it to the restore sequence's permanent cancellation:
     * once the reader touches the page, the place is
     * theirs again and no restore application may follow. */
    @Volatile
    var touchDownHandler: (() -> Unit)? = null

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked ==
            android.view.MotionEvent.ACTION_DOWN
        ) {
            touchDownHandler?.invoke()
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        scrollListener?.invoke(t)
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrapSelectionCallback(callback))

    override fun startActionMode(
        callback: ActionMode.Callback,
        type: Int
    ): ActionMode? =
        super.startActionMode(wrapSelectionCallback(callback), type)

    private fun wrapSelectionCallback(
        callback: ActionMode.Callback
    ): ActionMode.Callback {
        val handler: (EpubSelectionMenuAction) -> Unit = { action ->
            selectionActionHandler?.invoke(action)
        }
        return if (callback is ActionMode.Callback2) {
            RiverSelectionCallback2(callback, handler)
        } else {
            RiverSelectionCallback(callback, handler)
        }
    }
}

/** Console logging, permanently (item 0.4): a river page's
 * console — including every script error the book's own XHTML
 * provokes — lands in the log under one tag, the diagnostic
 * surface v1.5.32/33 never had. */
private class RiverWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
        if (message != null) {
            val line = "console ${message.messageLevel()}: " +
                "${message.message()} " +
                "(${message.sourceId()}:${message.lineNumber()})"
            when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR ->
                    Log.e(RIVER_LOG_TAG, line)
                ConsoleMessage.MessageLevel.WARNING ->
                    Log.w(RIVER_LOG_TAG, line)
                else -> Log.i(RIVER_LOG_TAG, line)
            }
        }
        return true
    }
}

/** §6.4's position line: only for an image that sits in a
 * plate section (the merger's plate sequence) — an inline
 * illustration inside a text chapter gets NO line rather than
 * a wrong one. */
private fun plateLabelFor(
    document: RiverDocument,
    containerPath: String
): String? {
    if (document.plateSections.isEmpty()) return null
    val image = document.images.firstOrNull {
        it.containerPath == containerPath
    } ?: return null
    val rank = document.plateSections.indexOf(image.section)
    if (rank < 0) return null
    return "Plate ${rank + 1} of ${document.plateSections.size}"
}

/**
 * The river's imperative handle: EpubBody's jump / volume-turn
 * collectors and its auto-scroll surface act on the WebView
 * through these slots; [EpubRiverView] fills them when its
 * WebView exists and clears them on release. Every slot is
 * fire-and-forget except [advanceBy], whose verdict the
 * auto-scroll engine's own coroutine consumes (end-of-book) —
 * an async answer on a background caller, never a gesture or
 * main-thread wait (§2.3). v1.5.34 adds the R4/R6 slots
 * (selection fetch, follow) and the plate-arbitration slots
 * (item 0.3/3c).
 */
internal class EpubRiverDriver {
    @Volatile
    var goToProgression: ((
        section: Int,
        progression: Double?,
        markQuery: String?,
        markCharOffset: Int
    ) -> Unit)? = null

    @Volatile
    var stepBy: ((dyPx: Int) -> Unit)? = null

    @Volatile
    var advanceBy: ((dyPx: Int) -> Boolean)? = null

    @Volatile
    var viewportHeightPx: (() -> Int)? = null

    /** Host-side plate probe for a clean tap: true when the
     * tap hit a book image and the viewer opened (the tap is
     * consumed — no chrome toggle follows it). */
    @Volatile
    var probePlateTap: ((xFraction: Float, yFraction: Float) -> Boolean)? =
        null

    /** Whether the plate viewer is currently open (taps do
     * not reach the chrome while it is). */
    @Volatile
    var plateViewerOpen: (() -> Boolean)? = null

    /** When the last plate claim landed (either road) — the
     * chrome toggle's arbitration clock for the JS fallback
     * road, whose claim posts asynchronously. */
    @Volatile
    var lastPlateClaimMs: (() -> Long)? = null

    /** ONE on-demand selection pull (R4): the result arrives
     * on the callback, once, only because an action asked. */
    @Volatile
    var fetchSelection: (((SelectionSnapshot?) -> Unit) -> Unit)? = null

    @Volatile
    var clearSelection: (() -> Unit)? = null

    /** Read-aloud follow (R6): keep the spoken section in
     * view (gently — only when it is off-screen). */
    @Volatile
    var followSection: ((section: Int) -> Unit)? = null

    /** v1.5.35 (restore §C.6): ONE final anchor pull at
     * exit, sequenced by the ON_STOP observer BEFORE its
     * flush — the plug for the mid-fling hole (the last
     * loop pull can be a mid-fling position; this pull
     * reads the resting place). The implementation pulls
     * once and then invokes [done]; a caller that cannot
     * wait forever pairs it with its own short timeout. */
    @Volatile
    var requestFinalPull: ((done: () -> Unit) -> Unit)? = null
}

/** The JS→host event sink, slimmed by the REA §5 rebuild:
 * String/no-arg payloads ONLY, events only, never a verdict a
 * gesture waits on, and NEVER the position of record (that is
 * the host's pull now). The three ping signatures are the
 * self-check's permanent diagnostics — between them they
 * discriminate a dead interface, a dead overload set and a
 * dead (Int, Int) coercion, the three suspects v1.5.33's
 * forensics could not separate.
 * PUBLIC class, deliberately: Chromium invokes the
 * @JavascriptInterface methods by reflection from its own
 * package — a non-public declaring class makes every call
 * throw IllegalAccessException (v1.5.32's dead bridge,
 * V1532-PROOF-FORENSICS). */
class RiverBridge(
    private val handler: Handler,
    private val onReady: () -> Unit,
    private val onImageTap: (String) -> Unit,
    private val onFootnoteTap: (String) -> Unit,
    private val onHighlightTap: (String) -> Unit
) {
    @Volatile
    var pingNoArg: Int = 0
    @Volatile
    var pingString: Int = 0
    @Volatile
    var pingInts: Int = 0

    @JavascriptInterface
    fun onReady() {
        handler.post { onReady() }
    }

    /** A tap on a book image (§6): carries the image's
     * fake-origin URL; the host resolves it to the container
     * path and opens the plate viewer. The FALLBACK road —
     * the host's HitTestResult probe is primary (item 0.3);
     * this survives for what the probe cannot see (SVG
     * <image>). An event, never a verdict. */
    @JavascriptInterface
    fun onImageTap(url: String) {
        handler.post { onImageTap(url) }
    }

    /** A footnote link tap (R6): carries the note element's
     * namespaced id; the host records the reader's anchor and
     * scrolls the note into view — jump-and-return. */
    @JavascriptInterface
    fun onFootnoteTap(targetId: String) {
        handler.post { onFootnoteTap(targetId) }
    }

    /** A tap on a rendered highlight <mark> (R4): carries the
     * highlight's store id; the host opens its note/delete
     * menu through the selection state machine. */
    @JavascriptInterface
    fun onHighlightTap(highlightId: String) {
        handler.post { onHighlightTap(highlightId) }
    }

    @JavascriptInterface
    fun ping() {
        pingNoArg++
    }

    @JavascriptInterface
    fun ping(value: String) {
        pingString++
    }

    @JavascriptInterface
    fun ping(a: Int, b: Int) {
        pingInts++
    }
}

private class RiverWebViewClient(
    private val session: ReadiumEpubSession,
    private val document: RiverDocument,
    private val onPageFinished: () -> Unit,
    private val onPageCommitVisible: () -> Unit,
    private val onExternalLink: (Uri) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.scheme == "https" && url.host == RIVER_HOST) {
            val path = url.path
            // v1.5.34 (item 0.1): the document route — the
            // merged XHTML served BY URL, the load mode every
            // working bridge in the app rides.
            if (path == "/book") {
                return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    ByteArrayInputStream(
                        document.html.toByteArray(Charsets.UTF_8)
                    )
                )
            }
            // v1.5.34 (R7): the windowed book's per-section
            // files, fetched by the page script as the window
            // moves. A missing file answers EMPTY (the fetch
            // resolves; the section stays a gap) rather than
            // hanging the fill.
            if (path != null && path.startsWith("/sec/")) {
                val index = path.removePrefix("/sec/").toIntOrNull()
                val dir = document.cacheDirPath
                val bytes = if (index != null && dir != null &&
                    document.windowed
                ) {
                    val file = File(dir, "sec-$index.html")
                    if (file.isFile) file.readBytes() else null
                } else {
                    null
                }
                return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    ByteArrayInputStream(bytes ?: ByteArray(0))
                )
            }
        }
        val containerPath = riverContainerPath(url) ?: return null
        val bytes = resourceBytes(session.publication, document, containerPath)
            ?: return null
        return WebResourceResponse(
            mimeTypeFor(session.publication, document, containerPath),
            null,
            ByteArrayInputStream(bytes)
        )
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val isRiver = url.scheme == "https" && url.host == RIVER_HOST
        if (isRiver) {
            // The document itself may load; a main-frame jump at
            // a resource URL would replace the book — block it.
            // Section files are fetches, never navigations.
            // In-document anchors never reach this road (the
            // bridge handles them in-page).
            if (url.path == "/book") return false
            return true
        }
        // R1's minimal external policy: anything off-origin
        // leaves the book for the system (full policy is R6).
        if (url.scheme == "http" || url.scheme == "https" ||
            url.scheme == "mailto"
        ) {
            onExternalLink(url)
        }
        return true
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        onPageCommitVisible()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onPageFinished()
    }

    companion object {
        private const val RIVER_HOST = "xreader.river"
    }
}

/** The decoded container path a fake-origin URL names, or null
 * when the URL is not a river resource request. */
private fun riverContainerPath(url: Uri): String? {
    if (url.scheme != "https" || url.host != "xreader.river") return null
    val path = url.path ?: return null
    if (!path.startsWith("/res/")) return null
    val decoded = runCatching { Uri.decode(path.removePrefix("/res/")) }
        .getOrNull() ?: return null
    return decoded.takeIf { it.isNotEmpty() }
}

/** Container bytes for a decoded container path: the manifest
 * link road first; the direct container road behind it (the
 * user font rides the session's container wrap under a
 * synthetic href that is no manifest link — the same road the
 * navigator's server uses today).
 *
 * `Resource.read()` is a suspend call in 3.1.2 and
 * `shouldInterceptRequest` is a synchronous WebView callback
 * that ALWAYS runs on WebView's own IO thread — never the main
 * thread, never a gesture path — so bridging with runBlocking
 * here is exactly the callback's contract, not a §2.3 breach. */
private fun resourceBytes(
    publication: Publication,
    document: RiverDocument,
    containerPath: String
): ByteArray? = kotlinx.coroutines.runBlocking {
    resourceBytesSuspend(publication, document, containerPath)
}

internal suspend fun resourceBytesSuspend(
    publication: Publication,
    document: RiverDocument,
    containerPath: String
): ByteArray? {
    val link = document.linksByKey[canonicalHrefKey(containerPath)]
    if (link != null) {
        val bytes = runCatching {
            publication.get(link)?.read()?.getOrNull()
        }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) return bytes
    }
    return runCatching {
        val url = Url(containerPath) ?: return@runCatching null
        val direct = runCatching {
            publication.get(url)?.read()?.getOrNull()
        }.getOrNull()
        if (direct != null && direct.isNotEmpty()) return direct
        val resolved = publication.baseUrl?.resolve(url)
            ?: return@runCatching null
        runCatching {
            publication.get(resolved)?.read()?.getOrNull()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun mimeTypeFor(
    publication: Publication,
    document: RiverDocument,
    containerPath: String
): String {
    val mediaType = document.linksByKey[canonicalHrefKey(containerPath)]
        ?.mediaType
    if (mediaType != null) return "${mediaType.type}/${mediaType.subtype}"
    return when (containerPath.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "css" -> "text/css"
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "xml" -> "application/xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
