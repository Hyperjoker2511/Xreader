package app.xreader.data.extension

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The aggregator cleaner engine (PLAN §5/§9.4): user + source regex
 * rules applied to fetched chapter text ("remove 'Please support the
 * translator at…' footers", watermark strips, name fixes).
 *
 * ReDoS safety is structural, per the plan: every rule runs on a
 * worker thread from a daemon pool with a HARD per-rule timeout and
 * bounded input; a rule that times out (or fails to compile, or
 * throws) is skipped for this run, DISABLED in the result, and
 * reported by name — the clean as a whole never hangs on a bad rule.
 * (A timed-out regex thread can't always be interrupted mid-match —
 * java.util.regex doesn't check interrupts — so timed-out workers are
 * daemon threads: they can burn a background core until their bounded
 * input finishes, but they can never block the app or the download.)
 */
object CleanerEngine {

    const val DEFAULT_TIMEOUT_MS = 1_000L
    const val MAX_INPUT_CHARS = 2_000_000
    const val MAX_PATTERN_CHARS = 512

    data class TimedRule(
        val rule: CleanerRuleData,
        /** "source" | "extension" | "book" | "global". */
        val scope: String
    )

    data class Outcome(
        val text: String,
        /** Rules disabled by this run (timeout/compile error) — the
         * caller persists the disable so the rule stops hurting. */
        val disabledRules: List<TimedRule>,
        /** Plain-words notes for the rule-test preview. */
        val notes: List<String>
    )

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "xreader-cleaner").apply { isDaemon = true }
    }

    /**
     * Clean [text] with [rules] in order. Rule order is the caller's
     * (config → extension → book → global in the fetch path).
     */
    fun clean(
        text: String,
        rules: List<TimedRule>,
        timeoutMsPerRule: Long = DEFAULT_TIMEOUT_MS
    ): Outcome {
        val bounded = if (text.length > MAX_INPUT_CHARS) {
            text.substring(0, MAX_INPUT_CHARS)
        } else {
            text
        }
        var current = bounded
        val disabled = mutableListOf<TimedRule>()
        val notes = mutableListOf<String>()
        for (timed in rules) {
            val rule = timed.rule
            if (!rule.enabled) continue
            if (rule.pattern.length > MAX_PATTERN_CHARS) {
                disabled += timed
                notes += "A ${timed.scope} rule was switched off: its pattern is too long."
                continue
            }
            val regex = try {
                if (rule.ignoreCase) {
                    Regex(rule.pattern, RegexOption.IGNORE_CASE)
                } else {
                    Regex(rule.pattern)
                }
            } catch (e: Exception) {
                disabled += timed
                notes += "A ${timed.scope} rule was switched off: the pattern isn't a valid search pattern."
                continue
            }
            val future: Future<String> = pool.submit(
                Callable { regex.replace(current, rule.replacement) }
            )
            try {
                current = future.get(timeoutMsPerRule, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                disabled += timed
                notes += "A ${timed.scope} rule was switched off: it took too long " +
                    "(it may be a runaway pattern)."
            } catch (e: Exception) {
                future.cancel(true)
                disabled += timed
                notes += "A ${timed.scope} rule was switched off: it failed while running."
            }
        }
        return Outcome(current, disabled, notes)
    }

    /** Apply rules to a whole chapter: text blocks are cleaned one by
     * one. Formatting spans survive untouched blocks; a block a rule
     * actually changed is rebuilt as plain text (a cleaner that edits
     * text can't preserve mid-word bold splits — an honest trade). */
    fun cleanContent(
        content: ChapterContent,
        rules: List<TimedRule>,
        timeoutMsPerRule: Long = DEFAULT_TIMEOUT_MS
    ): Pair<ChapterContent, Outcome> {
        if (rules.none { it.rule.enabled }) {
            return content to Outcome("", emptyList(), emptyList())
        }
        val allDisabled = mutableListOf<TimedRule>()
        val allNotes = mutableListOf<String>()
        var activeRules = rules
        val blocks = content.blocks.map { block ->
            when (block) {
                is ChapterBlock.Paragraph -> {
                    val outcome = clean(block.text, activeRules, timeoutMsPerRule)
                    allDisabled += outcome.disabledRules
                    allNotes += outcome.notes
                    if (outcome.disabledRules.isNotEmpty()) {
                        activeRules = activeRules - outcome.disabledRules.toSet()
                    }
                    if (outcome.text == block.text) block
                    else ChapterBlock.Paragraph.plain(outcome.text, block.id)
                }
                is ChapterBlock.Heading -> {
                    val outcome = clean(block.text, activeRules, timeoutMsPerRule)
                    allDisabled += outcome.disabledRules
                    allNotes += outcome.notes
                    if (outcome.disabledRules.isNotEmpty()) {
                        activeRules = activeRules - outcome.disabledRules.toSet()
                    }
                    if (outcome.text == block.text) block
                    else block.copy(text = outcome.text)
                }
                else -> block
            }
        }
        return ChapterContent(blocks, content.mediaOverlay) to
            Outcome("", allDisabled.distinct(), allNotes.distinct())
    }
}

/** Where cleaner rules live and how the scopes compose. */
class CleanerRuleStore(private val store: ExtensionStore) {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val listSerializer =
        kotlinx.serialization.builtins.ListSerializer(CleanerRuleData.serializer())

    fun globalRules(): List<CleanerRuleData> = read("global")
    fun extensionRules(packageId: String): List<CleanerRuleData> =
        read("ext-${packageId.sanitise()}")

    fun bookRules(bookId: Long): List<CleanerRuleData> = read("book-$bookId")

    fun saveGlobal(rules: List<CleanerRuleData>) = write("global", rules)
    fun saveExtensionRules(packageId: String, rules: List<CleanerRuleData>) =
        write("ext-${packageId.sanitise()}", rules)

    fun saveBookRules(bookId: Long, rules: List<CleanerRuleData>) =
        write("book-$bookId", rules)

    /** The full rule stack for one chapter fetch, in application
     * order: the source's own rules first, then the user's per-source,
     * per-book, and global rules. */
    fun stackFor(
        configRules: List<CleanerRuleData>,
        packageId: String?,
        bookId: Long?
    ): List<CleanerEngine.TimedRule> {
        val stack = mutableListOf<CleanerEngine.TimedRule>()
        configRules.forEach { stack += CleanerEngine.TimedRule(it, "source") }
        if (packageId != null) {
            extensionRules(packageId).forEach {
                stack += CleanerEngine.TimedRule(it, "extension")
            }
        }
        if (bookId != null) {
            bookRules(bookId).forEach { stack += CleanerEngine.TimedRule(it, "book") }
        }
        globalRules().forEach { stack += CleanerEngine.TimedRule(it, "global") }
        return stack
    }

    /** Persist "disabled" for rules the engine killed (in the user
     * scopes — source-shipped rules live in the config and are
     * reported, not rewritten). */
    fun applyDisables(disabled: List<CleanerEngine.TimedRule>, packageId: String?, bookId: Long?) {
        fun disableIn(
            rules: List<CleanerRuleData>,
            killed: List<CleanerRuleData>
        ): List<CleanerRuleData> = rules.map { rule ->
            if (killed.any { it.pattern == rule.pattern && it.replacement == rule.replacement }) {
                rule.copy(enabled = false)
            } else {
                rule
            }
        }
        val byScope = disabled.groupBy({ it.scope }, { it.rule })
        byScope["global"]?.let { saveGlobal(disableIn(globalRules(), it)) }
        if (packageId != null) {
            byScope["extension"]?.let {
                saveExtensionRules(packageId, disableIn(extensionRules(packageId), it))
            }
        }
        if (bookId != null) {
            byScope["book"]?.let { saveBookRules(bookId, disableIn(bookRules(bookId), it)) }
        }
    }

    private fun read(scope: String): List<CleanerRuleData> {
        val raw = store.readText(store.rulesFile(scope)) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }

    private fun write(scope: String, rules: List<CleanerRuleData>) {
        store.writeText(store.rulesFile(scope), json.encodeToString(listSerializer, rules))
    }

    private fun String.sanitise(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "pkg" }
}
