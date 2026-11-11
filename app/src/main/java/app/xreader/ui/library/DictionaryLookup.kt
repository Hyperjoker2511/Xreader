package app.xreader.ui.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.5.10 (F3, build-map Group F): the offline dictionary
 * lookup core, EXTRACTED from `VocabViewModel.lookup`
 * (VocabScreen.kt) so the readers' Dictionary selection action
 * and the Vocab screen share ONE implementation — the tree has
 * no standalone dictionary engine; this file seam over the
 * user's own dictionary file is the honest shape the map
 * names. Behaviour is the extraction's source, unchanged: the
 * dictionary is the single file under `filesDir/dictionary/`,
 * lines of `word<TAB>definition`; a lookup is a trimmed,
 * case-insensitive exact match on the word side. Null = no
 * dictionary installed or no entry — callers say so plainly.
 */
object DictionaryLookup {

    /** The directory holding THE dictionary file. */
    fun dictionaryDir(filesDir: File): File = File(filesDir, "dictionary")

    /** The installed dictionary file, or null when none is. */
    fun dictionaryFile(filesDir: File): File? =
        dictionaryDir(filesDir).listFiles()?.firstOrNull { it.isFile }

    /** Looks [word] up in the dictionary installed under
     * [filesDir]. */
    suspend fun lookup(filesDir: File, word: String): String? =
        lookupInFile(dictionaryFile(filesDir), word)

    /** Looks [word] up in [file] (the extracted core —
     * VocabViewModel's original body, verbatim semantics). */
    suspend fun lookupInFile(file: File?, word: String): String? {
        if (file == null) return null
        val needle = word.trim().lowercase()
        if (needle.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.firstOrNull { line ->
                        line.substringBefore('\t').trim().lowercase() == needle
                    }?.substringAfter('\t', "")?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }
    }

    /**
     * The dictionary query for a selection: its FIRST word,
     * trimmed of surrounding punctuation. A multi-word
     * selection looks up its head word (the re-run screen's
     * shape); an empty selection yields an empty string and
     * callers offer no lookup.
     */
    fun firstWord(selectionText: String): String =
        selectionText.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .trim { ch -> !ch.isLetterOrDigit() && ch != '\'' && ch != '-' }
}
