package app.xreader.data.migration

/**
 * The one shape every importer in the Migration Studio produces
 * (Phase 6): a library list with per-book read state, in plain
 * Kotlin, whoever the source app was. The apply step
 * ([MigrationService]) only ever sees this model, so a new backup
 * format is a new parser, never new import logic.
 */
data class MigratedChapter(
    val name: String,
    val url: String,
    val read: Boolean = false,
    val number: Float? = null,
    /** The source app's own ordering, when it records one. */
    val sourceOrder: Long? = null
)

data class MigratedBook(
    val title: String,
    val author: String = "",
    val description: String = "",
    /** Display name of the source in the old app ("" when unknown). */
    val sourceName: String = "",
    /** The old app's numeric source id (Mihon), when it has one. */
    val sourceId: Long? = null,
    /** The book's address on its source — becomes the remote id. */
    val url: String = "",
    val thumbnailUrl: String? = null,
    /** Mihon status code (1 ongoing, 2 completed, …), when known. */
    val status: Int? = null,
    val chapters: List<MigratedChapter> = emptyList(),
    val categoryNames: List<String> = emptyList()
)

data class MigratedLibrary(
    /** Plain-words name of the format that was read. */
    val formatName: String,
    val books: List<MigratedBook> = emptyList(),
    /** Every category/shelf name the backup carried. Xreader has
     * no shelves yet — the names are reported, never dropped
     * silently (PLAN §8). */
    val categories: List<String> = emptyList()
)
