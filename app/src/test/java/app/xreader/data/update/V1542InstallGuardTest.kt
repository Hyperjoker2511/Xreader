package app.xreader.data.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.42 hotfix pins (Defect 1): tapping Install crashed with
 * SecurityException "Need to declare
 * android.permission.REQUEST_INSTALL_PACKAGES to call this api"
 * because the manifest never declared that permission and
 * [ApkInstaller.canInstall] let the platform's throw escape.
 *
 * Two pins, so neither half can silently regress:
 *  1. the manifest declares REQUEST_INSTALL_PACKAGES as a
 *     top-level uses-permission;
 *  2. the canInstall guard itself can never throw — any
 *     failure of the platform query reads as "not granted"
 *     (the polite settings route), never as a crash.
 */
class V1542InstallGuardTest {

    // ── The guard ──────────────────────────────────────────

    @Test
    fun `canInstall passes the query result through`() {
        assertTrue(ApkInstaller.canInstall { true })
        assertFalse(ApkInstaller.canInstall { false })
    }

    @Test
    fun `canInstall never throws - a throwing query reads as not granted`() {
        assertFalse(
            ApkInstaller.canInstall {
                throw SecurityException(
                    "Need to declare " +
                        "android.permission.REQUEST_INSTALL_PACKAGES " +
                        "to call this api"
                )
            }
        )
        assertFalse(
            ApkInstaller.canInstall {
                throw IllegalStateException("platform gone sideways")
            }
        )
    }

    // ── The manifest declaration ───────────────────────────

    private fun manifest(): String {
        val rel = "src/main/AndroidManifest.xml"
        val candidates = mutableListOf(File(rel), File("app/$rel"))
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            dir?.let {
                candidates += it.resolve(rel)
                candidates += it.resolve("app/$rel")
            }
            dir = dir?.parentFile
        }
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "AndroidManifest.xml not found from any known root"
            )
        return file.readText()
    }

    @Test
    fun `manifest declares the install packages permission`() {
        val text = manifest()
        val element = "<uses-permission android:name=" +
            "\"android.permission.REQUEST_INSTALL_PACKAGES\""
        val at = text.indexOf(element)
        assertTrue(
            "REQUEST_INSTALL_PACKAGES is not declared — the Install " +
                "tap crashes with SecurityException",
            at >= 0
        )
        assertTrue(
            "the permission must be a top-level element, before <application",
            at < text.indexOf("<application")
        )
    }
}
