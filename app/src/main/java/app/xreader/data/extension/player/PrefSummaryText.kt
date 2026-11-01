package app.xreader.data.extension.player

/**
 * K5 (Keiyoushi player): the pure half of preference summaries.
 *
 * An androidx preference's summary reaches the screen three
 * ways: a SummaryProvider (the host asks it directly — that
 * call needs the live preference), a raw string, or a raw
 * string with a "%s" placeholder the framework fills with the
 * preference's current value (the ListPreference /
 * EditTextPreference convention). Only the string cases live
 * here, free of Android types, so they are unit-testable on
 * the JVM.
 */
object PrefSummaryText {

    /**
     * The summary to show for a preference whose raw summary
     * is [raw] and whose current value reads [currentValue]
     * (a ListPreference's entry, an EditTextPreference's
     * text). A null raw summary stays null — the framework
     * shows nothing, and inventing one would misstate the
     * source's own screen.
     */
    fun display(raw: String?, currentValue: String?): String? {
        raw ?: return null
        if (!raw.contains("%s")) return raw
        return raw.replace("%s", currentValue ?: "")
    }
}
