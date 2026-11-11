package app.xreader.ui.library

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (F3) gate for the dictionary extraction: the lookup
 * that used to live inside VocabViewModel (VocabScreen.kt)
 * now lives in [DictionaryLookup], shared by the readers'
 * selection Dictionary action. Semantics are the Vocab ones,
 * unchanged: `word<TAB>definition` lines, trimmed,
 * case-insensitive; a missing file is a null, not an error.
 */
class V1510DictionaryLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dictionaryFile(): File {
        val dir = File(tmp.root, "dictionary").apply { mkdirs() }
        return File(dir, "dictionary.tsv").apply {
            writeText(
                "serendipity\tthe luck of finding what you did not seek\n" +
                    "Ephemeral\tlasting a very short time\n" +
                    "no-tab-line\n" +
                    "\n"
            )
        }
    }

    @Test
    fun `hit returns the definition`() = runTest {
        assertEquals(
            "the luck of finding what you did not seek",
            DictionaryLookup.lookupInFile(dictionaryFile(), "serendipity")
        )
    }

    @Test
    fun `lookup is trimmed and case insensitive`() = runTest {
        assertEquals(
            "lasting a very short time",
            DictionaryLookup.lookupInFile(dictionaryFile(), "  EPHEMERAL ")
        )
    }

    @Test
    fun `miss and missing file are null`() = runTest {
        assertNull(DictionaryLookup.lookupInFile(dictionaryFile(), "absent"))
        assertNull(
            DictionaryLookup.lookupInFile(File(tmp.root, "nope.tsv"), "word")
        )
        assertNull(DictionaryLookup.lookup(tmp.root, "word"))
    }

    @Test
    fun `lookup resolves through the files dir layout`() = runTest {
        val dir = DictionaryLookup.dictionaryDir(tmp.root).apply { mkdirs() }
        File(dir, "words.tsv").writeText("halcyon\tdenoting a period of calm\n")
        assertEquals(
            "denoting a period of calm",
            DictionaryLookup.lookup(tmp.root, "halcyon")
        )
        assertTrue(dir.path.endsWith("dictionary"))
        assertEquals(
            "words.tsv",
            DictionaryLookup.dictionaryFile(tmp.root)?.name
        )
    }

    @Test
    fun `first word takes the first token and trims punctuation`() {
        assertEquals("serendipity", DictionaryLookup.firstWord("serendipity"))
        assertEquals(
            "ephemeral",
            DictionaryLookup.firstWord("  ephemeral, fleeting thing")
        )
        assertEquals("", DictionaryLookup.firstWord("   "))
        assertEquals("", DictionaryLookup.firstWord(""))
    }

    @Test
    fun `vocab screen delegates to the shared lookup`() {
        val file = listOf(
            File("src/main/java/app/xreader/ui/library/VocabScreen.kt"),
            File("app/src/main/java/app/xreader/ui/library/VocabScreen.kt")
        ).first { it.exists() }
        assertTrue(file.readText().contains("DictionaryLookup"))
    }
}
