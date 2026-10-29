package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult

/**
 * "Edit parser rules" (v1.0.4, D14): the pure mapping between a
 * stored [DeclarativeConfig] and the editor's plain-labelled
 * fields, plus the candidate builder the save flow gates on.
 *
 * The editor covers the reading core — finding books (search +
 * catalogue listings), the book page, the chapter list, chapter
 * text. Untouched parts of the config (latest/bookshelf listings,
 * reviews, comments, related, cleaners, POST bodies…) are carried
 * through [DeclarativeConfig.copy] unchanged.
 *
 * Save-gating rules, all honest:
 * - The chapter list rules and the chapter-text rule can never be
 *   blanked — a source without them cannot read.
 * - A listing that exists cannot be cleared away from the editor
 *   (a source needs its book list); a listing that doesn't exist
 *   can be added only by filling its address + result + title +
 *   link rules completely.
 * - Any selector field may carry a fallback chain ([SelectorChains]):
 *   candidates separated by `||`, first that finds something wins.
 *
 * Building a candidate never saves anything: the caller runs the
 * mandatory test-drive ([LinkProber.testDrive]) on it and only a
 * passing candidate replaces the stored rules.
 */
object ParserRulesEditor {

    /** One listing's editable fields (search / catalogue shape). */
    data class ListingFields(
        val url: String = "",
        val results: String = "",
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val author: String = "",
        val nextPage: String = ""
    ) {
        val isBlank: Boolean
            get() = url.isBlank() && results.isBlank() && title.isBlank() &&
                link.isBlank() && cover.isBlank() && author.isBlank() &&
                nextPage.isBlank()
    }

    data class Fields(
        val search: ListingFields = ListingFields(),
        val popular: ListingFields = ListingFields(),
        val detailsTitle: String = "",
        val detailsAuthor: String = "",
        val detailsDescription: String = "",
        val detailsCover: String = "",
        val chapterList: String = "",
        val chapterName: String = "",
        val chapterLink: String = "",
        val chapterReverse: Boolean = false,
        val contentSelector: String = "",
        val contentTitle: String = "",
        val contentNextPage: String = "",
        /** One remove-selector per line. */
        val contentRemove: String = ""
    )

    fun fieldsOf(config: DeclarativeConfig): Fields = Fields(
        search = config.search?.let { listingFieldsOf(it) } ?: ListingFields(),
        popular = config.popular?.let { listingFieldsOf(it) } ?: ListingFields(),
        detailsTitle = config.details.title ?: "",
        detailsAuthor = config.details.author ?: "",
        detailsDescription = config.details.description ?: "",
        detailsCover = config.details.cover ?: "",
        chapterList = config.details.chapters.list,
        chapterName = config.details.chapters.name,
        chapterLink = config.details.chapters.link,
        chapterReverse = config.details.chapters.reverse,
        contentSelector = config.chapter.content,
        contentTitle = config.chapter.title ?: "",
        contentNextPage = config.chapter.nextPage ?: "",
        contentRemove = config.chapter.remove.joinToString("\n")
    )

    private fun listingFieldsOf(spec: ListSpec): ListingFields = ListingFields(
        url = spec.url,
        results = spec.results,
        title = spec.title,
        link = spec.link,
        cover = spec.cover ?: "",
        author = spec.author ?: "",
        nextPage = spec.nextPage ?: ""
    )

    /**
     * The candidate config for [fields], or a plain-words error
     * naming what must be fixed first. Nothing is saved here.
     */
    fun buildCandidate(
        original: DeclarativeConfig,
        fields: Fields
    ): AppResult<DeclarativeConfig> {
        val search = when (
            val built = buildListing("Search", original.search, fields.search)
        ) {
            is AppResult.Err -> return built
            is AppResult.Ok -> built.value
        }
        val popular = when (
            val built = buildListing("Catalogue", original.popular, fields.popular)
        ) {
            is AppResult.Err -> return built
            is AppResult.Ok -> built.value
        }
        if (fields.chapterList.isBlank() || fields.chapterName.isBlank() ||
            fields.chapterLink.isBlank()
        ) {
            return AppResult.Err(
                AppError.BadFile(
                    "The chapter list rules can't be empty — a source " +
                        "needs them to open any book."
                )
            )
        }
        if (fields.contentSelector.isBlank()) {
            return AppResult.Err(
                AppError.BadFile(
                    "The chapter text rule can't be empty — it's how " +
                        "Xreader finds the story on a chapter page."
                )
            )
        }
        return AppResult.Ok(
            original.copy(
                search = search,
                popular = popular,
                details = original.details.copy(
                    title = fields.detailsTitle.trim().ifBlank { null },
                    author = fields.detailsAuthor.trim().ifBlank { null },
                    description = fields.detailsDescription.trim()
                        .ifBlank { null },
                    cover = fields.detailsCover.trim().ifBlank { null },
                    chapters = original.details.chapters.copy(
                        list = fields.chapterList.trim(),
                        name = fields.chapterName.trim(),
                        link = fields.chapterLink.trim(),
                        reverse = fields.chapterReverse
                    )
                ),
                chapter = original.chapter.copy(
                    content = fields.contentSelector.trim(),
                    title = fields.contentTitle.trim().ifBlank { null },
                    nextPage = fields.contentNextPage.trim().ifBlank { null },
                    remove = fields.contentRemove.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                )
            )
        )
    }

    private fun buildListing(
        label: String,
        original: ListSpec?,
        fields: ListingFields
    ): AppResult<ListSpec?> {
        if (fields.isBlank) {
            return if (original == null) {
                AppResult.Ok(null)
            } else {
                AppResult.Err(
                    AppError.BadFile(
                        "The $label rules can't be cleared away — a " +
                            "source needs its book list to work."
                    )
                )
            }
        }
        if (fields.url.isBlank() || fields.results.isBlank() ||
            fields.title.isBlank() || fields.link.isBlank()
        ) {
            return AppResult.Err(
                AppError.BadFile(
                    "The $label rules need an address, a result rule, " +
                        "a title rule and a link rule — one of them " +
                        "is empty."
                )
            )
        }
        val filled = ListSpec(
            url = fields.url.trim(),
            results = fields.results.trim(),
            title = fields.title.trim(),
            link = fields.link.trim(),
            cover = fields.cover.trim().ifBlank { null },
            author = fields.author.trim().ifBlank { null },
            nextPage = fields.nextPage.trim().ifBlank { null }
        )
        // copy() over the original keeps what the editor doesn't
        // show (method, POST body) exactly as it was.
        return AppResult.Ok(
            original?.copy(
                url = filled.url,
                results = filled.results,
                title = filled.title,
                link = filled.link,
                cover = filled.cover,
                author = filled.author,
                nextPage = filled.nextPage
            ) ?: filled
        )
    }
}
