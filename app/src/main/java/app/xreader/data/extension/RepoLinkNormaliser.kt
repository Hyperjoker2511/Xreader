package app.xreader.data.extension

import java.net.URLDecoder

/**
 * Repo-link normalisation (v1.0.9): users paste repository
 * links in every shape the other reader apps accept — a full
 * index URL, a link to the repo's GitHub page, a
 * `github.com/…/raw/…` address, an `owner/repo` shorthand, a
 * `tachiyomi://add-repo?url=…` deep link. This turns any of
 * them into an ordered list of candidate index URLs to try,
 * best first. Pure string work; fetching and judging live in
 * [RepoManager].
 *
 * The probe order inside one repository directory is:
 * `repo.json` (the tachiyomix pointer file) → `index.json`
 * (tachiyomix JSON / classic pretty index) → `index.pb`
 * (tachiyomix binary) → `index.min.json` (classic minified —
 * last because tachiyomix repos now publish it as a stub).
 * When the paste names a file directly, that file is tried
 * first, then its siblings in probe order.
 */
object RepoLinkNormaliser {

    private val INDEX_FILES = listOf(
        "repo.json", "index.json", "index.pb", "index.min.json"
    )

    private val SHORTHAND = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun candidates(rawPaste: String): List<String> {
        var paste = rawPaste.trim()
        if (paste.isEmpty()) return emptyList()

        // Deep links wrap the real index URL.
        if (paste.startsWith("tachiyomi://") || paste.startsWith("aniyomi://")) {
            val wrapped = paste.substringAfter("url=", "")
                .substringBefore("&")
            if (wrapped.isNotBlank()) {
                paste = runCatching {
                    URLDecoder.decode(wrapped, "UTF-8")
                }.getOrDefault(wrapped)
            }
        }

        // owner/repo shorthand (a TachiyomiAZ-proven shape):
        // the repo branch of that GitHub project is the index.
        if (SHORTHAND.matches(paste)) {
            return directoryCandidates(
                "https://raw.githubusercontent.com/$paste/repo/"
            )
        }

        // GitHub page shapes → their raw.githubusercontent form.
        githubRawBase(paste)?.let { rawBase ->
            return if (rawBase.endsWith("/")) {
                directoryCandidates(rawBase)
            } else {
                fileCandidates(rawBase)
            }
        }

        if (!paste.startsWith("http://") && !paste.startsWith("https://")) {
            return listOf(paste)
        }
        return if (looksLikeFile(paste)) {
            fileCandidates(paste)
        } else {
            directoryCandidates(paste)
        }
    }

    /** The URL itself, then its directory's probe order. */
    private fun fileCandidates(fileUrl: String): List<String> {
        val directory = fileUrl.substringBeforeLast("/", "") + "/"
        val siblings = directoryCandidates(directory)
        return (listOf(fileUrl) + siblings).distinct()
    }

    private fun directoryCandidates(directoryUrl: String): List<String> {
        val base = if (directoryUrl.endsWith("/")) directoryUrl
        else "$directoryUrl/"
        return INDEX_FILES.map { base + it }
    }

    private fun looksLikeFile(url: String): Boolean {
        // Judge by the last PATH segment only — the host itself
        // contains dots and must never make a bare site root
        // (https://example.com) look like a file.
        val path = url.substringAfter("://", "")
            .substringAfter("/", "")
            .substringBefore("?")
        if (path.isBlank()) return false
        val last = path.substringAfterLast("/")
        return last.contains(".")
    }

    /**
     * A github.com URL → the equivalent raw base: a repo PAGE
     * becomes the raw root of its conventional `repo` branch
     * (or of the branch a /tree/<branch> page names); a
     * /raw/ or /blob/ file address becomes the raw file URL.
     * Null when the URL isn't a GitHub shape we know.
     */
    private fun githubRawBase(url: String): String? {
        val prefix = "https://github.com/"
        if (!url.startsWith(prefix)) return null
        val parts = url.removePrefix(prefix)
            .split("/")
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val ownerRepo = "${parts[0]}/${parts[1]}"
        val raw = "https://raw.githubusercontent.com/$ownerRepo"
        return when {
            parts.size == 2 -> "$raw/repo/"
            parts[2] == "tree" && parts.size >= 4 ->
                "$raw/${parts[3]}/" + parts.drop(4).joinToString("/")
                    .let { if (it.isEmpty()) "" else "$it/" }
            parts[2] == "raw" && parts.size >= 5 ->
                "$raw/${parts[3]}/" + parts.drop(4).joinToString("/")
            parts[2] == "blob" && parts.size >= 5 ->
                "$raw/${parts[3]}/" + parts.drop(4).joinToString("/")
            else -> "$raw/repo/"
        }
    }
}
