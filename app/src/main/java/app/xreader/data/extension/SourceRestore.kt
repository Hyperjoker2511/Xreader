package app.xreader.data.extension

/**
 * v1.3.2 (item 84): the one-time repair for sources that
 * v1.3.1's failure breaker switched off without the user.
 *
 * The audit conclusion: the breaker was the ONLY automatic
 * off-path in the app, and v1.3.1 stored no reason beside an
 * off state — so an off source on an upgraded install cannot be
 * PROVEN breaker-off rather than deliberately-off. Nothing is
 * therefore restored silently: the app shows one plain-words
 * card listing every off source, and only the user's own tap on
 * "Turn them all back on" changes any state. A deliberate off
 * is never overridden by the app itself — the card is an offer,
 * and "Leave them off" is a complete answer.
 *
 * From v1.3.2 the breaker itself is gone (see ExtensionService):
 * a source is off only because its reader turned it off.
 */
object SourceRestore {

    data class Row(val id: Long, val name: String, val enabled: Boolean)

    /** Every currently-off row — the card's list, nothing more. */
    fun offRows(rows: List<Row>): List<Row> = rows.filter { !it.enabled }

    /** The card shows once: not yet answered, and at least one
     * source is off. */
    fun shouldShowCard(cardDone: Boolean, rows: List<Row>): Boolean =
        !cardDone && offRows(rows).isNotEmpty()
}
