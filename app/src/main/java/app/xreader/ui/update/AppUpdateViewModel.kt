package app.xreader.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.BuildConfig
import app.xreader.data.update.ApkInstaller
import app.xreader.data.update.AppUpdateRepository
import app.xreader.data.update.ReleaseInfo
import app.xreader.data.update.UpdateGate
import app.xreader.data.update.UpdateNotifier
import app.xreader.di.AppContainer
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The in-app updater's one brain (v1.3.5): the floating card,
 * the What's New screen and the Settings "Updates & about"
 * section all read this state, so a download started on the
 * card shows its progress everywhere.
 *
 * The user's rules, enforced here (CYCLE1-PLAN):
 *  - automatic checks at most once a day (Settings switch,
 *    default on);
 *  - the floating card is shown at most once a day, whether
 *    it's ignored or not;
 *  - an install is only ever offered for a download whose
 *    SHA-256 matched the release's published checksum.
 */
class AppUpdateViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface DownloadPhase {
        data object None : DownloadPhase
        data class Running(
            val fraction: Float?,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadPhase
        data object Verifying : DownloadPhase
        data class Ready(val filePath: String) : DownloadPhase
        data class Failed(val text: String) : DownloadPhase
        data object NeedsInstallPermission : DownloadPhase
    }

    data class UiState(
        val currentVersion: String = BuildConfig.VERSION_NAME,
        val currentVersionCode: Int = BuildConfig.VERSION_CODE,
        val autoCheckEnabled: Boolean = true,
        val checking: Boolean = false,
        /** The newer release, once known (cache or live check). */
        val release: ReleaseInfo? = null,
        /** Plain-words state for the Settings section. */
        val statusLine: String = "Not checked yet",
        /** Non-null while the floating card is on screen. */
        val cardRelease: ReleaseInfo? = null,
        val download: DownloadPhase = DownloadPhase.None,
        /** The verified APK on disk, once a download passed
         * its checksum — kept beside [download] so the
         * permission detour can't lose it. */
        val readyFilePath: String? = null
    )

    private val repo = container.updateRepository
    private val prefs = container.updatePrefs
    private val notifier = UpdateNotifier(container.applicationContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Intents the shell must fire (installer, permission
     * settings) — the ViewModel never starts activities. */
    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = _intents.asSharedFlow()

    private var downloadJob: Job? = null
    private var appOpenHandled = false

    init {
        viewModelScope.launch {
            prefs.autoCheckEnabled.collect { enabled ->
                _state.update { it.copy(autoCheckEnabled = enabled) }
            }
        }
    }

    /** Called once when the shell first composes: surface
     * any cached update, run the daily check if it's due,
     * then present the card if the once-a-day rule allows. */
    fun onAppOpen() {
        if (appOpenHandled) return
        appOpenHandled = true
        viewModelScope.launch {
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
     * Never touches the floating card. */
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
        if (_state.value.download != DownloadPhase.None) return
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

    /** Ignore: the card goes away and — the shown-day is
     * already stamped — stays away until tomorrow. */
    fun ignoreCard() {
        _state.update { it.copy(cardRelease = null) }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoCheckEnabled(enabled) }
    }

    /** Update Now (or Try again after a failure). */
    fun startDownload() {
        val release = _state.value.cardRelease ?: _state.value.release ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    cardRelease = release,
                    download = DownloadPhase.Running(null, 0L, release.apk.sizeBytes)
                )
            }
            notifier.showProgress(release.version, null)
            val targetDir = File(container.applicationContext.cacheDir, "updates")
            val outcome = repo.downloadAndVerify(release, targetDir) { downloaded, total ->
                if (!isActive) return@downloadAndVerify
                val fraction = if (total > 0) {
                    (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        download = DownloadPhase.Running(
                            fraction, downloaded,
                            if (total > 0) total else release.apk.sizeBytes
                        )
                    )
                }
                notifier.showProgress(release.version, fraction)
            }
            // Cancelled mid-download: the partial file is the
            // repository's to have removed on its failure path;
            // a cancelled job applies no outcome at all.
            if (!isActive) return@launch
            when (outcome) {
                is AppUpdateRepository.DownloadOutcome.Ready -> {
                    _state.update {
                        it.copy(
                            download = DownloadPhase.Ready(outcome.file.absolutePath),
                            readyFilePath = outcome.file.absolutePath
                        )
                    }
                    notifier.showReady(release.version)
                }
                is AppUpdateRepository.DownloadOutcome.ChecksumMissing -> {
                    failDownload(
                        "This release doesn't publish a safety checksum, so " +
                            "Xreader won't install it from here. You can still " +
                            "get it from the GitHub page."
                    )
                }
                is AppUpdateRepository.DownloadOutcome.ChecksumMismatch -> {
                    failDownload(
                        "The downloaded file doesn't match the release's " +
                            "safety checksum, so it was deleted. Nothing " +
                            "was installed."
                    )
                }
                is AppUpdateRepository.DownloadOutcome.Failed -> {
                    failDownload(outcome.reason)
                }
            }
        }
    }

    private fun failDownload(text: String) {
        _state.update { it.copy(download = DownloadPhase.Failed(text)) }
        notifier.showFailed()
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(download = DownloadPhase.None) }
        notifier.clear()
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
            _state.update {
                it.copy(download = DownloadPhase.Failed("The downloaded file is gone — download it again."))
            }
            return
        }
        if (ApkInstaller.canInstall(context)) {
            viewModelScope.launch {
                _intents.emit(ApkInstaller.installIntent(context, file))
            }
        } else {
            _state.update { it.copy(download = DownloadPhase.NeedsInstallPermission) }
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
        val path = _state.value.readyFilePath
        if (path != null) {
            _state.update { it.copy(download = DownloadPhase.Ready(path)) }
        }
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
