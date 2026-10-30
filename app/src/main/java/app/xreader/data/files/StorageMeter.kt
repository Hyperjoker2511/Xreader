package app.xreader.data.files

import java.io.File

/**
 * Storage accounting (v1.0.2, R07/G16): the user must be able to
 * tell valuable data (downloaded chapters, book files) from
 * rebuildable cache (covers, temp files) BEFORE deleting anything.
 * The categories map to BookStorage's real directories; "Other" is
 * whatever else lives under filesDir, computed as the remainder so
 * the rows always add up to the true total.
 */
data class StorageBreakdown(
    val downloadedTextBytes: Long,
    val bookFilesBytes: Long,
    val coverCacheBytes: Long,
    val tempBytes: Long,
    val otherBytes: Long
) {
    val totalBytes: Long
        get() = downloadedTextBytes + bookFilesBytes + coverCacheBytes +
            tempBytes + otherBytes
}

object StorageMeter {

    fun measure(filesDir: File): StorageBreakdown {
        val content = dirSize(File(filesDir, "content"))
        val books = dirSize(File(filesDir, "books"))
        val covers = dirSize(File(filesDir, "covers"))
        val temp = dirSize(File(filesDir, "downloads-temp"))
        // v1.0.6.2 (Gemini fix round, G6): import staging is temp
        // too — a crashed import used to strand its staged book
        // file in the unaccountable "Other" remainder, where the
        // cleanup flow could neither see nor clear it.
        val staging = dirSize(File(filesDir, "import-staging"))
        val total = dirSize(filesDir)
        val known = content + books + covers + temp + staging
        return StorageBreakdown(
            downloadedTextBytes = content,
            bookFilesBytes = books,
            coverCacheBytes = covers,
            tempBytes = temp + staging,
            otherBytes = (total - known).coerceAtLeast(0)
        )
    }

    fun dirSize(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        var sum = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) sum += child.length() else stack.add(child)
            }
        }
        return sum
    }

    /** Plain sizes: "812 KB", "1.4 MB", "2.31 GB". */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100) "%.0f %s".format(value, units[unit])
        else "%.1f %s".format(value, units[unit])
    }
}
