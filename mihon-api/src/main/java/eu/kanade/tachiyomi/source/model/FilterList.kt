/*
 * Shape follows keiyoushi/extensions-lib @42255ee5 (the binary contract) — see
 * NOTICE. Komikku's copy of this class adds a Compose @Stable annotation and an
 * equals() override that always returns false for its own UI recomposition;
 * neither belongs in the host contract (and @Stable would drag a Compose
 * dependency into this API module), so the contract's plain data class stands.
 */
package eu.kanade.tachiyomi.source.model

@Suppress("Unused", "JavaDefaultMethodsNotOverriddenByDelegation")
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())
}
