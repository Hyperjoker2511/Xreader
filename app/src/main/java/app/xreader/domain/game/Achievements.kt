package app.xreader.domain.game

/**
 * Everything an achievement can look at — one flat snapshot built by
 * [app.xreader.data.game.ExpService] from the session/activity
 * records, so unlock rules stay pure and testable (PLAN §8).
 */
data class GameStats(
    val booksTotal: Int = 0,
    val booksFinished: Int = 0,
    val chaptersFinished: Int = 0,
    val totalActiveMinutes: Int = 0,
    val totalWordsRead: Long = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val focusSessions: Int = 0,
    val level: Int = 1,
    val sourcesInstalled: Int = 0,
    val loreChatUsed: Boolean = false,
    val goalDaysMet: Int = 0
)

/**
 * One achievement. [progress] is how far the reader is toward
 * [target]; unlocked means progress >= target. Progress is always
 * derived from records — never stored — so an unlock can be
 * re-derived identically after a sync or a restore.
 */
data class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val target: Int,
    val progress: (GameStats) -> Int
)

object Achievements {

    val ALL: List<AchievementDef> = listOf(
        AchievementDef(
            "first-book", "First Steps",
            "Add your first book to the library", 1
        ) { it.booksTotal },
        AchievementDef(
            "library-10", "A Growing Shelf",
            "Keep 10 books in the library", 10
        ) { it.booksTotal },
        AchievementDef(
            "first-finish", "The End (For Now)",
            "Finish your first book", 1
        ) { it.booksFinished },
        AchievementDef(
            "chapters-10", "Warming Up",
            "Finish 10 chapters", 10
        ) { it.chaptersFinished },
        AchievementDef(
            "chapters-100", "Chapter Century",
            "Finish 100 chapters", 100
        ) { it.chaptersFinished },
        AchievementDef(
            "chapters-1000", "Thousand-Chapter Reader",
            "Finish 1,000 chapters", 1000
        ) { it.chaptersFinished },
        AchievementDef(
            "streak-7", "A Week of Reading",
            "Read 7 days in a row", 7
        ) { it.bestStreak },
        AchievementDef(
            "streak-30", "Unbroken Month",
            "Read 30 days in a row", 30
        ) { it.bestStreak },
        AchievementDef(
            "focus-first", "In the Zone",
            "Finish a focus session without a pause", 1
        ) { it.focusSessions },
        AchievementDef(
            "focus-10", "Deep Focus",
            "Finish 10 uninterrupted focus sessions", 10
        ) { it.focusSessions },
        AchievementDef(
            "level-5", "Rising Reader",
            "Reach level 5", 5
        ) { it.level },
        AchievementDef(
            "level-10", "Committed",
            "Reach level 10", 10
        ) { it.level },
        AchievementDef(
            "level-25", "Story Sage",
            "Reach level 25", 25
        ) { it.level },
        AchievementDef(
            "level-50", "Halfway to Legend",
            "Reach level 50", 50
        ) { it.level },
        AchievementDef(
            "first-source", "New Worlds",
            "Add your first source", 1
        ) { it.sourcesInstalled },
        AchievementDef(
            "first-lore-chat", "Ask the Lore",
            "Have your first chat with Lore Chat", 1
        ) { if (it.loreChatUsed) 1 else 0 },
        AchievementDef(
            "marathon-600", "Marathon Reader",
            "Read for 10 hours in total", 600
        ) { it.totalActiveMinutes }
    )

    fun byId(id: String): AchievementDef? = ALL.firstOrNull { it.id == id }

    /** id → unlocked, for every defined achievement. */
    fun evaluate(stats: GameStats): Map<String, Boolean> =
        ALL.associate { def -> def.id to (def.progress(stats) >= def.target) }

    /** The ids [stats] has earned — the unlock pass diffs this
     * against the stored unlock rows to find NEW achievements. */
    fun unlockedIds(stats: GameStats): Set<String> =
        ALL.filter { it.progress(stats) >= it.target }.map { it.id }.toSet()
}
