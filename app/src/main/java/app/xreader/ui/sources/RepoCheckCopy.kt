package app.xreader.ui.sources

import app.xreader.core.error.AppError
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.extension.RepoManager

/**
 * v1.3.3 (V132-CHECK 9/25, scopes S7 + addition 7): which
 * repositories an update check may touch, and the words a
 * failed check speaks.
 *
 * Built-in sources are NOT a user repository: they ship
 * with the app and update only WITH the app. The synthetic
 * rows behind them (and behind My link sources) must never
 * reach an update check — v1.3.2's Explore header button
 * checked the bundled pseudo-repo, fetched its internal
 * xreader:// address as if it were a web URL, and reported
 * the nonsense toast "That file couldn't be used. that
 * address doesn't look right" (three stacked fragments from
 * three layers). The check now scopes to real added repos
 * only, and a failure is ONE sentence: the repo's name and
 * the plain-words reason, nothing stacked behind it.
 */
object RepoCheckPolicy {

    /** True for the synthetic rows (built-ins, link
     * sources) that are app furniture, not user repos. */
    fun isSynthetic(repo: ExtensionRepo): Boolean =
        repo.indexUrl == RepoManager.BUNDLED_REPO_URL ||
            repo.indexUrl == RepoManager.LINK_REPO_URL

    /** The repos an update check may actually check. */
    fun checkable(repos: List<ExtensionRepo>): List<ExtensionRepo> =
        repos.filter { !isSynthetic(it) }
}

object RepoCheckCopy {

    /** ONE clean sentence for a failed repo check. */
    fun failureLine(repoName: String, error: AppError): String =
        when (error) {
            is AppError.BadFile ->
                "Couldn't check “$repoName” — that address " +
                    "doesn't look right."
            AppError.NoNetwork ->
                "Couldn't check “$repoName” — there's no " +
                    "connection right now."
            is AppError.RateLimited ->
                "Couldn't check “$repoName” — the site is " +
                    "busy right now; try again in a moment."
            is AppError.ProviderDown -> {
                val detail = error.detail.orEmpty()
                if (
                    detail.contains("too long", ignoreCase = true) ||
                    detail.contains("timed out", ignoreCase = true) ||
                    detail.contains("timeout", ignoreCase = true) ||
                    detail.contains("in time", ignoreCase = true)
                ) {
                    "Couldn't check “$repoName” — the site " +
                        "took too long to answer."
                } else {
                    "Couldn't check “$repoName” — the site " +
                        "isn't answering right now."
                }
            }
            is AppError.ParseFailed ->
                "Couldn't check “$repoName” — the list it " +
                    "sent back couldn't be read."
            is AppError.AuthFailed ->
                "Couldn't check “$repoName” — the site " +
                    "refused the check."
            else ->
                "Couldn't check “$repoName” — something " +
                    "went wrong."
        }
}
