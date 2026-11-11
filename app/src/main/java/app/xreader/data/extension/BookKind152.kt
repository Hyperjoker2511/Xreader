package app.xreader.data.extension

/**
 * v1.5.2 (§7): per-book kind. Stored states on the book row are the
 * strings "TEXT" / "MANGA" / NULL (= UNKNOWN — absence of evidence,
 * deliberately NOT a third MediaKind value).
 */
object BookKind152 {
    const val TEXT = "TEXT"
    const val MANGA = "MANGA"

    /** FenrirRealm's `type` field map. The text side is
     * live-evidenced 2026-11-02 (300 popular records). The
     * manga side shipped with exactly ONE value (`webtoon`)
     * from that sample; v1.5.8 (B5) widens it — stated plainly
     * as a best guess, NOT as the site's taxonomy (no type
     * enumeration is recorded anywhere in the tree): manga-side
     * = `webtoon`, `manhwa`, `comic`, `manga`. The real
     * mechanism behind the map is B2/B3 (chapter-shape evidence
     * re-files) plus the unmapped-value DiagnosticLog written
     * at the parse-miss branches, which makes the next unknown
     * value visible on its first occurrence instead of fatal.
     * Any other value → UNKNOWN. */
    val FENRIR_TYPE_MAP: Map<String, String> = mapOf(
        "novel" to TEXT,
        "web_novel" to TEXT,
        "light_novel" to TEXT,
        "original_novel" to TEXT,
        "webtoon" to MANGA,
        "manhwa" to MANGA,
        "comic" to MANGA,
        "manga" to MANGA
    )

    /** Tier-1 verdict for one listing value; null = UNKNOWN. */
    fun tier1(map: Map<String, String>?, rawValue: String?): String? {
        if (map == null || rawValue.isNullOrBlank()) return null
        return map[rawValue.trim().lowercase()]
    }

    enum class Verdict { TEXT, MANGA, UNKNOWN }

    fun verdictOf(storedKind: String?): Verdict = when (storedKind) {
        TEXT -> Verdict.TEXT
        MANGA -> Verdict.MANGA
        else -> Verdict.UNKNOWN
    }

    /** Library visual side for a verdict: UNKNOWN files VISUALLY under
     * Novels (presentation default, never a stored reclassification),
     * so no book ever falls off both sides. */
    fun visualSideIsManga(storedKind: String?): Boolean = storedKind == MANGA

    /**
     * Hysteresis (§7): a stored kind changes ONLY on tier-1 evidence,
     * or on the SECOND consecutive consistent tier-2 verdict for a
     * both-kinds source. Returns the new stored kind (or the old one
     * when no change is warranted) plus the updated pending tier-2
     * streak as (kind, streak).
     */
    fun applyTier2(
        storedKind: String?,
        bothKindsSource: Boolean,
        tier2Kind: String?,
        pendingKind: String?,
        pendingStreak: Int
    ): Pair<String?, Pair<String?, Int>> {
        if (!bothKindsSource || tier2Kind == null) {
            return storedKind to (pendingKind to pendingStreak)
        }
        if (storedKind == tier2Kind) return storedKind to (null to 0)
        val streak = if (pendingKind == tier2Kind) pendingStreak + 1 else 1
        return if (streak >= 2) {
            tier2Kind to (null to 0)
        } else {
            storedKind to (tier2Kind to streak)
        }
    }

    /** Tier-1 always overrides, immediately (one re-file trigger:
     * catalogue fetch/sync of the book's source). */
    fun applyTier1(storedKind: String?, tier1Kind: String?): String? =
        tier1Kind ?: storedKind
}

/**
 * v1.5.8 (B5): the unmapped-kind-value log. When a listing's
 * kind field carries a non-blank value the source's map does
 * not know, the parse files nothing (UNKNOWN) — and, instead
 * of silently returning null as before, records the value in
 * the DiagnosticLog ONCE per value per source, so the next
 * unknown value is visible on its first occurrence instead of
 * fatal. The parse-miss branches in DeclarativeSource call
 * [record] (Builder 2's B1 parse work applies those two call
 * sites); the once-per-value-per-source discipline lives here
 * so it is unit-testable at this seam.
 */
object UnmappedKindLog {

    private val seen =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Record [rawValue] for [sourceKey] unless it is blank or
     * this (source, value) pair was already recorded. */
    fun record(sourceKey: String, rawValue: String?) {
        val value = rawValue?.trim()?.lowercase()
            ?.ifBlank { null } ?: return
        if (!seen.add("$sourceKey|$value")) return
        DiagnosticLog.record(
            "kind",
            "unmapped type value '$value' — " +
                "filed as unknown; chapter evidence decides",
            sourceKey
        )
    }

    /** Test seam: the once-set is process-wide by design. */
    fun resetForTest() {
        seen.clear()
    }
}
