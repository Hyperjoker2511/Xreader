package app.xreader.data.update

/**
 * The update download's phases (v1.3.5, moved out of
 * AppUpdateViewModel in v1.5.42): the state the card, the
 * What's New screen and the Settings section all present.
 * The phases live in the data layer now because their owner
 * is [UpdateDownloadManager] — the ViewModel only mirrors
 * them into its UI state.
 *
 * An install is offered ONLY in [Ready] — a file whose
 * SHA-256 matched the release's published checksum (the
 * repository's verify-before-install gate).
 */
sealed interface UpdateDownloadPhase {
    data object None : UpdateDownloadPhase
    data class Running(
        val fraction: Float?,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadPhase
    data object Verifying : UpdateDownloadPhase
    data class Ready(val filePath: String) : UpdateDownloadPhase
    data class Failed(val text: String) : UpdateDownloadPhase
    data object NeedsInstallPermission : UpdateDownloadPhase
}
