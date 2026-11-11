package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.reader.TtsEngine
import app.xreader.di.AppContainer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.9 (F-5, Finding 4 P6): opening the reader constructs
 * ZERO TTS engines. The ViewModel's engine factory seam counts
 * constructions: reading (and even collecting) the plain
 * `ttsPlaying` flow the EPUB reader now binds builds nothing;
 * the first read-aloud toggle builds exactly one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V159TtsConstructionCounterTest {

    private class FakeTtsEngine : TtsEngine {
        override val isReady: Boolean = true
        override fun setListener(listener: TtsEngine.Listener?) = Unit
        override fun speak(utteranceId: String, text: String) = Unit
        override fun setRate(rate: Float) = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    @Test
    fun `open constructs zero engines, first toggle constructs one`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        // A book id that does not exist: the open path runs and
        // fails honestly — what matters is what it does NOT do
        // on the way (bind the device TTS service).
        val viewModel = LocalDocReaderViewModel(
            container, bookId = 987_654_321L, startTargetJson = null
        )
        val constructions = AtomicInteger(0)
        viewModel.ttsEngineFactory = {
            constructions.incrementAndGet()
            FakeTtsEngine()
        }
        // The EPUB reader's open path: read the progress/settings
        // flows and the playing flow it now collects.
        assertEquals(false, viewModel.ttsPlaying.value)
        assertEquals(null, viewModel.epubProgress.value)
        assertEquals(
            "opening constructs zero TTS engines", 0, constructions.get()
        )
        viewModel.toggleReadAloud()
        assertEquals(
            "first read-aloud use constructs exactly one engine",
            1, constructions.get()
        )
    }
}
