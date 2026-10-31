package app.xreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import app.xreader.core.motion.rememberMotionEnabled
import app.xreader.di.AppContainer
import app.xreader.ui.shell.LaunchIntro
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
        handleSourceShortcut(intent, (application as XreaderApp).container)
    }

    /** Live Source home-screen shortcuts (v1.0.9) carry the
     * source's ids; the shell observes
     * [AppContainer.pendingOpenSource] and opens its catalogue.
     * The task is labelled with the site's name, so the window
     * the shortcut opened presents as that site's app in
     * Recents. */
    private fun handleSourceShortcut(
        intent: android.content.Intent?,
        container: AppContainer
    ) {
        val extId = intent?.getLongExtra(
            app.xreader.ui.sources.SiteShortcut.EXTRA_SOURCE_EXT, -1L
        ) ?: -1L
        val sourceId = intent?.getStringExtra(
            app.xreader.ui.sources.SiteShortcut.EXTRA_SOURCE_ID
        )
        if (extId > 0 && !sourceId.isNullOrBlank()) {
            container.pendingOpenSource.value = extId to sourceId
            val name = intent.getStringExtra(
                app.xreader.ui.sources.SiteShortcut.EXTRA_SOURCE_NAME
            )
            if (!name.isNullOrBlank()) {
                runCatching {
                    setTaskDescription(
                        android.app.ActivityManager.TaskDescription(name)
                    )
                }
            }
        }
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
        handleSourceShortcut(intent, container)
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
            // v1.2.0 (WS6): the launch intro's two signals. The
            // motion decision is the app's own: the system animator
            // scale AND the Appearance reduce-motion preference,
            // combined by SpringMotionEngine.animationsEnabled (via
            // rememberMotionEnabled) — disabled motion skips the
            // intro entirely. `libraryReady` is fed by the shell's
            // LibraryViewModel loading flag (see MainScaffold).
            val motionEnabled = rememberMotionEnabled(
                userEnabled = !appearance.reduceMotion
            )
            var libraryReady by remember { mutableStateOf(false) }
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
                    // v1.2.0 (audit finding 7 / S4): first paint is
                    // no longer gated on the onboarding DataStore
                    // read. The shell composes IMMEDIATELY — with
                    // the default appearance (collectAsState's
                    // initialValue above), the stored appearance
                    // applying when it arrives — for both the
                    // loaded `true` case and the not-yet-read null
                    // case, which is the overwhelmingly common
                    // returning-user state. A genuinely new user
                    // sees the shell for the beat it takes the
                    // read to land, then onboarding replaces it;
                    // once loaded, behaviour is exactly as before.
                    else -> Box(Modifier.fillMaxSize()) {
                        MainScaffold(
                            container = container,
                            appearance = appearance,
                            onLibraryReady = { libraryReady = true }
                        )
                        // v1.2.0 (WS6): the animated launch intro
                        // — a pure overlay ABOVE the shell. It is
                        // composed as a sibling, never instead of
                        // or before the shell, so first content is
                        // never gated on it; it dismisses on the
                        // Library-ready signal above and is hard-
                        // capped at 1,000 ms by its controller.
                        LaunchIntro(
                            libraryReady = libraryReady,
                            animationsEnabled = motionEnabled
                        )
                    }
                }
            }
        }
    }
}
