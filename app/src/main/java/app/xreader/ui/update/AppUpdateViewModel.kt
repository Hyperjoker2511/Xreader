package app.xreader.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.BuildConfig
import app.xreader.data.update.ApkInstaller
import app.xreader.data.update.AppUpdateRepository
import app.xreader.data.update.ReleaseInfo
import app.xreader.data.update.UpdateDownloadPhase
import app.xreader.data.update.UpdateGate
import app.xreader.di.AppContainer
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The in-app updater's one brain (v1.3.5): the card, the
 * What's New screen and the Settings "Updates & about"
 * section all read this state, so a download started on the
 * card shows its progress everywhere.
 *
 * The user's rules, enforced here (CYCLE1-PLAN):
 *  - automatic checks at most once a day (Settings switch,
 *    default on);
 *  - the card is shown at most once a day, whether it's
 *    ignored or not;
 *  - an install is only ever offered for a download whose
 *    SHA-256 matched the release's published checksum.
 *
 * v1.5.42: the download itself moved OUT of this ViewModel
 * into the app-scoped UpdateDownloadManager — it now
 * survives the card being hidden, the app being left, and
 * rotation, under a foreground service. This ViewModel owns
 * the check, the card's presentation and the install
 * intents, and MIRRORS the manager's phase into [UiState]
 * so every surface keeps reading the one state.
 */
class AppUpdateViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val currentVersion: String = BuildConfig.VERSION_NAME,
        val currentVersionCode: Int = BuildConfig.VERSION_CODE,
        val autoCheckEnabled: Boolean = true,
        val checking: Boolean = false,
        /** The newer release, once known (cache or live check). */
        val release: ReleaseInfo? = null,
        /** Plain-words state for the Settings section. */
        val statusLine: String = "Not checked yet",
        /** Non-null while the card is presented. */
        val cardRelease: ReleaseInfo? = null,
        val download: UpdateDownloadPhase = UpdateDownloadPhase.None,
        /** The verified APK on disk, once a download passed
         * its checksum — kept beside [download] so the
         * permission detour can't lose it. */
        val readyFilePath: String? = null
    )

    private val repo = container.updateRepository
    private val prefs = container.updatePrefs
    private val downloads = container.updateDownloadManager

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Intents the shell must fire (installer, permission
     * settings) — the ViewModel never starts activities. */
    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = _intents.asSharedFlow()

    private var appOpenHandled = false

    init {
        viewModelScope.launch {
            prefs.autoCheckEnabled.collect { enabled ->
                _state.update { it.copy(autoCheckEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            downloads.state.collect { manager ->
                _state.update {
                    it.copy(
                        download = manager.phase,
                        readyFilePath = manager.readyFilePath
                    )
                }
            }
        }
    }

    /** Called once when the shell first composes: sweep any
     * orphaned partial a dead process left, surface any
     * cached update, run the daily check if it's due, then
     * present the card if the once-a-day rule allows. */
    fun onAppOpen() {
        if (appOpenHandled) return
        appOpenHandled = true
        viewModelScope.launch {
            downloads.sweepOrphanedDownloads()
            repo.cachedRelease()?.let { cached ->
                if (repo.isUpdateAvailable(cached)) {
                    _state.update {
                        it.copy(
                            release = cached,
                            statusLine = "Version ${cached.version} is ready."
                        )
                    }
                }
            }
            val due = UpdateGate.dueForAutoCheck(
                autoCheckEnabled = prefs.autoCheckEnabledNow(),
                lastCheckDay = prefs.lastCheckDay().ifBlank { null },
                nowMs = System.currentTimeMillis()
            )
            if (due) runCheck(manual = false)
            presentCardIfAllowed()
        }
    }

    /** The Settings button: a check right now, throttle-free.
     * Never touches the card. */
    fun checkManually() {
        if (_state.value.checking) return
        viewModelScope.launch { runCheck(manual = true) }
    }

    private suspend fun runCheck(manual: Boolean) {
        _state.update { it.copy(checking = true) }
        when (val outcome = repo.checkNow()) {
            is AppUpdateRepository.CheckOutcome.Available ->
                _state.update {
                    it.copy(
                        checking = false,
                        release = outcome.release,
                        statusLine = "Version ${outcome.release.version} is ready."
                    )
                }
            AppUpdateRepository.CheckOutcome.UpToDate ->
                _state.update {
                    it.copy(
                        checking = false,
                        release = null,
                        statusLine = "You're up to date."
                    )
                }
            is AppUpdateRepository.CheckOutcome.Failed ->
                _state.update {
                    it.copy(
                        checking = false,
                        statusLine = if (manual) {
                            "Couldn't check: ${outcome.reason}"
                        } else {
                            it.statusLine
                        }
                    )
                }
        }
    }

    private suspend fun presentCardIfAllowed() {
        val release = _state.value.release ?: return
        // A download already in flight owns the moment; a
        // finished one (Ready) may re-present so its Install
        // is reachable — the once-a-day rule still governs.
        val phase = _state.value.download
        if (phase is UpdateDownloadPhase.Running ||
            phase == UpdateDownloadPhase.Verifying
        ) {
            return
        }
        val allowed = UpdateGate.mayShowCard(
            updateAvailable = true,
            cardShownDay = prefs.cardShownDay().ifBlank { null },
            nowMs = System.currentTimeMillis()
        )
        if (allowed) {
            prefs.setCardShownDay(UpdateGate.dayOf(System.currentTimeMillis()))
            _state.update { it.copy(cardRelease = release) }
        }
    }

    /** Every way out of the card that is NOT Cancel: the ×
     * button, the scrim, Android Back, "Later", and — during
     * a download — "Hide". All of them only close the card:
     * the shown-day stamp (set at presentation) keeps it away
     * until tomorrow, and a running download is untouched —
     * it continues in the background and its notification
     * carries the progress and the Install offer. */
    fun dismissCard() {
        _state.update { it.copy(cardRelease = null) }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoCheckEnabled(enabled) }
    }

    /** Update Now (or Try again after a failure): hands the
     * release to the download manager and keeps the card on
     * it, so the journey reads in one place. */
    fun startDownload() {
        val release = _state.value.cardRelease ?: _state.value.release ?: return
        _state.update { it.copy(cardRelease = release) }
        downloads.start(release)
    }

    /** Cancel: the one explicit stop for a running download. */
    fun cancelDownload() {
        downloads.cancel()
    }

    /**
     * Install: hands the verified APK to Android's installer.
     * When the system still needs the one-time "allow installs
     * from Xreader" grant, the state says so in plain words
     * and the button routes to that exact setting instead.
     */
    fun install() {
        val path = _state.value.readyFilePath ?: return
        val context = container.applicationContext
        val file = File(path)
        if (!file.isFile) {
            downloads.reportReadyFileMissing()
            return
        }
        if (ApkInstaller.canInstall(context)) {
            viewModelScope.launch {
                _intents.emit(ApkInstaller.installIntent(context, file))
            }
        } else {
            downloads.showInstallPermissionNeeded()
        }
    }

    /** The "Open settings" tap on the permission explanation. */
    fun openInstallPermissionSettings() {
        viewModelScope.launch {
            _intents.emit(
                ApkInstaller.installPermissionIntent(container.applicationContext)
            )
        }
        // Back from Settings, Install is offered again —
        // install() re-checks the grant when tapped.
        downloads.restoreReadyAfterInstallSettings()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppUpdateViewModel(container) as T
            }
    }
}
