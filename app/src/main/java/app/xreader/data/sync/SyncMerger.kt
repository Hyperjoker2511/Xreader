package app.xreader.data.sync

/**
 * Payload-vs-payload merge (PLAN §7) — a pure function, so two-device
 * behaviour is pinned by tests, not by hope.
 *
 * Rules:
 *  - per-record last-write-wins on `updatedAt` (ties break toward the
 *    record whose deviceId sorts later — deterministic on both sides);
 *  - progress merges by POSITION, not just time: the further-along
 *    reading position wins, so sync can never silently move a reader
 *    backwards; when the further position is also the older write,
 *    the conflict is logged (the plan's "never backwards, and say so");
 *  - tombstones delete: a record whose tombstone is at least as new
 *    as the record does not survive the merge;
 *  - gamification merges monotonically (EXP/level take the max —
 *    progress on either device is real);
 *  - lore: user-locked local edits win over remote re-extraction.
 */
object SyncMerger {

    data class Result(
        val payload: SyncPayload,
        val conflicts: List<String>
    )

    fun merge(local: SyncPayload, remote: SyncPayload, now: Long): Result {
        val conflicts = mutableListOf<String>()

        val books = mergeBy(local.books, remote.books, key = { it.syncKey }) { a, b ->
            if (a.updatedAt >= b.updatedAt) a else b
        }

        val progress = mergeProgress(local, remote, conflicts)

        var bookmarks = mergeBy(local.bookmarks, remote.bookmarks, key = { it.naturalKey }) { a, b ->
            pickNewer(a, b, local.deviceId, remote.deviceId)
        }
        var highlights = mergeBy(local.highlights, remote.highlights, key = { it.naturalKey }) { a, b ->
            pickNewer(a, b, local.deviceId, remote.deviceId)
        }
        var lore = mergeBy(local.lore, remote.lore, key = { it.naturalKey }) { a, b ->
            when {
                a.userLocked && !b.userLocked -> a
                b.userLocked && !a.userLocked -> b
                else -> pickNewer(a, b, local.deviceId, remote.deviceId)
            }
        }
        var terms = mergeBy(local.terminology, remote.terminology, key = { it.naturalKey }) { a, b ->
            when {
                a.locked && !b.locked -> a
                b.locked && !a.locked -> b
                else -> b // no timestamps: the incoming side wins ties
            }
        }

        // Tombstones: union (newest per key), then apply.
        val tombstones = (local.tombstones + remote.tombstones)
            .groupBy { it.kind to it.key }
            .values
            .map { group -> group.maxByOrNull { it.deletedAt }!! }
        fun tombstoned(kind: String, key: String, updatedAt: Long): Boolean =
            tombstones.any { it.kind == kind && it.key == key && it.deletedAt >= updatedAt }
        bookmarks = bookmarks.filterNot {
            tombstoned("bookmark", it.naturalKey, it.updatedAt)
        }
        highlights = highlights.filterNot {
            tombstoned("highlight", it.naturalKey, it.updatedAt)
        }
        lore = lore.filterNot { tombstoned("lore", it.naturalKey, it.updatedAt) }
        terms = terms.filterNot { tombstoned("term", it.naturalKey, Long.MIN_VALUE) }

        val relations = (local.loreRelations.associateBy { it.naturalKey } +
            remote.loreRelations.associateBy { it.naturalKey }).values.toList()

        // ── v1.0.2 organisation (R02) ────────────────────────────
        // Shelves merge by name: fields from the newer write,
        // membership the UNION (a book added on either device is
        // on the shelf). A "shelf" tombstone at least as new as
        // the record deletes it (below, with the other tombstones).
        var shelves = mergeBy(local.shelves, remote.shelves, key = { it.name }) { a, b ->
            val newer = if (a.updatedAt >= b.updatedAt) a else b
            newer.copy(
                memberBookKeys = (a.memberBookKeys + b.memberBookKeys).distinct()
            )
        }
        shelves = shelves.filterNot { tombstoned("shelf", it.name, it.updatedAt) }
        val tags = mergeBy(local.tags, remote.tags, key = { it.slug }) { a, _ -> a }
        // A user correction (suppressed) on either side wins — a
        // removed tag must never silently come back.
        val bookTags = mergeBy(local.bookTags, remote.bookTags, key = { it.naturalKey }) { a, b ->
            if (a.suppressed || b.suppressed) {
                (if (a.suppressed) a else b)
            } else if (a.origin == "USER") a else b
        }
        val followedTags = (local.followedTagSlugs + remote.followedTagSlugs).distinct()
        val favourites = (local.favouriteBookKeys + remote.favouriteBookKeys).distinct()
        val themeSync = when {
            local.themeSync == null -> remote.themeSync
            remote.themeSync == null -> local.themeSync
            remote.themeSync.updatedAt > local.themeSync.updatedAt -> remote.themeSync
            else -> local.themeSync
        }

        val profile = mergeProfile(local.profile, remote.profile)
        val days = (local.dailyActivity.associateBy { it.date } +
            remote.dailyActivity.associateBy { it.date })
            .values
            .map { day ->
                val a = local.dailyActivity.firstOrNull { it.date == day.date }
                val b = remote.dailyActivity.firstOrNull { it.date == day.date }
                if (a == null) day else if (b == null) a else DayRecord(
                    date = day.date,
                    minutesRead = maxOf(a.minutesRead, b.minutesRead),
                    chaptersFinished = maxOf(a.chaptersFinished, b.chaptersFinished),
                    expEarned = maxOf(a.expEarned, b.expEarned),
                    goalMet = a.goalMet || b.goalMet
                )
            }
            .sortedBy { it.date }

        val merged = SyncPayload(
            format = SyncPayload.FORMAT,
            deviceId = local.deviceId,
            exportedAt = now,
            books = books,
            progress = progress,
            bookmarks = bookmarks,
            highlights = highlights,
            lore = lore,
            loreRelations = relations,
            terminology = terms,
            profile = profile,
            dailyActivity = days,
            readerPrefs = if (remote.exportedAt > local.exportedAt) {
                remote.readerPrefs.ifEmpty { local.readerPrefs }
            } else {
                local.readerPrefs
            },
            aiModelChoice = if (remote.exportedAt > local.exportedAt) {
                remote.aiModelChoice ?: local.aiModelChoice
            } else {
                local.aiModelChoice
            },
            tombstones = tombstones,
            shelves = shelves,
            tags = tags,
            bookTags = bookTags,
            followedTagSlugs = followedTags,
            favouriteBookKeys = favourites,
            themeSync = themeSync
        )
        return Result(merged, conflicts)
    }

    private fun mergeProgress(
        local: SyncPayload,
        remote: SyncPayload,
        conflicts: MutableList<String>
    ): List<ProgressRecord> {
        val byKey = LinkedHashMap<String, ProgressRecord>()
        for (record in local.progress) byKey[record.bookKey] = record
        for (record in remote.progress) {
            val existing = byKey[record.bookKey]
            if (existing == null) {
                byKey[record.bookKey] = record
                continue
            }
            if (existing == record) continue
            val further = furtherAlong(existing, record)
            val newer = if (existing.updatedAt >= record.updatedAt) existing else record
            if (further != newer) {
                conflicts += "Reading progress for one book changed on both devices — " +
                    "kept the further-along position (chapter " +
                    "${further.chapterIndex + 1}) so your place never moves backwards."
            }
            byKey[record.bookKey] = further.copy(
                updatedAt = maxOf(existing.updatedAt, record.updatedAt)
            )
        }
        return byKey.values.toList()
    }

    /** The record that is further through the book. */
    fun furtherAlong(a: ProgressRecord, b: ProgressRecord): ProgressRecord = when {
        a.chapterIndex != b.chapterIndex ->
            if (a.chapterIndex > b.chapterIndex) a else b
        a.charOffset != b.charOffset ->
            if (a.charOffset > b.charOffset) a else b
        a.fraction != b.fraction ->
            if (a.fraction > b.fraction) a else b
        else -> if (a.updatedAt >= b.updatedAt) a else b
    }

    private fun mergeProfile(a: ProfileRecord?, b: ProfileRecord?): ProfileRecord? {
        if (a == null) return b
        if (b == null) return a
        val richer = if (b.totalExp > a.totalExp) b else a
        return ProfileRecord(
            displayName = richer.displayName.ifBlank { a.displayName.ifBlank { b.displayName } },
            totalExp = maxOf(a.totalExp, b.totalExp),
            level = maxOf(a.level, b.level),
            dailyGoalMinutes = richer.dailyGoalMinutes,
            createdAt = minOf(
                a.createdAt.takeIf { it > 0 } ?: b.createdAt,
                b.createdAt.takeIf { it > 0 } ?: a.createdAt
            )
        )
    }

    private fun pickNewer(a: BookmarkRecord, b: BookmarkRecord, aDevice: String, bDevice: String): BookmarkRecord =
        when {
            a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
            else -> if (aDevice >= bDevice) a else b
        }

    private fun pickNewer(a: HighlightRecord, b: HighlightRecord, aDevice: String, bDevice: String): HighlightRecord =
        when {
            a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
            else -> if (aDevice >= bDevice) a else b
        }

    private fun pickNewer(a: LoreRecord, b: LoreRecord, aDevice: String, bDevice: String): LoreRecord =
        when {
            a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
            else -> if (aDevice >= bDevice) a else b
        }

    /**
     * Union of two keyed lists; on collision [resolve] picks (or
     * merges) the survivor.
     */
    private fun <T> mergeBy(
        local: List<T>,
        remote: List<T>,
        key: (T) -> String,
        resolve: (T, T) -> T
    ): List<T> {
        val byKey = LinkedHashMap<String, T>()
        for (item in local) byKey[key(item)] = item
        for (item in remote) {
            val existing = byKey[key(item)]
            byKey[key(item)] = if (existing == null) item else resolve(existing, item)
        }
        return byKey.values.toList()
    }
}
