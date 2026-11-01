package app.xreader.data.update

import app.xreader.data.prefs.UpdatePrefs
import java.io.File
import java.time.ZoneId

/**
 * The updater's orchestration (v1.3.5): check GitHub's
 * latest release for Hyperjoker2511/Xreader, cache it,
 * compare it with the installed version, and — on demand —
 * download the APK and verify its SHA-256 before anyone is
 * offered an install. Android-free apart from [File]; the
 * clock and the installed version are injected for tests.
 */
class AppUpdateRepository(
    private val http: UpdateHttp,
    private val prefs: UpdatePrefs,
    private val installedVersion: () -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    sealed interface CheckOutcome {
        data class Available(val release: ReleaseInfo) : CheckOutcome
        data object UpToDate : CheckOutcome
        data class Failed(val reason: String) : CheckOutcome
    }

    sealed interface DownloadOutcome {
        data class Ready(val file: File, val release: ReleaseInfo) : DownloadOutcome
        data class ChecksumMissing(val release: ReleaseInfo) : DownloadOutcome
        data class ChecksumMismatch(val release: ReleaseInfo) : DownloadOutcome
        data class Failed(val reason: String) : DownloadOutcome
    }

    /** The release from the last good check, if it parses —
     * no network. Null when nothing usable is cached. */
    suspend fun cachedRelease(): ReleaseInfo? {
        val cached = prefs.cachedReleaseJson()
        if (cached.isBlank()) return null
        return ReleaseParsing.parseLatestRelease(cached)
    }

    /** True when [release] is strictly newer than the
     * installed build. */
    fun isUpdateAvailable(release: ReleaseInfo): Boolean =
        VersionNames.isNewer(release.version, installedVersion())

    /**
     * One live check against GitHub. A good answer is cached
     * and stamps the once-a-day bookkeeping; a failed one
     * changes nothing, so a later open can try again.
     */
    suspend fun checkNow(): CheckOutcome {
        val payload = http.getText(LATEST_RELEASE_URL)
            ?: return CheckOutcome.Failed("GitHub couldn't be reached.")
        val release = ReleaseParsing.parseLatestRelease(payload)
            ?: return CheckOutcome.Failed("That release couldn't be read.")
        prefs.setCachedReleaseJson(payload)
        prefs.setLastCheckDay(UpdateGate.dayOf(nowMs(), zone))
        return if (isUpdateAvailable(release)) {
            CheckOutcome.Available(release)
        } else {
            CheckOutcome.UpToDate
        }
    }

    /** The published SHA-256 for [release]'s APK: the
     * checksum asset when the release carries one, else the
     * release body. Null when the release publishes neither —
     * the caller must fail closed. */
    suspend fun resolveChecksum(release: ReleaseInfo): String? {
        val assetText = release.checksumAsset?.let { http.getText(it.downloadUrl) }
        return ReleaseParsing.extractChecksum(
            checksumAssetText = assetText,
            body = release.body,
            apkName = release.apk.name
        )
    }

    /**
     * Downloads the APK into [targetDir] and verifies it
     * against the published checksum. Any failure removes
     * the partial/untrusted file — a file that failed
     * verification is never left behind to be installed.
     */
    suspend fun downloadAndVerify(
        release: ReleaseInfo,
        targetDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadOutcome {
        val checksum = resolveChecksum(release)
            ?: return DownloadOutcome.ChecksumMissing(release)
        val target = File(targetDir, "xreader-${release.version}.apk")
        val downloaded = http.download(release.apk.downloadUrl, target, onProgress)
        if (!downloaded || !target.isFile || target.length() == 0L) {
            target.delete()
            return DownloadOutcome.Failed("The download didn't finish.")
        }
        return if (Sha256.matches(target, checksum)) {
            DownloadOutcome.Ready(target, release)
        } else {
            target.delete()
            DownloadOutcome.ChecksumMismatch(release)
        }
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Hyperjoker2511/Xreader/releases/latest"
        const val REPO_URL = "https://github.com/Hyperjoker2511/Xreader"
        const val RELEASES_URL = "https://github.com/Hyperjoker2511/Xreader/releases"
        const val PRIVACY_URL =
            "https://github.com/Hyperjoker2511/Xreader/blob/main/PRIVACY.md"
    }
}
