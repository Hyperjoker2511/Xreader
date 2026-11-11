package app.xreader.data.library

/**
 * v1.5.2 (§13.4): the doubtful-key migration, pure. Existing row-id
 * favourite keys convert through the CURRENT row → natural-key map:
 * a key whose row exists converts; a key whose row no longer exists
 * is DROPPED (under rowid reuse it may name a wrong live book, so it
 * is never carried over as that book's favourite).
 */
object FavouriteKeys {
    data class Migration(val converted: Set<String>, val droppedCount: Int)

    fun migrate(
        oldRowIdKeys: Set<Long>,
        rowIdToNaturalKey: Map<Long, String>
    ): Migration {
        val converted = mutableSetOf<String>()
        var dropped = 0
        for (id in oldRowIdKeys) {
            val key = rowIdToNaturalKey[id]
            if (key != null) converted += key else dropped++
        }
        return Migration(converted, dropped)
    }

    /** §13.2 merge transfer: the survivor gains the key if EITHER row
     * had it (union). */
    fun mergedKeys(
        survivorKey: String,
        duplicateKey: String,
        favourites: Set<String>
    ): Set<String> =
        if (duplicateKey in favourites || survivorKey in favourites) {
            favourites - duplicateKey + survivorKey
        } else {
            favourites
        }
}
