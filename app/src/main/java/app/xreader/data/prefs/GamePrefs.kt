package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 6 game facts that are EVENTS, not records: things the
 * session/activity tables can't prove after the fact (a Lore Chat
 * conversation happened; the goal celebration was already shown for
 * a date). One owner, validated reads, safe defaults — the standing
 * DataStore discipline.
 */
class GamePrefs(private val store: DataStore<Preferences>) {

    val loreChatUsed: Flow<Boolean> = store.data.map { it[KEY_LORE_CHAT] ?: false }

    /** The local date (ISO) whose goal-completion burst was last
     * shown — the burst fires once per goal day, not per visit. */
    val lastGoalCelebration: Flow<String> =
        store.data.map { it[KEY_GOAL_CELEBRATION] ?: "" }

    /** v1.0.1 Me screen: the avatar disc colour name ("" = scheme). */
    val avatarColour: Flow<String> =
        store.data.map { it[KEY_AVATAR_COLOUR] ?: "" }

    suspend fun setAvatarColour(name: String) {
        store.edit { it[KEY_AVATAR_COLOUR] = name }
    }

    suspend fun setLoreChatUsed() {
        store.edit { it[KEY_LORE_CHAT] = true }
    }

    suspend fun setGoalCelebrated(dateIso: String) {
        store.edit { it[KEY_GOAL_CELEBRATION] = dateIso }
    }

    /** v1.0.2 (R28): the newest version code whose "What's new"
     * note the user has dismissed on Me. The note shows while the
     * installed version is newer than this. */
    val whatsNewSeenCode: Flow<Int> =
        store.data.map { it[KEY_WHATS_NEW_SEEN] ?: 0 }

    suspend fun setWhatsNewSeen(code: Int) {
        store.edit { it[KEY_WHATS_NEW_SEEN] = code }
    }

    private companion object {
        val KEY_LORE_CHAT = booleanPreferencesKey("game.lore_chat_used")
        val KEY_GOAL_CELEBRATION = stringPreferencesKey("game.goal_celebration")
        val KEY_AVATAR_COLOUR = stringPreferencesKey("game.avatar_colour")
        val KEY_WHATS_NEW_SEEN =
            androidx.datastore.preferences.core.intPreferencesKey(
                "game.whats_new_seen_code"
            )
    }
}
