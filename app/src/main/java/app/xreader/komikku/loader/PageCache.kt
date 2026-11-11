/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * v1.5.7 (Komikku full): the donor ChapterCache's reader-facing
 * semantics (eu.kanade.tachiyomi.data.cache.ChapterCache), reduced
 * to what Xreader's stream pipeline needs: page images are stored
 * as files keyed by their image URL, a cached image is served as a
 * file stream (which is what the tiling page view consumes), and
 * the cache trims oldest-first to the eh_cache_size budget
 * (default 75 MB). The donor keys by URL hash on disk too; the
 * page LIST cache is not reproduced — Xreader's stored
 * ChapterPages record (BookStorage) already persists page lists.
 */
package app.xreader.komikku.loader

import java.io.File
import java.security.MessageDigest

/** Donor ChapterCache analogue: URL-keyed page-image files with
 * an LRU byte budget. Thread-safe for the loader's worker pool. */
class KomikkuPageCache(
    private val dir: File,
    private val maxBytes: Long
) {

    init {
        dir.mkdirs()
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }.take(40)
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "img"
        return File(dir, "$key.$ext")
    }

    fun isImageInCache(url: String): Boolean =
        fileFor(url).let { it.isFile && it.length() > 0 }

    /** The cached file for [url], touching its LRU time. */
    fun getImageFile(url: String): File? {
        val file = fileFor(url)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Stores [bytes] for [url] atomically, then trims to budget. */
    fun putImageToCache(url: String, bytes: ByteArray): File {
        val target = fileFor(url)
        val temp = File(dir, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        trimToBudget()
        return target
    }

    @Synchronized
    private fun trimToBudget() {
        if (maxBytes <= 0) return
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }
}
