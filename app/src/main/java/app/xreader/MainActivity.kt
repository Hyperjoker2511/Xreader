package app.xreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
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
import app.xreader.data.prefs.settingsDataStore
import androidx.compose.ui.Modifier
import app.xreader.core.motion.SpringMotionEngine
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

/** The single activity; everything else is Compose (PLAN §3).
 * v1.5.6 (§3): a [FragmentActivity] (a ComponentActivity subclass),
 * originally so the local EPUB reader could host Readium's
 * navigator fragment; v1.7 OE-2 retired that reader, and the
 * activity class is unchanged — setContent and every other
 * behaviour are as they were. */
class MainActivity : FragmentActivity() {

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
        handleSourceShelfIntent(intent, (application as XreaderApp).container)
    }

    /** Source-update notification taps (v1.5.39, item 4)
     * carry the shelf extra; the shell observes
     * [AppContainer.pendingOpenSourceShelf] and opens the
     * manga shelf — the widget-tap pattern. */
    private fun handleSourceShelfIntent(
        intent: android.content.Intent?,
        container: AppContainer
    ) {
        val open = intent?.getBooleanExtra(
            app.xreader.data.update.SourceUpdateNotifier
                .EXTRA_OPEN_SOURCE_SHELF, false
        ) ?: false
        if (open) container.pendingOpenSourceShelf.value = true
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
        // v1.7 OE-2 (R27): the Readium dummy fragment-factory
        // install that used to stand here is deleted whole —
        // with no navigator fragment in the app there is
        // nothing for process-death restoration to restore.
        super.onCreate(savedInstanceState)
        // Code 126 (splash): open the window in the user's own
        // design colours. The manifest theme is the static black
        // base (exact for Nocturne, in-family for Material 3
        // dark — the Android 12+ system splash can only ever use
        // the manifest theme); this re-theme, from the splash
        // mirror the container's collector keeps (the last
        // session's design + M3 theme mode), runs before
        // setContent so the window and the first Compose frame
        // sit in the right colours. No mirror yet (first-ever
        // cold start) keeps the base theme for that one start.
        // No delay is added anywhere: the branded Compose moment
        // still hands over the instant loading finishes.
        runCatching {
            val mirror = getSharedPreferences("splash_mirror", MODE_PRIVATE)
            when (mirror.getString("design", null)) {
                "EDITORIAL" -> setTheme(
                    when (mirror.getString("theme_mode", null)) {
                        "LIGHT" -> R.style.Theme_Xreader_SplashM3Light
                        "FOLLOW_SYSTEM" -> if (
                            resources.configuration.uiMode and
                                android.content.res.Configuration
                                    .UI_MODE_NIGHT_MASK ==
                                android.content.res.Configuration
                                    .UI_MODE_NIGHT_NO
                        ) {
                            R.style.Theme_Xreader_SplashM3Light
                        } else {
                            R.style.Theme_Xreader_SplashM3Dark
                        }
                        else -> R.style.Theme_Xreader_SplashM3Dark
                    }
                )
                "NOCTURNE" -> setTheme(R.style.Theme_Xreader_SplashNocturne)
            }
        }
        enableEdgeToEdge()
        val container = (application as XreaderApp).container
        handleWidgetIntent(intent, container)
        handleSourceShortcut(intent, container)
        handleSourceShelfIntent(intent, container)
        // Code 122 (source regression fix): the one-time startup
        // sequence (bundled defaults → Keiyoushi repo →
        // restoreInstalled() → update passes) runs on the
        // container's app scope, triggered here — never on a
        // composition scope, so a design switch or recomposition
        // can never cancel it mid-restore. Guarded inside the
        // container: exactly once per process.
        container.startStartupSequence()
        // A previous crash outranks onboarding (PLAN §1.5): recovery first.
        val crashNote = container.unseenCrashNote()
        setContent {
            val appearance by container.appearancePrefs.state
                .collectAsStateWithLifecycle(initialValue = AppearanceState())
            // Code 120: the design state rides beside appearance —
            // the theme branches on it, the first-start ask writes
            // it, and the M3 launch intro + the intro's own
            // Show/Skip knob (design §2.11 Advanced) read it. An
            // existing install's value is whatever priming
            // recorded (U10): Nocturne, exactly as found.
            val designState by container.designPrefs.state
                .collectAsStateWithLifecycle(
                    initialValue = app.xreader.data.prefs.DesignState()
                )
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
            // 2 GB perf pass (F6): the intro also honours the
            // MOTION tier — on a Minimal-tier device (≤2 GB RAM)
            // the spring intro is skipped entirely (system
            // splash only), per the motion engine's own
            // "Minimal = crossfades only" doctrine. Other tiers
            // pass motionEnabled through unchanged.
            val launchMotionTier = remember(container, motionEnabled) {
                SpringMotionEngine.tierFor(container.totalRamMb(), motionEnabled)
            }
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
                hazeReady = hazeReady,
                design = designState
            ) {
                when {
                    showRecovery -> RecoveryScreen(
                        note = crashNote,
                        onContinue = {
                            container.acknowledgeCrash()
                            showRecovery = false
                        },
                        onSafeAppearance = {
                            // Code 121 (S39/T17): Safe appearance
                            // is DESIGN-SCOPED. Under Material 3
                            // it resets ONLY M3's own appearance
                            // settings to their defaults and NEVER
                            // writes a Nocturne setting; the
                            // Tinted write below exists only when
                            // Nocturne is the active design.
                            if (designState.design !=
                                app.xreader.data.prefs.AppDesign.NOCTURNE
                            ) {
                                // Code 123: the M3-family reset
                                // serves Editorial too — the state
                                // it resets (colour source, theme
                                // mode, motion) is the family's
                                // shared state Editorial reads.
                                activityScope.launch {
                                    val prefs = container.designPrefs
                                    prefs.setColourSource(
                                        app.xreader.data.prefs
                                            .M3ColourSource.DYNAMIC
                                    )
                                    // W8 (D-121-W7-05): the
                                    // stored seed is cleared too,
                                    // not merely made inert.
                                    prefs.clearSeed()
                                    prefs.setThemeMode(
                                        app.xreader.data.prefs
                                            .M3ThemeMode.DARK
                                    )
                                    prefs.setMotionChoice(
                                        app.xreader.data.prefs
                                            .M3MotionChoice.EXPRESSIVE
                                    )
                                    prefs.setBookPageCoverColour(true)
                                    prefs.setTabLabels(
                                        app.xreader.data.prefs
                                            .M3TabLabels.ALWAYS
                                    )
                                    prefs.setNavForm(
                                        app.xreader.data.prefs
                                            .M3NavForm.AUTO
                                    )
                                }
                            } else {
                                // Persist Tinted BEFORE entering
                                // the app, so the crashing
                                // appearance can't return.
                                activityScope.launch {
                                    container.appearancePrefs
                                        .setGlassStyle(GlassStyle.TINTED)
                                }
                            }
                            container.acknowledgeCrash()
                            showRecovery = false
                        }
                    )
                    onboardingDone == false -> OnboardingScreen(
                        onDone = {
                            activityScope.launch { container.onboardingPrefs.markCompleted() }
                        },
                        // Code 120 (S38): the first-start design &
                        // colour ask — shown inside the onboarding
                        // pager, fresh installs only (this branch
                        // is unreachable once onboarding completed,
                        // and priming has already seeded the M3
                        // default, so skipping the ask changes
                        // nothing).
                        designState = designState,
                        onPickDesign = { picked ->
                            activityScope.launch {
                                container.designPrefs.setDesign(picked)
                            }
                        },
                        onPickDynamicColour = {
                            activityScope.launch {
                                container.designPrefs.setColourSource(
                                    app.xreader.data.prefs.M3ColourSource.DYNAMIC
                                )
                            }
                        },
                        onPickSeedColour = { argb ->
                            activityScope.launch {
                                container.designPrefs.setColourSource(
                                    app.xreader.data.prefs.M3ColourSource.PICKED
                                )
                                container.designPrefs.setSeed(argb)
                            }
                        },
                        // Code 120 completion (S38): the
                        // Nocturne card's accent choice — the
                        // preset scheme names through the one
                        // explicit scheme-choice write.
                        nocturneScheme = appearance.colourScheme,
                        onPickNocturneScheme = { schemeName ->
                            activityScope.launch {
                                container.appearancePrefs
                                    .setColourScheme(schemeName)
                            }
                        },
                        // Code 121 (S38/§5.2 parity), W8
                        // reconciliation: the ask's colour bar
                        // serving Nocturne writes through the
                        // SAME "Custom" entry as W3's §5.2 door
                        // in Customise — the entry is identified
                        // by its name ("Custom"): an existing one
                        // is updated in place and selected; only
                        // when none exists is a new one created
                        // (with the door's own id form). There is
                        // exactly ONE "Custom" scheme entry.
                        onPickNocturneColour = { argb ->
                            activityScope.launch {
                                val existingCustom =
                                    appearance.customSchemes
                                        .firstOrNull {
                                            it.name == "Custom"
                                        }
                                if (existingCustom != null) {
                                    container.appearancePrefs
                                        .setCustomSchemes(
                                            appearance.customSchemes
                                                .map {
                                                    if (it.id ==
                                                        existingCustom.id
                                                    ) {
                                                        it.copy(
                                                            seedArgb = argb,
                                                            endArgb = null
                                                        )
                                                    } else {
                                                        it
                                                    }
                                                }
                                        )
                                    container.appearancePrefs
                                        .setColourScheme(
                                            app.xreader.data.prefs
                                                .CUSTOM_SCHEME_PREFIX +
                                                existingCustom.id
                                        )
                                } else {
                                    val spec =
                                        app.xreader.data.prefs
                                            .CustomSchemeSpec(
                                                id = "c" +
                                                    System
                                                        .currentTimeMillis(),
                                                name = "Custom",
                                                seedArgb = argb
                                            )
                                    val updated =
                                        app.xreader.data.prefs
                                            .AppearanceValidation
                                            .addCustomScheme(
                                                appearance.customSchemes,
                                                spec
                                            )
                                    if (updated != null) {
                                        container.appearancePrefs
                                            .setCustomSchemes(updated)
                                        container.appearancePrefs
                                            .setColourScheme(
                                                app.xreader.data.prefs
                                                    .CUSTOM_SCHEME_PREFIX +
                                                    spec.id
                                            )
                                    }
                                }
                            }
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
                        if (designState.design !=
                            app.xreader.data.prefs.AppDesign.NOCTURNE
                        ) {
                            // Code 120 (S2): in the Material 3
                            // design the launch intro is the plain
                            // spec surface — wordmark on the scheme
                            // surface, dismissed by an effect fade
                            // when Library is ready. The spring
                            // intro is Nocturne's and stays
                            // Nocturne's. The intro's own Show/Skip
                            // knob (§2.11 Advanced) gates both.
                            // Code 123 (S2 extension): Editorial
                            // takes the same plain surface — under
                            // its theme that IS the extension's
                            // form: paper ground, serif wordmark.
                            app.xreader.ui.start.M3LaunchIntro(
                                libraryReady = libraryReady,
                                animationsEnabled = motionEnabled &&
                                    launchMotionTier !=
                                        SpringMotionEngine.MotionTier.Minimal &&
                                    designState.launchIntro
                            )
                        } else {
                            LaunchIntro(
                                libraryReady = libraryReady,
                                animationsEnabled = motionEnabled &&
                                    launchMotionTier !=
                                        SpringMotionEngine.MotionTier.Minimal &&
                                    designState.launchIntro
                            )
                        }
                        // Code 121 (R39): TWO gates, two records,
                        // never interacting.
                        //
                        // (A) THE REVEAL — for a design-carrying
                        // update. Its record is RevealPrefs (sole
                        // reader: this gate; sole writer: the
                        // reveal's own terminal actions below).
                        // Exact gate: the installed code has
                        // crossed DESIGN_CARRYING_CODE (121),
                        // the reveal has not been seen for it,
                        // the install is ESTABLISHED (the Room
                        // database file existed at container
                        // construction — fresh installs take the
                        // onboarding branch above and never reach
                        // here), and the ACTIVE design is not the
                        // design being revealed (code 121: not
                        // Nocturne-gated for M3; code 123: not
                        // already Editorial). It composes in this
                        // shell branch only, so a Recovery detour
                        // DELAYS it — Recovery replaces this
                        // branch, and once Continue resolves it
                        // the gate is re-evaluated and the reveal
                        // fires; the detour cannot eat it. It is
                        // a Dialog: truly modal, input beneath
                        // blocked. Try switches the design via
                        // DesignPrefs — the only door besides
                        // Customise that may, and only on tap.
                        //
                        // (B) THE LEGACY SHOWCASE — for updates
                        // carrying no new design. Wears the
                        // active design; its record is
                        // ShowcasePrefs (sole reader: this gate;
                        // sole writer: the showcase's own
                        // terminal actions below — Skip / Done /
                        // Back). Code 125 (F4): it NO LONGER
                        // shares the GamePrefs store with the Me
                        // note — that sharing let a Me-note
                        // dismissal write the showcase's seen
                        // record and suppress it (the code-120
                        // conviction, still live for this gate
                        // until this build). It is suppressed
                        // while the reveal is pending or showing,
                        // so the two never stack; finishing it
                        // writes only its own store.
                        val revealPrefs = remember {
                            app.xreader.ui.update.RevealPrefs(
                                container.applicationContext
                                    .settingsDataStore
                            )
                        }
                        val revealSeen by revealPrefs.revealSeenCode
                            .collectAsStateWithLifecycle(
                                initialValue = Int.MAX_VALUE
                            )
                        // Code 125 (F4): the showcase's own
                        // record — see ShowcasePrefs. The Me
                        // note keeps GamePrefs.whatsNewSeenCode
                        // for itself (read in MainScaffold);
                        // this gate never reads it any more.
                        val showcasePrefs = remember {
                            app.xreader.ui.update.ShowcasePrefs(
                                container.applicationContext
                                    .settingsDataStore
                            )
                        }
                        val showcaseSeen by showcasePrefs
                            .showcaseSeenCode
                            .collectAsStateWithLifecycle(
                                initialValue = Int.MAX_VALUE
                            )
                        val installedCode = remember {
                            runCatching {
                                @Suppress("DEPRECATION")
                                container.applicationContext
                                    .packageManager.getPackageInfo(
                                        container.applicationContext
                                            .packageName, 0
                                    ).versionCode
                            }.getOrDefault(0)
                        }
                        var revealDismissed by remember {
                            mutableStateOf(false)
                        }
                        var showcaseDismissed by remember {
                            mutableStateOf(false)
                        }
                        // Code 123: the reveal now presents the
                        // Editorial design (DESIGN_CARRYING_CODE
                        // moved to 123 in RevealPrefs). The gate's
                        // design condition is "not already on
                        // Editorial": Nocturne and Material 3
                        // users both meet the new design; an
                        // Editorial user does not need its reveal.
                        val revealGate =
                            !revealDismissed &&
                                installedCode >=
                                    app.xreader.ui.update.RevealPrefs
                                        .DESIGN_CARRYING_CODE &&
                                revealSeen <
                                    app.xreader.ui.update.RevealPrefs
                                        .DESIGN_CARRYING_CODE &&
                                container.databaseFileExisted &&
                                designState.design !=
                                    app.xreader.data.prefs.AppDesign
                                        .EDITORIAL
                        if (revealGate) {
                            app.xreader.ui.update.WhatsNewRevealEditorial(
                                designState = designState,
                                onTryDesign = {
                                    revealDismissed = true
                                    activityScope.launch {
                                        container.designPrefs
                                            .setDesign(
                                                app.xreader.data.prefs
                                                    .AppDesign.EDITORIAL
                                            )
                                        revealPrefs.setRevealSeen(
                                            installedCode
                                        )
                                    }
                                },
                                onFinish = {
                                    revealDismissed = true
                                    activityScope.launch {
                                        revealPrefs.setRevealSeen(
                                            installedCode
                                        )
                                    }
                                }
                            )
                        } else if (!showcaseDismissed &&
                            installedCode > 0 &&
                            showcaseSeen < installedCode &&
                            (showcaseSeen > 0 ||
                                container.databaseFileExisted)
                        ) {
                            app.xreader.ui.update.WhatsNewShowcase(
                                onFinish = {
                                    showcaseDismissed = true
                                    activityScope.launch {
                                        showcasePrefs
                                            .setShowcaseSeen(
                                                installedCode
                                            )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
