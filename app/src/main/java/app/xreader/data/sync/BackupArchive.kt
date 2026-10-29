package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local backup/restore (PLAN §7): ONE archive the user keeps wherever
 * he likes, written/read through SAF. Contents:
 *  - `manifest.json` — what this archive is and the section counts
 *    (the "media manifest": lore-art and cover file NAMES are listed
 *    in the payload's records, the media bytes themselves are not —
 *    a backup is data, not a second copy of the library);
 *  - `xreader-backup.json` — the same [SyncPayload] shape sync uses,
 *    so restore runs through the very same merge as a device sync.
 *
 * Keys, cookies, and passwords are structurally absent (§9.5): the
 * payload builder never sees the KeyVault.
 */
object BackupArchive {

    const val PAYLOAD_ENTRY = "xreader-backup.json"
    const val MANIFEST_ENTRY = "manifest.json"
    /** Phase 6: the book-file channel's manifest inside the archive
     * (entries under `files/…` carry the bytes themselves). */
    const val FILES_MANIFEST_ENTRY = "files-manifest.json"
    const val APP_TAG = "xreader-backup"

    fun write(payload: SyncPayload, output: OutputStream) {
        writeArchive(payload, null, emptyList(), output)
    }

    /** Backup WITH the opt-in book files (Phase 6): [files] are
     * (zip entry name → on-disk file) pairs, streamed — a 100 MB
     * book never sits in RAM. */
    fun writeWithFiles(
        payload: SyncPayload,
        filesManifestJson: String?,
        files: List<Pair<String, java.io.File>>,
        output: OutputStream
    ) {
        writeArchive(payload, filesManifestJson, files, output)
    }

    private fun writeArchive(
        payload: SyncPayload,
        filesManifestJson: String?,
        files: List<Pair<String, java.io.File>>,
        output: OutputStream
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = buildJsonObject {
                put("app", APP_TAG)
                put("format", SyncPayload.FORMAT)
                put("exportedAt", payload.exportedAt)
                put("deviceId", payload.deviceId)
                put("books", payload.books.size)
                put("bookmarks", payload.bookmarks.size)
                put("highlights", payload.highlights.size)
                put("lore", payload.lore.size)
                put("terminology", payload.terminology.size)
                put("bookFiles", files.size)
                put(
                    "note",
                    if (files.isEmpty()) {
                        "Reading data only — no book files, no keys, no passwords, no sign-ins."
                    } else {
                        "Reading data plus the book files the user opted to include. " +
                            "No keys, no passwords, no sign-ins."
                    }
                )
            }
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
            zip.write(SyncPayload.encode(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (filesManifestJson != null) {
                zip.putNextEntry(ZipEntry(FILES_MANIFEST_ENTRY))
                zip.write(filesManifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for ((entryName, file) in files) {
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /** Reads + validates an archive. Anything that isn't a readable
     * Xreader backup is a plain [AppError.BadFile], never a crash. */
    fun read(input: InputStream): AppResult<SyncPayload> {
        var manifestOk = false
        var payload: SyncPayload? = null
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            manifestOk = manifestIsOurs(text)
                        }
                        PAYLOAD_ENTRY -> {
                            payload = SyncPayload.decode(
                                zip.readBytes().toString(Charsets.UTF_8)
                            )
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return AppResult.Err(
                AppError.BadFile("that file isn't a readable Xreader backup")
            )
        }
        val decoded = payload
        if (!manifestOk || decoded == null) {
            return AppResult.Err(
                AppError.BadFile("that file isn't a readable Xreader backup")
            )
        }
        return AppResult.Ok(decoded)
    }

    /** Phase 6: like [read], but `files/…` entries are streamed to
     * [fileSink] (entry name + the live zip stream — copy what you
     * keep before returning) and the files-manifest JSON comes back
     * beside the payload. */
    fun readWithFiles(
        input: InputStream,
        fileSink: (String, InputStream) -> Unit
    ): AppResult<Pair<SyncPayload, String?>> {
        var manifestOk = false
        var payload: SyncPayload? = null
        var filesManifest: String? = null
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == MANIFEST_ENTRY -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            manifestOk = manifestIsOurs(text)
                        }
                        entry.name == PAYLOAD_ENTRY -> {
                            payload = SyncPayload.decode(
                                zip.readBytes().toString(Charsets.UTF_8)
                            )
                        }
                        entry.name == FILES_MANIFEST_ENTRY -> {
                            filesManifest = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        entry.name.startsWith("files/") -> fileSink(entry.name, zip)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return AppResult.Err(
                AppError.BadFile("that file isn't a readable Xreader backup")
            )
        }
        val decoded = payload
        if (!manifestOk || decoded == null) {
            return AppResult.Err(
                AppError.BadFile("that file isn't a readable Xreader backup")
            )
        }
        return AppResult.Ok(decoded to filesManifest)
    }

    private fun manifestIsOurs(text: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        val app = (root?.get("app") as? kotlinx.serialization.json.JsonPrimitive)?.content
        app == APP_TAG
    }.getOrDefault(false)
}
