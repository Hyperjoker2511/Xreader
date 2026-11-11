package app.xreader.ui.localreader

import android.annotation.SuppressLint
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.engine.RiverDocument
import app.xreader.data.engine.canonicalHrefKey
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.5.32 (EPUB RIVER, plan §6 — the owner's settled
 * requirement "in epub illustrations zoom should work",
 * pulled forward from R5): the full-screen plate viewer.
 *
 * Machinery is the household standard the plan names: the
 * Tachiyomi-fork SubsamplingScaleImageView already in the app
 * (the Komikku reader's page view is built on it) — transform
 * during the gesture, tile decoding that sharpens
 * progressively and is sharp at rest, animated double-tap
 * zoom — fed a FILE, so memory stays bounded at any zoom.
 *
 * The file is the book's ORIGINAL image bytes (§6.3):
 * extracted once per plate from the Readium container
 * (`publication.get(...)`, the same byte road the river's
 * interceptor serves) into the book's cache directory and
 * reused thereafter. Never a screenshot, never the WebView's
 * decoded bitmap, never a screen-sized downscale — pinch into
 * a colour plate and the plate's real pixels are what
 * sharpen.
 *
 * SVG plates (rare) have no SSIV road: they fall back to a
 * WebView page rendering the single SVG with the WebView's
 * own zoom controls (§6.3's stated fallback). An SSIV load
 * error lands on the same fallback.
 *
 * The viewer is an overlay inside the reader: the river
 * WebView beneath it never navigates, so dismissing this
 * viewer returns to the exact reading position by
 * construction (§6.4). Chrome is a tap-toggled close chip +
 * the plate's position line when the merger knows it.
 */
@Composable
internal fun EpubPlateViewer(
    session: ReadiumEpubSession,
    document: RiverDocument,
    containerPath: String,
    plateLabel: String?,
    cacheDir: File,
    onDismiss: () -> Unit
) {
    val mediaType = remember(document, containerPath) {
        document.linksByKey[canonicalHrefKey(containerPath)]?.mediaType
    }
    val isSvg = containerPath.substringAfterLast('.', "")
        .equals("svg", ignoreCase = true) ||
        mediaType?.subtype?.contains("svg") == true

    var plateFile by remember(containerPath) {
        mutableStateOf<File?>(null)
    }
    var failed by remember(containerPath) { mutableStateOf(false) }
    var webFallback by remember(containerPath) {
        mutableStateOf(isSvg)
    }
    var affordances by remember { mutableStateOf(true) }
    val toggleAffordances = rememberUpdatedState {
        affordances = !affordances
    }

    // §6.3: extract the ORIGINAL bytes once, then serve the
    // viewer from the cache file.
    LaunchedEffect(containerPath) {
        val dir = File(cacheDir, "plates")
        val ext = containerPath.substringAfterLast('.', "")
            .lowercase().filter { it.isLetterOrDigit() }.take(8)
        val stem = MessageDigest.getInstance("SHA-256")
            .digest(containerPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val target = File(
            dir, if (ext.isEmpty()) stem else "$stem.$ext"
        )
        if (target.isFile && target.length() > 0L) {
            plateFile = target
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) {
            resourceBytesSuspend(
                session.publication, document, containerPath
            )
        }
        if (bytes == null) {
            failed = true
        } else {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    target.writeBytes(bytes)
                    target
                }.getOrNull()
            }
            if (written != null) plateFile = written else failed = true
        }
    }

    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
    ) {
        val file = plateFile
        when {
            file != null && !webFallback -> SsivPlate(
                file = file,
                onTap = { toggleAffordances.value() },
                onError = { webFallback = true }
            )
            file != null -> WebPlate(
                file = file,
                onTap = { toggleAffordances.value() }
            )
            failed -> Text(
                "This illustration couldn't be opened.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            )
            else -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (affordances) {
            Row(
                Modifier.align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(
                            1.dp,
                            Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(50)
                        )
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Close",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                if (plateLabel != null) {
                    Text(
                        plateLabel,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** The SSIV road (§6.2): fitted at open, double-tap to 2×
 * the fitted scale, pinch to 5× — the donor's own numbers
 * (ReaderPageImageView.setupZoom). A single tap toggles the
 * viewer's affordances; SSIV's click fires only for a real
 * tap, never mid-pinch. */
@Composable
private fun SsivPlate(
    file: File,
    onTap: () -> Unit,
    onError: () -> Unit
) {
    val tapState = rememberUpdatedState(onTap)
    val errorState = rememberUpdatedState(onError)
    val loaded = remember { object { var path: String? = null } }
    AndroidView(
        factory = { ctx ->
            SubsamplingScaleImageView(ctx).apply {
                setMinimumScaleType(
                    SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                )
                setDoubleTapZoomStyle(
                    SubsamplingScaleImageView.ZOOM_FOCUS_CENTER
                )
                setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                setMaxTileSize(2048)
                setMinimumTileDpi(180)
                setOnImageEventListener(
                    object :
                        SubsamplingScaleImageView
                            .DefaultOnImageEventListener() {
                        override fun onReady() {
                            setDoubleTapZoomScale(scale * 2f)
                            maxScale = scale * 5f
                        }

                        override fun onImageLoadError(e: Exception) {
                            errorState.value()
                        }
                    }
                )
                setOnClickListener { tapState.value() }
            }
        },
        update = { view ->
            val path = file.absolutePath
            if (loaded.path != path) {
                loaded.path = path
                // This fork's ImageSource.uri takes the
                // Context first (it resolves content URIs
                // through the resolver for dimensions).
                view.setImage(
                    ImageSource.uri(view.context, Uri.fromFile(file))
                )
            }
        },
        onRelease = { it.recycle() },
        modifier = Modifier.fillMaxSize()
    )
}

/** The SVG / SSIV-failure fallback (§6.3): the single image
 * in a WebView with the WebView's own pinch zoom. The
 * tap-toggle rides a watch-only gesture detector — it never
 * consumes a touch, so the WebView's zoom keeps every
 * gesture. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPlate(
    file: File,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val tapState = rememberUpdatedState(onTap)
    val loaded = remember { object { var path: String? = null } }
    AndroidView(
        factory = {
            WebView(context).apply {
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = true
                setBackgroundColor(android.graphics.Color.BLACK)
                val detector = GestureDetector(
                    context,
                    object :
                        GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(
                            e: MotionEvent
                        ): Boolean {
                            tapState.value()
                            return false
                        }
                    }
                )
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
            }
        },
        update = { view ->
            val path = file.absolutePath
            if (loaded.path != path) {
                loaded.path = path
                view.loadUrl(Uri.fromFile(file).toString())
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize()
    )
}
