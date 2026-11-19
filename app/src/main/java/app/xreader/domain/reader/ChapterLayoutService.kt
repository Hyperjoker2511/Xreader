package app.xreader.domain.reader

/**
 * v1.7 Patch 2 (item 2a): the chapter layout service — measure +
 * paginate for one chapter, extracted from PagedReader so the
 * paged surfaces of Patches 3–4 (EPUB, PDF) build on the same
 * core the novel reader uses.
 *
 * The seam: a bare text measurer cannot reproduce the blocks
 * the paged reader builds — measuring also needs density, the
 * block text styles and the bionic/annotated-string production.
 * The abstraction is therefore [ChapterMeasurer]: given ONE
 * block of a [ChapterText] plus the layout config, it returns
 * that block's [MeasuredBlock]. The UI-layer implementation
 * captures the Compose `TextMeasurer` + `Density` + the
 * style/annotated-string machinery exactly as PagedReader built
 * them (moved, not reinvented); in tests a `LineWrap`-backed
 * measurer exercises the same service path. The service owns
 * the orchestration — block order, packing via [Paginator] —
 * and stays pure: no Android, no Compose, JVM-testable.
 */
interface ChapterMeasurer {
    fun measure(
        chapterText: ChapterText,
        blockIndex: Int,
        config: ReaderConfig
    ): MeasuredBlock
}

/** One chapter's layout: the measured blocks, the packed pages,
 * and the config fingerprint they were built under. */
data class ChapterLayout(
    val measuredBlocks: List<MeasuredBlock>,
    val pages: List<Page>,
    val fingerprint: List<Any?>
)

class ChapterLayoutService {

    /** The bounded layout cache (item 2b) — current chapter +
     * next chapter. Memory-only; it lives and dies with the
     * service instance its reader holds. */
    val cache: ChapterLayoutCache = ChapterLayoutCache()

    /** Measure every block of [chapterText] in order through
     * [measurer] and pack the blocks into pages of
     * [pageHeightPx]. */
    fun layout(
        chapterText: ChapterText,
        config: ReaderConfig,
        pageHeightPx: Float,
        fingerprint: List<Any?>,
        measurer: ChapterMeasurer
    ): ChapterLayout {
        val measured = chapterText.content.blocks.indices.map { index ->
            measurer.measure(chapterText, index, config)
        }
        return ChapterLayout(
            measuredBlocks = measured,
            pages = Paginator(pageHeightPx).paginate(measured),
            fingerprint = fingerprint
        )
    }
}

/**
 * v1.7 Patch 2 (item 2b): the bounded layout cache — at most
 * [MAX_ENTRIES] entries (the current chapter and the next).
 *
 * The key is (chapterId, CONTENT HASH of the chapter's text,
 * config fingerprint). Content identity is mandatory: terms
 * edits and translation/original toggles change the text under
 * a stable chapterId + config, and a cache keyed on less would
 * serve pages in the wrong char coordinate system — the same
 * place-corruption class Item 0 exists to kill. A text change
 * produces a new key; the stale entry is simply unreachable
 * and the two-entry bound evicts it.
 *
 * The config fingerprint is the tuple (reader config object,
 * font family, column width px, content height px, App-size
 * scale, terms-active flag, translation/original selection) —
 * tuple equality means identical layout inputs. The tuple is
 * assembled by the UI layer (it owns the font family and the
 * density) and treated as opaque here. A stale-fingerprint
 * result is never served: a different tuple is a different key.
 */
class ChapterLayoutCache {

    data class Key(
        val chapterId: Long,
        val contentHash: Int,
        val fingerprint: List<Any?>
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Key, ChapterLayout>()

    val size: Int get() = synchronized(lock) { entries.size }

    fun get(key: Key): ChapterLayout? = synchronized(lock) { entries[key] }

    /** Insert (or refresh) an entry, evicting the eldest beyond
     * the bound. */
    fun put(key: Key, layout: ChapterLayout) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = layout
            while (entries.size > MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 2

        /** Cheap stable identity for a chapter's text: length +
         * the string's content hash. */
        fun contentHash(text: ChapterText): Int =
            31 * text.length + text.text.hashCode()
    }
}

/**
 * v1.7 Patch 2 (item 2b): the layout-ahead policy.
 *
 * Tier gating mirrors the existing prefetch policy: FULL and
 * STANDARD — on; MINIMAL — off (fetch/parse behaviour on
 * MINIMAL is unchanged). The ViewModel, which already reads
 * the tier for prefetch gating, supplies the decision through
 * [enabled].
 *
 * The ahead char cap (amendment M9): entry count is not a size
 * bound — one huge chapter must not be ahead-measured whole.
 * Ahead layout proceeds only when the next chapter's length
 * (exact when parsed, its estimate otherwise) is at most
 * [MAX_AHEAD_CHARS] — the stream planner's existing
 * FULL/STANDARD budget ([StreamWindowPlanner.charBudgetFor])
 * reused as the precedent. A chapter over the cap is simply
 * not laid out ahead; it lays out on arrival, exactly as
 * before.
 */
object LayoutAheadPolicy {

    const val MAX_AHEAD_CHARS: Int = 1_500_000

    /** Ahead is on for every tier except MINIMAL. */
    fun enabled(minimalTier: Boolean): Boolean = !minimalTier

    /** The per-chapter ahead cap (M9). */
    fun withinCap(chapterChars: Int): Boolean =
        chapterChars <= MAX_AHEAD_CHARS
}
