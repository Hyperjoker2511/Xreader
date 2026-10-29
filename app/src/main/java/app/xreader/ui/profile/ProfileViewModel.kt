package app.xreader.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.data.db.DailyActivity
import app.xreader.data.db.Profile
import app.xreader.data.game.ExpService
import app.xreader.di.AppContainer
import app.xreader.domain.game.AchievementDef
import app.xreader.domain.game.Achievements
import app.xreader.domain.game.ExpCurve
import app.xreader.domain.game.GameStats
import app.xreader.domain.game.Streaks
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AchievementUi(
    val def: AchievementDef,
    val progress: Int,
    val unlocked: Boolean,
    val unlockedAt: Long?,
    val isNew: Boolean
)

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: Profile? = null,
    val progress: ExpCurve.Progress = ExpCurve.progress(0),
    val expToNext: Long = 0,
    val title: String = ExpCurve.TITLES.first(),
    val stats: GameStats = GameStats(),
    val heatmap: List<List<Streaks.HeatDay>> = emptyList(),
    val todayMinutes: Int = 0,
    val todayGoalMet: Boolean = false,
    val achievements: List<AchievementUi> = emptyList(),
    val unlockedCount: Int = 0,
    /** Current pace + how it moved vs the previous week. */
    val wpmNow: Int = 0,
    val wpmDelta: Int = 0,
    /** Set once per goal day: the screen's particle burst plays. */
    /** v1.0.1: the chosen avatar disc colour name ("" = scheme). */
    val avatarColour: String = "",
    val celebrateGoal: Boolean = false,
    val message: String? = null
)

/**
 * Profile & progress (screen #27, PLAN §8). Opening this screen is
 * one of the EXP engine's settle triggers: whatever reading has
 * happened since the last settle is paid out, levelled, and —
 * if the daily goal just landed — celebrated, exactly once per day.
 */
class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state

    private val zone: ZoneId = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            val result = runCatching { container.expService.settle() }.getOrNull()
            refresh(newAchievementIds = result?.newAchievements?.toSet() ?: emptySet())
            // Goal celebration: once per goal day, only when this
            // visit's settle (or an earlier one today) met the goal.
            val today = LocalDate.now(zone).toString()
            val celebrated = container.gamePrefs.lastGoalCelebration.first()
            val todayRow = container.database.dailyActivityDao().get(today)
            if (todayRow?.goalMet == true && celebrated != today) {
                _state.value = _state.value.copy(celebrateGoal = true)
                container.gamePrefs.setGoalCelebrated(today)
            }
            if (result != null && result.newAchievements.isNotEmpty()) {
                val names = result.newAchievements.mapNotNull {
                    Achievements.byId(it)?.title
                }
                if (names.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        message = "New achievement: ${names.joinToString(", ")}"
                    )
                }
            }
        }
    }

    private suspend fun refresh(newAchievementIds: Set<String> = emptySet()) {
        val expService: ExpService = container.expService
        val profile = expService.ensureProfile()
        val stats = expService.stats()
        val days = container.database.dailyActivityDao().all()
        val unlocks = container.database.achievementDao().all()
            .associate { it.id to it.unlockedAt }
        val today = LocalDate.now(zone)
        val todayRow = days.firstOrNull { it.date == today.toString() }
        val wpmTrend = wpmTrend()
        _state.value = ProfileUiState(
            loading = false,
            profile = profile,
            progress = ExpCurve.progress(profile.totalExp),
            expToNext = ExpCurve.expToNextLevel(profile.totalExp),
            title = ExpCurve.titleFor(profile.level),
            stats = stats,
            heatmap = Streaks.heatmap(
                minutesByDate = days.mapNotNull { day ->
                    runCatching { LocalDate.parse(day.date) }.getOrNull()
                        ?.let { date -> date to day.minutesRead }
                }.toMap(),
                goalMetDates = days.filter { it.goalMet }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet(),
                today = today,
                goalMinutes = profile.dailyGoalMinutes
            ),
            todayMinutes = todayRow?.minutesRead ?: 0,
            todayGoalMet = todayRow?.goalMet == true,
            achievements = Achievements.ALL.map { def ->
                AchievementUi(
                    def = def,
                    progress = def.progress(stats),
                    unlocked = def.progress(stats) >= def.target,
                    unlockedAt = unlocks[def.id],
                    isNew = def.id in newAchievementIds
                )
            },
            unlockedCount = Achievements.ALL.count {
                it.progress(stats) >= it.target
            },
            avatarColour = container.gamePrefs.avatarColour.first(),
            wpmNow = wpmTrend.first,
            wpmDelta = wpmTrend.second
        )
    }

    /** (current wpm, delta vs the previous 7 days) from sessions. */
    private suspend fun wpmTrend(): Pair<Int, Int> {
        val sessions = container.database.readingSessionDao().recent(200)
            .filter { it.activeMinutes > 0 && it.wordsRead > 0 }
        val now = System.currentTimeMillis()
        val weekMs = 7L * 24 * 3600 * 1000
        fun wpmOf(list: List<app.xreader.data.db.ReadingSession>): Int {
            val words = list.sumOf { it.wordsRead }
            val minutes = list.sumOf { it.activeMinutes }
            if (minutes <= 0) return 0
            return (words / minutes).toInt()
        }
        val recent = sessions.filter {
            (it.endedAt ?: it.startedAt) >= now - weekMs
        }
        val previous = sessions.filter {
            val at = it.endedAt ?: it.startedAt
            at in (now - 2 * weekMs) until (now - weekMs)
        }
        val current = if (recent.isNotEmpty()) {
            wpmOf(recent)
        } else {
            container.wpmEstimator.wpm().toInt()
        }
        val delta = if (previous.isNotEmpty() && recent.isNotEmpty()) {
            current - wpmOf(previous)
        } else {
            0
        }
        return current to delta
    }

    fun setDisplayName(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val profile = container.expService.ensureProfile()
            container.database.profileDao().upsert(profile.copy(displayName = trimmed))
            refresh()
        }
    }

    fun setDailyGoal(minutes: Int) {
        viewModelScope.launch {
            val profile = container.expService.ensureProfile()
            container.database.profileDao().upsert(
                profile.copy(dailyGoalMinutes = minutes.coerceIn(5, 600))
            )
            // A lowered goal may already be met today — settle so
            // the EXP + ring agree with the new target immediately.
            runCatching { container.expService.settle() }
            refresh()
        }
    }

    /** v1.0.1: pick the avatar disc colour (used when no photo). */
    fun setAvatarColour(name: String) {
        viewModelScope.launch {
            container.gamePrefs.setAvatarColour(name)
            refresh()
        }
    }

    /** Copies the picked image into `avatars/` and points the
     * profile at it. The stored path is relative (storage rule). */
    fun setAvatar(openStream: () -> InputStream?, extensionHint: String) {
        viewModelScope.launch {
            val storage = container.bookStorage
            val ext = extensionHint.lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            val relative = "avatars/avatar-${System.currentTimeMillis()}.$ext"
            val target = storage.resolve(relative)
            val copied = runCatching {
                val input = openStream() ?: return@runCatching false
                target.parentFile?.mkdirs()
                input.use { source ->
                    target.outputStream().buffered().use { sink ->
                        source.copyTo(sink)
                    }
                }
                target.isFile && target.length() > 0
            }.getOrDefault(false)
            if (copied) {
                val profile = container.expService.ensureProfile()
                container.database.profileDao().upsert(
                    profile.copy(avatarPath = relative)
                )
                refresh()
            } else {
                _state.value = _state.value.copy(
                    message = "That picture couldn't be saved — try another one."
                )
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun onCelebrationShown() {
        _state.value = _state.value.copy(celebrateGoal = false)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProfileViewModel(container) as T
            }
    }
}
