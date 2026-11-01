package app.xreader.data.extension

import kotlinx.serialization.Serializable

/**
 * Manga programme (Cycle 1, slice M1): the media kind of a
 * source, its content rating, and the ONE content-preference
 * setting that decides which ratings are listed — Mihon's
 * model, as the user chose it (CYCLE1-PLAN, open question 3):
 * sources CARRY a rating; the preference decides what's
 * listed; the default hides 18+.
 */

/** What a source serves: prose chapters (every source before
 * the manga programme) or image chapters (manga/manhwa). The
 * flag lives on [DeclarativeConfig] — the least invasive
 * place: no schema change, and a book's kind is always
 * derivable from its source's config (the Library split
 * filters on exactly that). Defaulted so every config written
 * before the manga programme decodes as TEXT, unchanged. */
@Serializable
enum class MediaKind {
    TEXT,
    MANGA;

    val isManga: Boolean get() = this == MANGA
}

/** A source's content rating (Mihon model): SAFE for general
 * sources, MIXED for sources whose catalogue mixes general
 * and adult material, ADULT for 18+ sources. Keiyoushi's
 * index carries the same three values as its contentWarning
 * (see [KeiyoushiIndex]); conversions stamp the rating onto
 * the converted config. Defaulted SAFE everywhere — an
 * unrated source is a general source. */
@Serializable
enum class ContentRating {
    SAFE,
    MIXED,
    ADULT;

    /** The plain label used wherever the rating is named. */
    val label: String
        get() = when (this) {
            SAFE -> "Safe"
            MIXED -> "Mixed"
            ADULT -> "18+"
        }

    /** The small badge shown where a VISIBLE source is listed
     * (its row, its own page): Safe sources carry no badge —
     * the badge exists to mark what a reader might want to
     * know, not to decorate every row. */
    val badge: String?
        get() = when (this) {
            SAFE -> null
            MIXED -> "Mixed"
            ADULT -> "18+"
        }

    /** Higher = more restrictive content (for "strongest
     * rating in a package" computations). */
    val severity: Int get() = ordinal
}

/**
 * The ONE content-preference setting (Settings → Sources &
 * repairs): how much of the rated catalogue is listed.
 * Stored by name in [app.xreader.data.prefs.SourcePrefs];
 * [HIDE_ADULT] is the default the plan fixed — 18+ sources
 * stay out of Explore, search and repo lists until the
 * reader chooses otherwise.
 */
enum class ContentVisibility {
    /** Everything is listed, badges shown. */
    SHOW_ALL,

    /** 18+ sources are hidden from lists and search. */
    HIDE_ADULT,

    /** Mixed and 18+ sources are hidden from lists and search. */
    HIDE_MIXED_AND_ADULT;

    /** The chip label (plain words). */
    val label: String
        get() = when (this) {
            SHOW_ALL -> "Show everything"
            HIDE_ADULT -> "Hide 18+"
            HIDE_MIXED_AND_ADULT -> "Hide Mixed and 18+"
        }

    /** The sentence under the chips (plain words). */
    val description: String
        get() = when (this) {
            SHOW_ALL ->
                "Every source is listed, with its rating shown."
            HIDE_ADULT ->
                "Sources rated 18+ stay out of Explore, search " +
                    "and repository lists. Books you already have " +
                    "are never hidden."
            HIDE_MIXED_AND_ADULT ->
                "Only Safe sources are listed. Books you already " +
                    "have are never hidden."
        }

    companion object {
        /** The plan's default: hide 18+. */
        val DEFAULT: ContentVisibility = HIDE_ADULT

        fun fromName(name: String?): ContentVisibility =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The rating rules, pure: does this preference list a source
 * with this rating, and what badge does a listed source wear.
 * Every list/search seam (Explore, global search, repo
 * listings, the Keiyoushi catalogue) filters through here so
 * the model can never drift between surfaces.
 */
object ContentAccess {

    fun allows(rating: ContentRating, visibility: ContentVisibility): Boolean =
        when (visibility) {
            ContentVisibility.SHOW_ALL -> true
            ContentVisibility.HIDE_ADULT -> rating != ContentRating.ADULT
            ContentVisibility.HIDE_MIXED_AND_ADULT -> rating == ContentRating.SAFE
        }

    /** A package/extension is listed when AT LEAST ONE of its
     * sources is listed; it disappears only when every source
     * it carries is hidden. */
    fun anyAllowed(
        ratings: List<ContentRating>,
        visibility: ContentVisibility
    ): Boolean = ratings.any { allows(it, visibility) }

    /** The badge a listed row wears: the badge of the
     * STRONGEST rating still visible under [visibility]
     * (a package carrying Safe + Mixed shows "Mixed" when
     * Mixed is listed; it shows nothing when only its Safe
     * sources are visible). Null = no badge. */
    fun visibleBadge(
        ratings: List<ContentRating>,
        visibility: ContentVisibility
    ): String? = ratings
        .filter { allows(it, visibility) }
        .maxByOrNull { it.severity }
        ?.badge

    fun ratingOf(config: DeclarativeConfig): ContentRating = config.contentRating

    fun mediaKindOf(config: DeclarativeConfig): MediaKind = config.mediaKind

    fun isManga(config: DeclarativeConfig): Boolean = config.mediaKind.isManga
}
