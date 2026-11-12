package app.xreader.core.error

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (A1, Finding 7's D1 instrument), re-derived for the
 * v1.5.21 note shape: CrashGuard notes render the whole cause
 * chain — each link's bounded message and its top 5 stack
 * frames as className.methodName ONLY (no file names or line
 * numbers). The separate [CrashGuard.topFrames] helper keeps
 * its original top-3 shape for bounded log details.
 */
class V1510CrashGuardFramesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A throwable whose stack is fully hand-built, so the test
     * knows every frame, file name and line number in advance. */
    private fun synthetic(): NullPointerException {
        val t = NullPointerException("secret-npe-message-xyz")
        t.stackTrace = arrayOf(
            StackTraceElement("com.example.alpha.Foo", "bar", "Foo.kt", 31337),
            StackTraceElement("com.example.beta.Baz", "qux", "Baz.kt", 42424),
            StackTraceElement("com.example.gamma.Quux", "zap", "Quux.kt", 55555),
            StackTraceElement("com.example.delta.Extra", "more", "Extra.kt", 66666)
        )
        return t
    }

    @Test
    fun `crash note carries the top 5 chain frames as class plus method`() {
        val note = CrashGuard.formatNote(0L, "worker-1", synthetic())
        val framesSection = note.substringAfter("Frames:\n")
        val frameLines = framesSection.lines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "com.example.alpha.Foo.bar",
                "com.example.beta.Baz.qux",
                "com.example.gamma.Quux.zap",
                "com.example.delta.Extra.more"
            ),
            frameLines
        )
        // All four synthetic frames fit under the five-frame
        // chain cap and are recorded.
        assertTrue(note.contains("com.example.delta.Extra"))
    }

    @Test
    fun `crash note contains the bounded message and no line numbers or file names`() {
        val note = CrashGuard.formatNote(0L, "worker-1", synthetic())
        assertTrue(note.contains("Message: secret-npe-message-xyz"))
        assertFalse(note.contains("31337"))
        assertFalse(note.contains("42424"))
        assertFalse(note.contains("55555"))
        assertFalse(note.contains("66666"))
        assertFalse(note.contains(".kt"))
        // The pre-A1 evidence is all still there.
        assertTrue(note.contains("java.lang.NullPointerException"))
        assertTrue(note.contains("Thread: worker-1"))
    }

    @Test
    fun `fewer than 3 frames lists what exists`() {
        val t = IllegalStateException("x")
        t.stackTrace = arrayOf(
            StackTraceElement("com.example.Only", "one", "Only.kt", 11111)
        )
        val note = CrashGuard.formatNote(0L, "main", t)
        val frameLines = note.substringAfter("Frames:\n")
            .lines().filter { it.isNotBlank() }
        assertEquals(listOf("com.example.Only.one"), frameLines)
    }

    @Test
    fun `topFrames returns class plus method only`() {
        assertEquals(
            listOf(
                "com.example.alpha.Foo.bar",
                "com.example.beta.Baz.qux",
                "com.example.gamma.Quux.zap"
            ),
            CrashGuard.topFrames(synthetic())
        )
    }

    @Test
    fun `contained note carries the marker, the site, the frames and bounded message`() {
        val note = CrashGuard.formatContainedNote(
            0L, "worker-2", "appScope", synthetic()
        )
        assertTrue(note.contains(CrashGuard.CONTAINED_MARKER))
        assertTrue(note.contains("Site: appScope"))
        assertTrue(note.contains("com.example.alpha.Foo.bar"))
        assertTrue(note.contains("Message: secret-npe-message-xyz"))
        assertFalse(note.contains("31337"))
    }

    @Test
    fun `contained note writer writes no crash marker`() {
        val filesDir = tmp.newFolder("files")
        val written = CrashGuard.writeContainedNote(
            filesDir = filesDir,
            threadName = "worker-3",
            site = "render-runtime:tier",
            throwable = synthetic(),
            nowMillis = 123456789L
        )
        assertTrue(written.exists())
        assertTrue(written.name.startsWith("contained-"))
        val text = written.readText()
        assertTrue(text.contains(CrashGuard.CONTAINED_MARKER))
        assertTrue(text.contains("com.example.beta.Baz.qux"))
        // The recovery screen's trigger must NOT exist: contained
        // failures never summon it.
        assertFalse(File(filesDir, CrashGuard.MARKER_FILE).exists())
    }
}
