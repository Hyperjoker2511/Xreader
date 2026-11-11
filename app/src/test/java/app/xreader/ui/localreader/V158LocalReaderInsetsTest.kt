package app.xreader.ui.localreader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (F2): the local readers pad THEMSELVES — the second
 * half of the F1+F2 change (Finding 5 Q4.3's latent trap:
 * registering the route immersive WITHOUT the readers' own
 * inset padding drops the PDF slider onto the gesture inset).
 * Structure-level, matching the plan's verification: both
 * readers' chrome declares statusBarsPadding (top bars) and
 * navigationBarsPadding (bottom bars), the novel reader's
 * pattern.
 */
class V158LocalReaderInsetsTest {

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

    @Test
    fun `both local readers declare their own system-bar paddings`() {
        for (name in listOf("EpubBookReader.kt", "PdfDocumentReader.kt")) {
            val source = sourceFile(name).readText()
            assertTrue(
                "$name top bar must declare statusBarsPadding",
                source.contains("statusBarsPadding")
            )
            assertTrue(
                "$name bottom bar must declare navigationBarsPadding",
                source.contains("navigationBarsPadding")
            )
        }
    }

    /**
     * v1.5.9 RECONCILIATION (F-2): the EPUB reader's geometry
     * contract changed from "Scaffold bars pad the body" to the
     * OVERLAY contract — the Scaffold keeps only the snackbar
     * host with zero content insets, the comfort host receives
     * PaddingValues(0.dp), the Scaffold's live padding is NOT
     * applied to the body, and the chrome floats over a
     * constant-size body in the overlay host. The self-padding
     * assertions above still hold (both bars still self-pad);
     * this test pins the new half of the contract.
     */
    @Test
    fun `epub reader follows the overlay geometry contract`() {
        val source = sourceFile("EpubBookReader.kt").readText()
        assertTrue(
            "Scaffold content insets are zero",
            source.contains("contentWindowInsets = WindowInsets(0)")
        )
        assertTrue(
            "comfort host receives PaddingValues(0.dp)",
            source.contains("Modifier.padding(PaddingValues(0.dp))")
        )
        assertTrue(
            "the Scaffold's live padding is never applied to the body",
            !source.contains("Modifier.padding(padding)")
        )
        assertTrue(
            "chrome overlays the body in the overlay host",
            source.contains("EpubChromeOverlayHost") &&
                source.contains("Alignment.TopCenter") &&
                source.contains("Alignment.BottomCenter")
        )
    }
}
