package app.xreader.data.ai

import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreArtDao
import app.xreader.data.files.BookStorage

/**
 * The ONE delete path for generated art (Patch 10): a picture is
 * its row AND its file, and both go together. The Lore page and
 * the Generated art gallery both delete through here — there is
 * no second delete for art anywhere in the app.
 */
class LoreArtStore(
    private val artDao: LoreArtDao,
    private val storage: BookStorage
) {

    suspend fun delete(art: LoreArt) {
        artDao.delete(art)
        runCatching { storage.resolve(art.imagePath).delete() }
    }
}
