package app.xreader.data.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** An InputStream that SHA-256-hashes everything read through it. */
private class HashingInputStream(private val delegate: InputStream) : InputStream() {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) digest.update(b.toByte())
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (read > 0) digest.update(buffer, offset, read)
        return read
    }

    val sha256Hex: String
        get() = digest.digest().joinToString("") { "%02x".format(it) }

    override fun close() = delegate.close()
}

/**
 * On-disk home of everything extension-shaped (PLAN §1.4):
 *
 * ```
 * filesDir/extensions/
 *   <packageId>/artifact          the pinned js/dex bytes
 *   <packageId>/definition.json   declarative rules / js+dex metadata
 *   <packageId>/pending-*         a staged update awaiting re-trust
 *   <packageId>/store.json        the extension's private KV store
 *   <packageId>/dexopt/           DexClassLoader optimised output
 *   repos/<repoId>.json           last fetched index (offline catalogue)
 *   rules/global.json             user cleaner rules (scopes)
 *   rules/ext-<packageId>.json
 *   rules/book-<bookId>.json
 * ```
 *
 * An extension's directory is the only place the host ever writes for
 * it, and the runtime only ever reads its own directory — that is the
 * practical shape of "its own sandbox directory" on Android (§9.1).
 */
class ExtensionStore(private val filesDir: File) {

    private val root = File(filesDir, "extensions").apply { mkdirs() }

    fun packageDir(packageId: String): File =
        File(root, packageId.sanitise()).apply { mkdirs() }

    fun artifactFile(packageId: String): File = File(packageDir(packageId), "artifact")
    fun pendingArtifactFile(packageId: String): File =
        File(packageDir(packageId), "pending-artifact")
    fun definitionFile(packageId: String): File = File(packageDir(packageId), "definition.json")
    fun pendingDefinitionFile(packageId: String): File =
        File(packageDir(packageId), "pending-definition.json")
    fun pendingMetaFile(packageId: String): File = File(packageDir(packageId), "pending.json")
    fun storeFile(packageId: String): File = File(packageDir(packageId), "store.json")
    fun dexOptDir(packageId: String): File =
        File(packageDir(packageId), "dexopt").apply { mkdirs() }

    fun repoIndexFile(repoId: Long): File =
        File(root, "repos").apply { mkdirs() }.let { File(it, "$repoId.json") }

    // ── Keiyoushi player (K3) ─────────────────────────────────

    /** The player's extension files live OUTSIDE the
     * extensions/<packageId> tree: donor-shaped flat files,
     * `<packageName>.ext`, read-only once installed (the
     * Komikku private-extension layout — Android 14+ requires
     * dex-bearing files to be read-only). */
    fun keiyoushiExtDir(): File =
        File(filesDir, "keiyoushi-exts").apply { mkdirs() }

    fun keiyoushiExtFile(packageName: String): File =
        File(keiyoushiExtDir(), packageName.sanitise() + ".ext")

    /** A downloaded APK awaiting verification + consent. */
    fun keiyoushiStagingFile(packageName: String): File =
        File(keiyoushiExtDir(), packageName.sanitise() + ".staging")

    /** The player's persisted per-extension records (the K3
     * state machine's durable form — file-backed like the
     * pack state, so no Room migration rides this slice). */
    fun keiyoushiPlayerStateFile(): File =
        File(root, "keiyoushi-player.json")

    /** v1.3.5 (Cycle 1, provider packs): one pack channel's
     * persisted state (active/previous pack documents + check
     * bookkeeping) — see [ProviderPackManager]. Lives beside
     * the repo indexes; the pack id is sanitised like a
     * package id before it becomes a file name. */
    fun packStateFile(packId: String): File =
        File(root, "packs").apply { mkdirs() }
            .let { File(it, "${packId.sanitise()}.json") }

    /** The pack ids that have a state file on disk — the
     * cross-pack ownership check reads every channel's state. */
    fun packIdsOnDisk(): List<String> =
        File(root, "packs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()

    /** v1.3.2 (scope E): the persisted site list of a FOREIGN
     * repository (another app's index) — foreign repos get their
     * own screen long after the add flow that detected them. */
    fun foreignRepoFile(repoId: Long): File =
        File(root, "repos").apply { mkdirs() }
            .let { File(it, "$repoId.foreign.json") }

    fun rulesFile(scope: String): File =
        File(root, "rules").apply { mkdirs() }.let { File(it, "$scope.json") }

    /** Write bytes atomically; returns the SHA-256 of what landed. */
    fun writeArtifact(target: File, bytes: ByteArray): String {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        return Hashes.sha256Hex(bytes)
    }

    /** Stream an artifact to disk, hashing as it lands. */
    fun writeArtifactStream(target: File, input: InputStream): Pair<Long, String> {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val hashing = HashingInputStream(input)
        var total = 0L
        tmp.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = hashing.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return total to hashing.sha256Hex
    }

    fun readText(file: File): String? =
        runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else null }.getOrNull()

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun hashFile(file: File): String? {
        if (!file.exists()) return null
        return runCatching {
            file.inputStream().use { input ->
                val hashing = HashingInputStream(input)
                val buffer = ByteArray(64 * 1024)
                while (hashing.read(buffer) >= 0) { /* drain */ }
                hashing.sha256Hex
            }
        }.getOrNull()
    }

    fun deletePackage(packageId: String) {
        runCatching { packageDir(packageId).deleteRecursively() }
    }

    fun hasPending(packageId: String): Boolean = pendingMetaFile(packageId).exists()

    fun readPending(packageId: String): PendingUpdate? =
        readText(pendingMetaFile(packageId))?.let { raw ->
            runCatching { pendingJson.decodeFromString(PendingUpdate.serializer(), raw) }
                .getOrNull()
        }

    fun writePending(packageId: String, pending: PendingUpdate) {
        writeText(
            pendingMetaFile(packageId),
            pendingJson.encodeToString(PendingUpdate.serializer(), pending)
        )
    }

    fun clearPending(packageId: String) {
        runCatching {
            pendingMetaFile(packageId).delete()
            pendingArtifactFile(packageId).delete()
            pendingDefinitionFile(packageId).delete()
        }
    }

    /** Accept a staged update: pending files become the live ones. */
    fun promotePending(packageId: String) {
        val pendingArtifact = pendingArtifactFile(packageId)
        if (pendingArtifact.exists()) {
            pendingArtifact.renameTo(artifactFile(packageId))
        }
        val pendingDefinition = pendingDefinitionFile(packageId)
        if (pendingDefinition.exists()) {
            pendingDefinition.renameTo(definitionFile(packageId))
        }
        clearPending(packageId)
    }

    companion object {
        private val pendingJson = Json { ignoreUnknownKeys = true }

        /** Package ids become directory names — keep them tame. */
        fun String.sanitise(): String =
            replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "pkg" }
    }
}

/** A staged update waiting for the user's decision (§9.1). */
@Serializable
data class PendingUpdate(
    val versionName: String,
    val versionCode: Long,
    val fingerprint: String,
    val permissionsJson: String,
    val reasons: List<String> = emptyList(),
    val stagedAt: Long = 0L
)
