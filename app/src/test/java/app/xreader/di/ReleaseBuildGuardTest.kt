package app.xreader.di

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.1 regression tripwire (the launch-crash fix): v1.4.0 died in
 * AppContainer.<init> on device — "TypeReference constructed
 * without actual type information" — because the release build's
 * R8 pass stripped the generic Signature Injekt's TypeReference
 * reads reflectively, and the unminified JVM suite could not see
 * it. The user's permanent fix is that release builds do NOT
 * minify or shrink at all; the donor (Komikku) injekt keeps are
 * recorded in proguard-rules.pro as documentation in case
 * minification is ever re-enabled deliberately.
 *
 * These source-structure assertions pin BOTH halves so neither
 * can silently regress: the release build type must keep the
 * shrinker off, and the proguard file must keep the injekt rules
 * the mechanism needs. Same static-gate style as
 * ScreenRootStructureTest — no device claimed or needed.
 */
class ReleaseBuildGuardTest {

    private fun projectFile(relative: String): String {
        val roots = listOf(
            File("."),
            File("app"),
            File(System.getProperty("user.dir") ?: ".")
        )
        val file = roots.map { it.resolve(relative) }.firstOrNull { it.exists() }
            ?: throw AssertionError("project file not found from any known root: $relative")
        return file.readText()
    }

    @Test
    fun `release build never minifies or shrinks`() {
        val gradle = projectFile("build.gradle.kts")
        val releaseStart = gradle.indexOf("release {")
        assertTrue("release build type not found", releaseStart >= 0)
        val block = gradle.substring(
            releaseStart,
            gradle.indexOf("\n    }", releaseStart)
        )
        assertTrue(
            "isMinifyEnabled must stay false (v1.4.0 launch crash; user rule: size is not a constraint)",
            block.contains("isMinifyEnabled = false")
        )
        assertTrue(
            "isShrinkResources must stay false (v1.4.0 launch crash; user rule: size is not a constraint)",
            block.contains("isShrinkResources = false")
        )
    }

    @Test
    fun `proguard rules keep the injekt mechanism documented`() {
        val rules = projectFile("proguard-rules.pro")
        assertTrue(
            "injekt classes must stay kept (donor rules)",
            rules.contains("-keep class uy.kohesive.injekt.** { *; }")
        )
        assertTrue(
            "the generic Signature attribute is the mechanism — it must stay kept",
            rules.contains("-keepattributes Signature")
        )
        assertTrue(
            "TypeReference subclasses carry the actual type information — they must stay kept",
            rules.contains("class * extends uy.kohesive.injekt.api.TypeReference")
        )
    }
}
