package app.xreader.ui.localreader

import android.app.Application
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.9 delta (B-2, the F-2 acceptance check): with the
 * chrome SHOWN over the EPUB surface, a chapter's last line
 * must be readable at chapter end — the overlay must not
 * permanently cover text — WITHOUT reintroducing F-2's
 * surface resize. The mechanism: the navigator WebView keeps
 * its size; its bottom PADDING (clip off) insets the internal
 * scroll viewport by exactly the visible bottom-chrome
 * height, and drops to 0 when the chrome hides.
 *
 * Pinned here at the buildable seam: the pure inset rule
 * (shown → chrome height, hidden → 0), the application onto
 * a real WebView in a fragment-shaped view tree (padding set,
 * clipping off, re-application tracks changes), and the
 * production wiring in EpubBookReader.kt (height measured off
 * the gated chrome, inset computed by the rule, applied to
 * the fragment's WebView). The device-side readability itself
 * is a phone-proof item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V159EpubBottomInsetTest {

    @Test
    fun `inset equals the chrome height when shown and zero when hidden`() {
        assertEquals(184, epubBottomScrollInsetPx(true, 184))
        assertEquals(0, epubBottomScrollInsetPx(false, 184))
        assertEquals(0, epubBottomScrollInsetPx(true, 0))
        assertEquals(0, epubBottomScrollInsetPx(false, 0))
        assertEquals(
            "a bogus negative measurement clamps to zero",
            0, epubBottomScrollInsetPx(true, -12)
        )
    }

    @Test
    fun `inset lands on the webview as unpadded-clip bottom padding`() {
        val context = RuntimeEnvironment.getApplication()
        val webView = WebView(context)
        // A fragment-shaped tree: wrappers around the WebView,
        // as Readium's navigator view hierarchy presents.
        val inner = FrameLayout(context).apply { addView(webView) }
        val root = FrameLayout(context).apply { addView(inner) }

        assertSame(webView, findNavigatorWebView(root))
        assertSame(webView, findNavigatorWebView(webView))

        applyEpubBottomInset(root, 184)
        assertEquals(184, webView.paddingBottom)
        assertFalse(
            "content must be allowed to draw into the padded" +
                " region while scrolling (clipToPadding off)",
            webView.clipToPadding
        )
        assertEquals(0, webView.paddingTop)
        assertEquals(0, webView.paddingLeft)
        assertEquals(0, webView.paddingRight)

        applyEpubBottomInset(root, 0)
        assertEquals(
            "hiding the chrome returns the inset to zero",
            0, webView.paddingBottom
        )

        // A tree with no WebView is a safe no-op.
        val bare = FrameLayout(context).apply {
            addView(FrameLayout(context))
        }
        applyEpubBottomInset(bare, 120)
        applyEpubBottomInset(null, 120)
        assertEquals(0, (bare.getChildAt(0) as ViewGroup).paddingBottom)
    }

    @Test
    fun `epub body wires measured chrome height to the webview inset`() {
        val source = sourceFile("EpubBookReader.kt").readText()
        assertTrue(
            "the overlay host reports the gated chrome's height",
            source.contains("onBottomChromeHeightChanged")
        )
        assertTrue(
            "the host measures the bottom chrome",
            source.contains("Modifier.onSizeChanged")
        )
        assertTrue(
            "the body receives the rule-computed inset",
            source.contains("epubBottomScrollInsetPx(")
        )
        assertTrue(
            "the inset is applied to the fragment's webview",
            source.contains("applyEpubBottomInset(navigator?.view")
        )
        assertTrue(
            "F-2 stands: the surface itself is not resized by" +
                " the inset path (no padding on the AndroidView)",
            !source.contains("applyEpubBottomInset(container")
        )
    }

    private fun sourceFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(
                dir,
                "app/src/main/java/app/xreader/ui/localreader/$name"
            )
            if (candidate.isFile) return candidate
            val direct = File(dir, "src/main/java/app/xreader/ui/localreader/$name")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $name from ${System.getProperty("user.dir")}")
    }
}
