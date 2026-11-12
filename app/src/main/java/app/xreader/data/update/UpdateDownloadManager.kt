package app.xreader.data.update

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The update download's owner (v1.5.42): app-scoped, so a
 * download started from the card keeps running when the
 * user hides the card, leaves the app or rotates — the
 * owner's "it's not background / can't back out" complaint
 * against the old ViewModel-scoped download. The thin
 * foreground service (UpdateDownloadService, started via
 * [onDownloadStarted]) keeps the process alive while a
 * download runs; the notifications are spoken through the
 * hooks (UpdateNotifier in production).
 *
 * The download+verify boundary is injected as a function so
 * the state machine is JVM-testable with a fake; production
 * wires it to [AppUpdateRepository.downloadAndVerify], whose
 * SHA-256 verify-before-install gate is unchanged — an
 * install is only ever offered for a checksum-matched file.
 *
 * Process death is honest: state is in-memory, so a killed
 * process relaunches to "update available" (phase [None]),
 * and [sweepOrphanedDownloads] removes any partial file a
 * dead process left behind — an orphaned file is never
 * offered for install, because its verification died with
 * the process that ran it.
 */
class UpdateDownloadManager(
    private val scope: CoroutineScope,
    private val downloadAndVerify: suspend (
        release: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) -> AppUpdateRepository.DownloadOutcome,
    private val updatesDir: () -> File,
    private val onDownloadStarted: (version: String) -> Unit = {},
    private val onProgress: (version: String, fraction: Float?) -> Unit = { _, _ -> },
    private val onReady: (version: String) -> Unit = {},
    private val onFailed: () -> Unit = {},
    private val onCancelled: () -> Unit = {}
) {

    data class State(
        val release: ReleaseInfo? = null,
        val phase: UpdateDownloadPhase = UpdateDownloadPhase.None,
        /** The verified APK on disk, once a download passed
         * its checksum — kept beside [phase] so the install-
         * permission detour can't lose it. */
        val readyFilePath: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Start downloading [release] (the Update tap). A
     * second tap while a download runs is a no-op. Hiding
     * the card is NOT this — hiding is a UI-only act that
     * never reaches the manager, so a hidden download simply
     * continues. */
    fun start(release: ReleaseInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update {
                it.copy(
                    release = release,
                    phase = UpdateDownloadPhase.Running(
                        null, 0L, release.apk.sizeBytes
                    ),
                    readyFilePath = null
                )
            }
            onDownloadStarted(release.version)
            onProgress(release.version, null)
            val outcome = downloadAndVerify(release) { downloaded, total ->
                if (!isActive) return@downloadAndVerify
                val fraction = if (total > 0) {
                    (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        phase = UpdateDownloadPhase.Running(
                            fraction, downloaded,
                            if (total > 0) total else release.apk.sizeBytes
                        )
                    )
                }
                onProgress(release.version, fraction)
            }
            // Cancelled mid-download: no outcome is applied;
            // cancel() owns the state reset and the partial
            // file's cleanup.
            if (!isActive) return@launch
            when (outcome) {
                is AppUpdateRepository.DownloadOutcome.Ready -> {
                    _state.update {
                        it.copy(
                            phase = UpdateDownloadPhase.Ready(
                                outcome.file.absolutePath
                            ),
                            readyFilePath = outcome.file.absolutePath
                        )
                    }
                    onReady(release.version)
                }
                is AppUpdateRepository.DownloadOutcome.ChecksumMissing ->
                    fail(
                        "This release doesn't publish a safety checksum, so " +
                            "Xreader won't install it from here. You can still " +
                            "get it from the GitHub page."
                    )
                is AppUpdateRepository.DownloadOutcome.ChecksumMismatch ->
                    fail(
                        "The downloaded file doesn't match the release's " +
                            "safety checksum, so it was deleted. Nothing " +
                            "was installed."
                    )
                is AppUpdateRepository.DownloadOutcome.Failed ->
                    fail(outcome.reason)
            }
        }
    }

    private fun fail(text: String) {
        _state.update { it.copy(phase = UpdateDownloadPhase.Failed(text)) }
        onFailed()
    }

    /** The explicit stop (the card's Cancel, the progress
     * notification's Cancel action): the download dies, the
     * state returns to idle and the partial file is swept
     * once the cancelled work has fully unwound. */
    fun cancel() {
        val running = job ?: return
        if (!running.isActive) return
        job = null
        running.cancel()
        _state.update {
            it.copy(
                phase = UpdateDownloadPhase.None,
                readyFilePath = null
            )
        }
        onCancelled()
        scope.launch {
            running.join()
            sweepOrphanedDownloads()
        }
    }

    /** Delete any `.apk` in the updates directory that no
     * live state vouches for. Runs only at phase
     * [UpdateDownloadPhase.None] with no job active — a
     * Ready file (phase Ready) and an in-flight download
     * (phase Running) are untouchable. Called on app open
     * (a previous process may have died mid-download) and
     * after a cancel has unwound. */
    fun sweepOrphanedDownloads() {
        if (_state.value.phase != UpdateDownloadPhase.None) return
        if (job?.isActive == true) return
        runCatching {
            updatesDir().listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.name.endsWith(".apk", ignoreCase = true)
                ) {
                    file.delete()
                }
            }
        }
    }

    /** The Install tap found the verified file gone (the
     * cache was cleared under us): say so in plain words.
     * No notification — the user is looking at the card. */
    fun reportReadyFileMissing() {
        _state.update {
            it.copy(
                phase = UpdateDownloadPhase.Failed(
                    "The downloaded file is gone — download it again."
                ),
                readyFilePath = null
            )
        }
    }

    /** Android still needs the one-time "allow installs from
     * Xreader" grant: the card explains and routes to the
     * exact system setting instead of dead-ending. */
    fun showInstallPermissionNeeded() {
        if (_state.value.readyFilePath == null) return
        _state.update {
            it.copy(phase = UpdateDownloadPhase.NeedsInstallPermission)
        }
    }

    /** Back from the install-permission setting: offer
     * Install again — the tap re-checks the grant. */
    fun restoreReadyAfterInstallSettings() {
        val current = _state.value
        val path = current.readyFilePath ?: return
        if (current.phase != UpdateDownloadPhase.NeedsInstallPermission) return
        _state.update { it.copy(phase = UpdateDownloadPhase.Ready(path)) }
    }
}
