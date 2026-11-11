/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): the transplanted Komikku ReaderActivity — kept
 * as its own Activity at the settled boundary, with the donor's
 * intent contract verbatim (newIntent(context, mangaId, chapterId,
 * page); extras "manga" / "chapter" / "page"; FLAG_ACTIVITY_CLEAR_TOP)
 * and the donor Activity's window duties: it owns
 * FLAG_KEEP_SCREEN_ON and the window brightness, both driven by
 * the reader preferences it resolves from Injekt — the donor's
 * own `private val readerPreferences = Injekt.get<ReaderPreferences>()`.
 * v1.5.7 (Komikku full): what it hosts is now the donor's own
 * shape — a View-based viewer (DonorReaderView: RecyclerView
 * pager/webtoon with SSIV page views) under the Compose chrome
 * (KomikkuReaderScreen), as in the donor ReaderActivity's
 * viewer + composeOverlay split.
 * v1.5.11 (ANR forensics, Part 2): the blocking appearance
 * read is gone from onCreate — the theme collects
 * appearancePrefs.state in the composition (see below).
 */
package app.xreader.komikku

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import app.xreader.komikku.presentation.KomikkuReaderScreen
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.ui.theme.XreaderTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomikkuReaderActivity : ComponentActivity() {

    // Donor ReaderActivity: preferences resolve from Injekt.
    private val readerPreferences = Injekt.get<ReaderPreferences>()

    private lateinit var viewModel: KomikkuReaderViewModel

    /** Volume-key page turns (+1 next / −1 previous), shared with
     * the hosted surface — the donor routes these through its
     * ViewerConfig (volumeKeysEnabled / volumeKeysInverted). */
    private val _volumeTurns = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val volumeTurns: SharedFlow<Int> = _volumeTurns

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra("manga", -1L)
        val chapterId = intent.getLongExtra("chapter", -1L)
        val page = if (intent.hasExtra("page")) intent.getIntExtra("page", 0) else null
        if (mangaId < 0) {
            finish()
            return
        }
        viewModel = ViewModelProvider(
            this,
            KomikkuReaderViewModel.factory(
                application,
                bookId = mangaId,
                chapterId = chapterId,
                page = page
            )
        )[KomikkuReaderViewModel::class.java]

        applyWindowPreferences()
        enableEdgeToEdge()
        // v1.5.11 (ANR forensics, Part 2): the appearance is
        // COLLECTED in the composition instead of read with
        // a blocking call on the main thread — reader open never
        // parks the main thread on a preferences read. Seam
        // adaptation of the Gemini return: appearancePrefs
        // .state is a plain Flow here (AppContainer.kt:138 /
        // AppearancePrefs), so collectAsState takes the
        // AppearanceState() initial value the shell's own
        // collectors use, instead of the return's no-arg
        // StateFlow form.
        val appearanceState = (application as app.xreader.XreaderApp)
            .container.appearancePrefs.state
        setContent {
            val appearance by appearanceState.collectAsState(
                initial = app.xreader.data.prefs.AppearanceState()
            )
            val darkTheme =
                appearance.themeMode != app.xreader.data.prefs.ThemeMode.LIGHT
            XreaderTheme(darkTheme = darkTheme) {
                KomikkuReaderScreen(
                    viewModel = viewModel,
                    volumeTurns = volumeTurns,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) applyWindowPreferences()
    }

    /** The donor Activity's window duties, driven by the scoped
     * (per-book over global) preferences once the VM exists, the
     * Injekt global preferences before it. */
    private fun applyWindowPreferences() {
        val prefs = if (::viewModel.isInitialized) {
            viewModel.scopedPreferences
        } else {
            readerPreferences
        }
        if (prefs.keepScreenOn().get()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // v1.5.8 (K7 census wire): "Draw under cutout" was a
        // dead row — written by the settings sheet, read by
        // nobody. It now drives the window's cutout mode here,
        // beside its window-duty neighbours.
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.P
        ) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (prefs.drawUnderCutout().get()) {
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = attrs
        }
        if (prefs.customBrightness().get()) {
            val value = prefs.customBrightnessValue().get()
            val attrs = window.attributes
            attrs.screenBrightness = when {
                value <= 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else -> (value / 100f).coerceIn(0.01f, 1f)
            }
            window.attributes = attrs
        }
        if (::viewModel.isInitialized) {
            requestedOrientation = viewModel.resolveOrientation().flag
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::viewModel.isInitialized) {
            val prefs = viewModel.scopedPreferences
            if (prefs.readWithVolumeKeys().get()) {
                val inverted = prefs.readWithVolumeKeysInverted().get()
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        _volumeTurns.tryEmit(if (inverted) -1 else 1)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        _volumeTurns.tryEmit(if (inverted) 1 else -1)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** Donor ReaderActivity.newIntent — same extras, same flag. */
        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            // SY -->
            page: Int? = null
            // SY <--
        ): Intent {
            return Intent(context, KomikkuReaderActivity::class.java).apply {
                // Primitive overloads so getLongExtra/getIntExtra
                // read them back (the donor's nullable-Serializable
                // overload stores boxed values its own reader
                // unwraps via Bundle.getLong; names/flag identical).
                mangaId?.let { putExtra("manga", it) }
                chapterId?.let { putExtra("chapter", it) }
                // SY -->
                page?.let { putExtra("page", it) }
                // SY <--
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
