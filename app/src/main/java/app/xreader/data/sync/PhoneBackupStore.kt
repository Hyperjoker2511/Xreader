package app.xreader.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.xreader.core.error.AppResult
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF plumbing for on-phone backups (v1.0.1): everything goes
 * through the folder the user picked once (a tree URI remembered in
 * SyncPrefs). Framework DocumentsContract only — no extra library.
 * Cancelling the picker is a silent no-op handled by the caller
 * (a null URI simply never reaches here).
 */
class PhoneBackupStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun writeBackup(treeUri: Uri, fileName: String): AppResult<Pair<Uri, OutputStream>> {
        return runCatching {
            val parentDoc = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDoc),
                "application/zip", fileName
            ) ?: error("the folder refused the new file")
            val stream = resolver.openOutputStream(docUri, "wt")
                ?: error("the backup file couldn't be opened")
            docUri to stream
        }.fold(
            onSuccess = { AppResult.Ok(it) },
            onFailure = {
                AppResult.Err(
                    app.xreader.core.error.AppError.ExportFailed(
                        "The backup couldn't be saved to that folder."
                    )
                )
            }
        )
    }

    fun listBackups(treeUri: Uri): List<BackupLogic.BackupEntry> {
        return runCatching {
            val parentDoc = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDoc
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val entries = mutableListOf<BackupLogic.BackupEntry>()
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries += BackupLogic.BackupEntry(
                        name = cursor.getString(1) ?: continue,
                        documentId = cursor.getString(0) ?: continue,
                        sizeBytes = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                        modifiedAtMillis = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    )
                }
            }
            BackupLogic.newestFirst(entries)
        }.getOrDefault(emptyList())
    }

    fun openBackup(treeUri: Uri, documentId: String): InputStream? = runCatching {
        resolver.openInputStream(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        )
    }.getOrNull()

    fun deleteBackup(treeUri: Uri, documentId: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        )
    }.getOrDefault(false)
}
