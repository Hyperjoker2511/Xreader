package app.xreader.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.xreader.MainActivity
import app.xreader.XreaderApp
import app.xreader.domain.game.Streaks
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * The home-screen widget (PLAN §8, assumption A10): the book in
 * progress, its bar, the streak flame and the level — one glance,
 * tap opens the book in the reader. Data is read fresh on every
 * Glance update from the same Room rows the app uses; the shell
 * asks for an update after each EXP settle (app open / reader
 * close), and the system refreshes on its own cadence besides.
 */
class XreaderWidget : GlanceAppWidget() {

    data class WidgetState(
        val bookTitle: String = "",
        val bookId: Long = -1,
        val fraction: Float = 0f,
        val streak: Int = 0,
        val level: Int = 1
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent { WidgetContent(state, context) }
    }

    private suspend fun loadState(context: Context): WidgetState {
        val container = (context.applicationContext as? XreaderApp)?.container
            ?: return WidgetState()
        return runCatching {
            val progress = container.database.readingProgressDao()
                .observeRecent().first().firstOrNull()
            val book = progress?.let {
                container.database.bookDao().getById(it.bookId)
            }
            val profile = container.database.profileDao().get()
            val days = container.database.dailyActivityDao().all()
            val activeDates = days.filter { it.minutesRead > 0 }
                .mapNotNull {
                    runCatching { LocalDate.parse(it.date) }.getOrNull()
                }.toSet()
            WidgetState(
                bookTitle = book?.title ?: "",
                bookId = book?.id ?: -1,
                fraction = progress?.fraction ?: 0f,
                streak = Streaks.currentStreak(activeDates, LocalDate.now()),
                level = profile?.level ?: 1
            )
        }.getOrDefault(WidgetState())
    }

    @Composable
    private fun WidgetContent(state: WidgetState, context: Context) {
        // Glance delivers action parameters as intent extras under
        // their key names — MainActivity reads EXTRA_OPEN_BOOK.
        val openAction = androidx.glance.action.actionStartActivity(
            android.content.ComponentName(context, MainActivity::class.java),
            parameters = androidx.glance.action.actionParametersOf(
                BookIdKey to state.bookId
            )
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(16.dp)
                .clickable(openAction),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "XREADER — CONTINUE READING",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = state.bookTitle.ifBlank { "No book in progress" },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2
            )
            Spacer(GlanceModifier.height(8.dp))
            if (state.bookId > 0) {
                LinearProgressIndicator(
                    progress = state.fraction.coerceIn(0f, 1f),
                    modifier = GlanceModifier.height(6.dp)
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = "${(state.fraction * 100).toInt()}% read",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = "Level ${state.level} · 🔥 ${state.streak} day streak",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK = "app.xreader.OPEN_BOOK"

        /** The action-parameter key carrying the book id (delivered
         * to MainActivity as an intent extra of the same name). */
        val BookIdKey =
            androidx.glance.action.ActionParameters.Key<Long>(EXTRA_OPEN_BOOK)
    }
}

class XreaderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = XreaderWidget()
}
