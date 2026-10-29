package app.xreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Extension & download tables (PLAN §1.3). */

@Entity(tableName = "extension_repos")
data class ExtensionRepo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val indexUrl: String,
    /** Pinned after first fetch (TOFU, §9.1); null until then. */
    val indexSha256: String? = null,
    val lastFetchedAt: Long? = null,
    val enabled: Boolean = true
)

@Entity(
    tableName = "installed_extensions",
    foreignKeys = [
        ForeignKey(
            entity = ExtensionRepo::class,
            parentColumns = ["id"],
            childColumns = ["repoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("repoId"), Index(value = ["packageId"], unique = true)]
)
data class InstalledExtension(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val displayName: String,
    /** The repo's package id for this extension (stable identity). */
    val packageId: String,
    val versionName: String,
    val versionCode: Long,
    val kind: ExtensionKind = ExtensionKind.DECLARATIVE,
    /** Dex apk or JS bundle under extensions/<id>/; null for declarative. */
    val artifactPath: String? = null,
    /** SHA-256 fingerprint recorded at install (§9.1). */
    val sha256: String,
    val apiLevel: Int,
    /** Comma-separated language codes the extension declares. */
    val languages: String = "",
    /** Comma-separated source ids this package provides. */
    val sourceIds: String = "",
    val enabled: Boolean = true,
    val trustState: TrustState = TrustState.TRUSTED,
    /** Declared domains + toggles as JSON (parsed at the boundary, §9.1). */
    val permissionsJson: String = "{}",
    val installedAt: Long,
    val updatedAt: Long
)

/**
 * The Downloads screen's source of truth — a mirror of the WorkManager
 * queue (Phase 2 fills it), so what the user sees survived process death.
 */
@Entity(
    tableName = "download_queue",
    indices = [Index("bookId"), Index("state")]
)
data class DownloadQueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long? = null,
    val kind: DownloadKind = DownloadKind.CHAPTER,
    val state: QueueState = QueueState.QUEUED,
    val attempts: Int = 0,
    /** Technical detail of the last failure (AppError.detail discipline). */
    val lastErrorDetail: String? = null,
    val enqueuedAt: Long,
    val updatedAt: Long
)
