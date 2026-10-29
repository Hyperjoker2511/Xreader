package app.xreader.data.library

/**
 * The canonical tag registry (v1.0.2, R22): sources say "Male MC",
 * "OP MC", "Male Lead" — the user sees one canonical name, and a
 * tag page gathers every spelling. Genres, MC types, themes and
 * relationships are separate families; the author is never a tag.
 *
 * Pure data + matching, unit-tested. The registry seeds the `tags`
 * table on first use; user-created personal tags join with
 * family "Yours".
 */
data class CanonicalTag(
    val slug: String,
    val displayName: String,
    val family: String,
    val aliases: List<String> = emptyList()
)

object TagRegistry {

    fun slugify(label: String): String =
        label.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    val SEED: List<CanonicalTag> = listOf(
        // Genres
        CanonicalTag("action", "Action", "Genre"),
        CanonicalTag("adventure", "Adventure", "Genre"),
        CanonicalTag("comedy", "Comedy", "Genre"),
        CanonicalTag("drama", "Drama", "Genre"),
        CanonicalTag("fantasy", "Fantasy", "Genre"),
        CanonicalTag("historical", "Historical", "Genre"),
        CanonicalTag("horror", "Horror", "Genre"),
        CanonicalTag("martial-arts", "Martial Arts", "Genre", listOf("Martial Art")),
        CanonicalTag("mature", "Mature", "Genre"),
        CanonicalTag("modern-day", "Modern Day", "Genre", listOf("Contemporary", "Urban")),
        CanonicalTag("mystery", "Mystery", "Genre"),
        CanonicalTag("romance", "Romance", "Genre"),
        CanonicalTag("sci-fi", "Sci-Fi", "Genre", listOf("Science Fiction", "Sci Fi")),
        CanonicalTag("wuxia", "Wuxia", "Genre"),
        CanonicalTag("xianxia", "Xianxia", "Genre"),
        CanonicalTag("xuanhuan", "Xuanhuan", "Genre"),
        // Story shapes
        CanonicalTag("cultivation", "Cultivation", "Story", listOf("Cultivator")),
        CanonicalTag("game-elements", "Game Elements", "Story", listOf("LitRPG", "Game System", "System", "Virtual Reality")),
        CanonicalTag("kingdom-building", "Kingdom Building", "Story", listOf("Territory Building", "Base Building")),
        CanonicalTag("reincarnation", "Reincarnation", "Story", listOf("Reborn", "Second Chance", "Reincarnated")),
        CanonicalTag("transmigration", "Transmigration", "Story", listOf("Transmigrated", "Transported to Another World", "Isekai")),
        CanonicalTag("time-travel", "Time Travel", "Story", listOf("Time Loop", "Regression", "Regressor")),
        CanonicalTag("apocalypse", "Apocalypse", "Story", listOf("Post-Apocalyptic", "Doomsday")),
        CanonicalTag("dungeon", "Dungeon", "Story", listOf("Tower Climbing", "Hunter")),
        // MC types
        CanonicalTag("male-protagonist", "Male MC", "MC type", listOf("Male Lead", "Male Protagonist")),
        CanonicalTag("female-protagonist", "Female MC", "MC type", listOf("Female Lead", "Female Protagonist")),
        CanonicalTag("overpowered-mc", "Overpowered MC", "MC type", listOf("OP MC", "Overpowered Protagonist", "Invincible MC")),
        CanonicalTag("anti-hero", "Anti-Hero", "MC type", listOf("Antihero", "Morally Grey MC")),
        CanonicalTag("smart-mc", "Smart MC", "MC type", listOf("Strategic MC", "Cunning MC", "Scheming MC", "Intelligent MC")),
        CanonicalTag("weak-to-strong", "Weak to Strong", "MC type", listOf("Weak Protagonist", "Underdog MC")),
        CanonicalTag("non-human-mc", "Non-Human MC", "MC type", listOf("Monster MC", "Beast MC")),
        CanonicalTag("ruthless-mc", "Ruthless MC", "MC type", listOf("Cold MC", "Merciless MC")),
        // Themes
        CanonicalTag("revenge", "Revenge", "Theme"),
        CanonicalTag("face-slapping", "Face-Slapping", "Theme", listOf("Faceslapping", "Show-Off")),
        CanonicalTag("slow-burn", "Slow Burn", "Theme"),
        CanonicalTag("found-family", "Found Family", "Theme"),
        CanonicalTag("teacher-disciple", "Teacher & Disciple", "Theme", listOf("Master-Disciple", "Mentor")),
        CanonicalTag("showbiz", "Showbiz", "Theme", listOf("Entertainment Industry", "Celebrity"))
    )

    private val bySlug: Map<String, CanonicalTag> = SEED.associateBy { it.slug }

    private val aliasToSlug: Map<String, String> = buildMap {
        for (tag in SEED) {
            put(slugify(tag.displayName), tag.slug)
            for (alias in tag.aliases) put(slugify(alias), tag.slug)
        }
    }

    /** Resolve any source label to its canonical tag, or null when
     * the registry doesn't know it (the label is then kept as its
     * own tag — evidence is never thrown away). */
    fun normalise(label: String): CanonicalTag? =
        aliasToSlug[slugify(label)]?.let { bySlug[it] }

    fun canonical(slug: String): CanonicalTag? = bySlug[slug]

    /** The slug a label maps to: canonical when known, else its own
     * slugified form (a new tag the registry learns by use). */
    fun slugFor(label: String): String = normalise(label)?.slug ?: slugify(label)

    fun displayFor(slug: String): String =
        bySlug[slug]?.displayName ?: slug.replace('-', ' ')
            .replaceFirstChar { it.uppercase() }

    /**
     * Synopsis extraction (R22): cautious keyword rules produce
     * INFERRED tags only — they are labelled as guesses in the UI
     * and the user can remove them per book. A rule fires on a
     * whole-word/phrase hit, never a substring of a longer word.
     */
    private val SYNOPSIS_RULES: List<Pair<String, List<String>>> = listOf(
        "reincarnation" to listOf("reincarnated", "reborn into", "second life", "reincarnation"),
        "transmigration" to listOf("transmigrated", "transported to another world", "woke up in another world", "isekai"),
        "time-travel" to listOf("returned to the past", "back in time", "regressed", "time loop"),
        "game-elements" to listOf("a system appeared", "the system", "game-like", "level up", "status window"),
        "cultivation" to listOf("cultivation", "cultivator", "qi", "immortal path"),
        "revenge" to listOf("revenge", "avenge", "payback"),
        "weak-to-strong" to listOf("once a trash", "known as trash", "weakest", "from zero"),
        "overpowered-mc" to listOf("invincible", "strongest", "overwhelming power", "unmatched"),
        "kingdom-building" to listOf("build his kingdom", "build her kingdom", "build a kingdom", "territory"),
        "apocalypse" to listOf("apocalypse", "the world ended", "zombies", "doomsday"),
        "male-protagonist" to listOf("he was", "his journey", "young man"),
        "female-protagonist" to listOf("she was", "her journey", "young woman")
    )

    fun extractFromSynopsis(synopsis: String): List<String> {
        if (synopsis.isBlank()) return emptyList()
        // Punctuation becomes spaces and the text is space-padded,
        // so every phrase check is a whole-word/phrase check.
        val text = " " + synopsis.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ") + " "
        return SYNOPSIS_RULES.mapNotNull { (slug, phrases) ->
            if (phrases.any { text.contains(" $it ") }) slug else null
        }
    }
}
