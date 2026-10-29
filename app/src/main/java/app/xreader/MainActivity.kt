package app.xreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.GlassStyle
import app.xreader.data.prefs.ThemeMode
import app.xreader.di.AppContainer
import app.xreader.ui.shell.MainScaffold
import app.xreader.ui.start.OnboardingScreen
import app.xreader.ui.start.RecoveryScreen
import app.xreader.ui.theme.XreaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The single activity; everything else is Compose (PLAN §3). */
class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Volume-key page turns (PLAN §6): the reader registers a
     * handler while its setting is on; all other volume presses keep
     * their normal behaviour. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (app.xreader.ui.reader.VolumeKeyTurn.onKey(
                event.keyCode, event.action == android.view.KeyEvent.ACTION_DOWN
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent, (application as XreaderApp).container)
    }

    /** Home-screen widget taps (Phase 6) carry the current book's
     * id; the shell observes [AppContainer.pendingOpenBook] and
     * opens the reader there. */
    private fun handleWidgetIntent(
        intent: android.content.Intent?,
        container: AppContainer
    ) {
        val bookId = intent?.getLongExtra(
            app.xreader.widget.XreaderWidget.EXTRA_OPEN_BOOK, -1L
        ) ?: -1L
        if (bookId > 0) container.pendingOpenBook.value = bookId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as XreaderApp).container
        handleWidgetIntent(intent, container)
        // A previous crash outranks onboarding (PLAN §1.5): recovery first.
        val crashNote = container.unseenCrashNote()
        setContent {
            val appearance by container.appearancePrefs.state
                .collectAsStateWithLifecycle(initialValue = AppearanceState())
            val onboardingDone by container.onboardingPrefs.completed
                .collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = when (appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }
            var showRecovery by remember { mutableStateOf(crashNote != null) }
            // R01 boot sequence: compose the first frame WITHOUT a
            // haze state, then enable glass once a frame has drawn —
            // a bad appearance state can never crash the first frame,
            // and Recovery (which never composes Haze) stays safe.
            var hazeReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                hazeReady = true
            }
            XreaderTheme(
                darkTheme = darkTheme,
                appearance = appearance,
                hazeReady = hazeReady
            ) {
                when {
                    showRecovery -> RecoveryScreen(
                        note = crashNote,
                        onContinue = {
                            container.acknowledgeCrash()
                            showRecovery = false
                        },
                        onSafeAppearance = {
                            // Persist Tinted BEFORE entering the app,
                            // so the crashing appearance can't return.
                            activityScope.launch {
                                container.appearancePrefs
                                    .setGlassStyle(GlassStyle.TINTED)
                            }
                            container.acknowledgeCrash()
                            showRecovery = false
                        }
                    )
                    onboardingDone == false -> OnboardingScreen(
                        onDone = {
                            activityScope.launch { container.onboardingPrefs.markCompleted() }
                        }
                    )
                    onboardingDone == true -> MainScaffold(
                        container = container,
                        appearance = appearance
                    )
                    // null = preferences not read yet; the theme's first
                    // frame is the splash — render nothing for a beat.
                    else -> Unit
                }
            }
        }
    }
}
