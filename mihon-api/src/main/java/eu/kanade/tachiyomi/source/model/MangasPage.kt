/*
 * Transplanted for Xreader from Komikku (source-api, Apache-2.0) with the
 * deviations named in the NOTICE file in this module.
 */
package eu.kanade.tachiyomi.source.model

class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MangasPage) return false

        if (mangas != other.mangas) return false
        if (hasNextPage != other.hasNextPage) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mangas.hashCode()
        result = 31 * result + hasNextPage.hashCode()
        return result
    }

    override fun toString(): String {
        return "MangasPage(mangas=$mangas, hasNextPage=$hasNextPage)"
    }


    @Deprecated("MangasPage is now a regular class")
    operator fun component1(): List<SManga> = mangas

    @Deprecated("MangasPage is now a regular class")
    operator fun component2(): Boolean = hasNextPage

    @Deprecated("MangasPage is now a regular class")
    fun copy(
        mangas: List<SManga> = this.mangas,
        hasNextPage: Boolean = this.hasNextPage,
    ): MangasPage = MangasPage(
        mangas = mangas,
        hasNextPage = hasNextPage,
    )
}

