package app.xreader.data.extension

/**
 * Identity/metadata fallback chain — groundwork (v1.0.2, R12
 * foundation; the full resolver UI/screen wiring is R12/R16).
 *
 * Item 15's deeper lesson: book identity and metadata arrive from
 * several places (the book's own source, another source's copy of
 * the same book, the file itself, the user's own edit) and v1.0.1
 * had no explicit precedence — whichever writer ran last won, and a
 * blank could erase a good value (the synopsis-vanish class).
 *
 * This pure resolver makes the chain explicit and testable:
 *  - candidates carry their ORIGIN and a confidence;
 *  - a USER-LOCKED value always wins (the user's edit is final);
 *  - otherwise the highest-priority origin with a NON-BLANK value
 *    wins — blank never overwrites, at any priority;
 *  - the result records where the value came from, so screens can
 *    show provenance ("from Wuxiaworld") instead of guessing.
 */
object MetadataFallback {

    /** Where a metadata value came from, in winning order. */
    enum class Origin(val priority: Int) {
        USER_EDIT(400),
        OWN_SOURCE(300),
        OTHER_SOURCE_EXACT(200),
        FILE(100),
        INFERRED(50)
    }

    data class Candidate(
        val value: String?,
        val origin: Origin,
        /** 0–1; breaks ties inside one origin (exact-title match > fuzzy). */
        val confidence: Float = 1f,
        /** The user pinned this field; nothing automatic may replace it. */
        val userLocked: Boolean = false
    )

    data class Resolved(
        val value: String,
        val origin: Origin,
        val confidence: Float,
        val userLocked: Boolean
    )

    /** Pick the winning value, or null when every candidate is blank. */
    fun resolve(candidates: List<Candidate>): Resolved? {
        val usable = candidates.filter { !it.value.isNullOrBlank() }
        if (usable.isEmpty()) return null
        val winner = usable.maxWithOrNull(
            compareBy<Candidate> { it.userLocked }
                .thenBy { it.origin.priority }
                .thenBy { it.confidence }
        ) ?: return null
        return Resolved(
            value = winner.value!!.trim(),
            origin = winner.origin,
            confidence = winner.confidence,
            userLocked = winner.userLocked
        )
    }

    /**
     * Merge rule for one field: the stored value survives unless the
     * resolution produced a different value from an origin allowed to
     * replace it. A locked stored field is never replaced.
     */
    fun shouldReplace(stored: Candidate?, resolved: Resolved?): Boolean {
        if (resolved == null) return false
        if (stored?.userLocked == true) return false
        if (stored?.value.isNullOrBlank()) return true
        return resolved.value != stored.value &&
            resolved.origin.priority >= (stored?.origin?.priority ?: 0)
    }
}
