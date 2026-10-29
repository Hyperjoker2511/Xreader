package app.xreader.data.opds

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import java.io.InputStream
import java.net.URI

/** One catalog entry: either a sub-catalog link or a book. */
data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    /** Navigation link to a sub-feed, when this entry is a category. */
    val navigationUrl: String?,
    /** Acquisition links by format mime (epub preferred first). */
    val acquisitions: List<OpdsAcquisition>
) {
    val isBook: Boolean get() = acquisitions.isNotEmpty()
}

data class OpdsAcquisition(
    val url: String,
    /** Normalised: "epub", "cbz", "pdf", "mobi", or the raw mime. */
    val formatKey: String,
    val mime: String
)

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextPageUrl: String?,
    val searchUrlTemplate: String?
)

/**
 * OPDS 1.x (Atom) catalog client (PLAN §4) — Komga, Kavita and
 * Calibre-Web all speak this. Parsing is pure (input stream in, feed
 * out) so recorded-style fixtures pin the behaviour; fetching goes
 * through [HttpTransport]. Credentials, when the server has them, are
 * supplied per request from the KeyVault by the caller (never stored
 * in this layer, never logged).
 */
class OpdsClient(private val transport: HttpTransport) {

    /** (username, password) lookup for a server id — KeyVault-backed in production. */
    fun interface CredentialsProvider {
        suspend fun credentialsFor(serverId: Long): Pair<String, String>?
    }

    suspend fun fetchFeed(
        serverId: Long,
        url: String,
        credentials: CredentialsProvider
    ): AppResult<OpdsFeed> {
        val auth = credentials.credentialsFor(serverId)
        val response = when (val result = transport.execute(
            HttpTransport.Request(url = url, basicAuth = auth)
        )) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return AppResult.Err(HttpTransport.statusError(response.code))
        }
        val body = response.body
            ?: return AppResult.Err(AppError.Unknown(IllegalStateException("empty feed")))
        return body.use { stream -> OpdsFeedParser.parse(stream, url) }
    }
}

/** Pure Atom/OPDS parser. */
object OpdsFeedParser {

    fun parse(input: InputStream, feedUrl: String): AppResult<OpdsFeed> {
        return try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)

            var feedTitle = ""
            val entries = mutableListOf<OpdsEntry>()
            var nextPage: String? = null
            var searchTemplate: String? = null

            // Current entry accumulators.
            var inEntry = false
            var eId = ""
            var eTitle = ""
            var eAuthor: String? = null
            var eSummary: String? = null
            var eCover: String? = null
            var eThumb: String? = null
            var eNav: String? = null
            var eAcquisitions = mutableListOf<OpdsAcquisition>()
            var inAuthor = false

            fun resolve(href: String?): String? = href?.let {
                runCatching { URI(feedUrl).resolve(it).toString() }.getOrDefault(it)
            }

            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "feed" -> Unit
                        "title" -> {
                            val text = parser.nextText().orEmpty()
                            if (inEntry) eTitle = text else feedTitle = text
                        }
                        "entry" -> {
                            inEntry = true
                            eId = ""
                            eTitle = ""
                            eAuthor = null
                            eSummary = null
                            eCover = null
                            eThumb = null
                            eNav = null
                            eAcquisitions = mutableListOf()
                        }
                        "id" -> if (inEntry) eId = parser.nextText().orEmpty()
                        "author" -> inAuthor = true
                        "name" -> if (inEntry && inAuthor) {
                            eAuthor = parser.nextText()?.takeIf { it.isNotBlank() }
                        }
                        "summary", "content" -> if (inEntry && eSummary == null) {
                            eSummary = parser.nextText()
                                ?.replace(Regex("<[^>]+>"), "")
                                ?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        "link" -> {
                            val rel = parser.getAttributeValue(null, "rel") ?: ""
                            val href = parser.getAttributeValue(null, "href")
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            val resolved = resolve(href)
                            when {
                                !inEntry && rel == "next" -> nextPage = resolved
                                !inEntry && rel == "search" -> searchTemplate = resolved
                                inEntry && rel == "subsection" ||
                                    inEntry && rel == "http://opds-spec.org/navigation" -> {
                                    eNav = resolved
                                }
                                inEntry && rel.contains("opds-spec.org/image/thumbnail") -> {
                                    eThumb = resolved
                                }
                                inEntry && rel.contains("opds-spec.org/image") -> {
                                    eCover = resolved
                                }
                                inEntry && rel.startsWith("http://opds-spec.org/acquisition") -> {
                                    if (resolved != null) {
                                        eAcquisitions += OpdsAcquisition(
                                            url = resolved,
                                            formatKey = formatKey(type),
                                            mime = type
                                        )
                                    }
                                }
                                inEntry && eNav == null &&
                                    type.contains("application/atom+xml") -> {
                                    eNav = resolved
                                }
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                        "author" -> inAuthor = false
                        "entry" -> {
                            inEntry = false
                            entries += OpdsEntry(
                                id = eId,
                                title = eTitle.ifBlank { "Untitled" },
                                author = eAuthor,
                                summary = eSummary,
                                coverUrl = eCover,
                                thumbnailUrl = eThumb ?: eCover,
                                navigationUrl = eNav,
                                acquisitions = eAcquisitions.sortedBy { acquisitionRank(it.formatKey) }
                            )
                        }
                    }
                }
                event = parser.next()
            }
            AppResult.Ok(OpdsFeed(feedTitle, entries, nextPage, searchTemplate))
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("OPDS", "the catalog couldn't be read", e.javaClass.simpleName))
        }
    }

    private fun acquisitionRank(formatKey: String): Int = when (formatKey) {
        "epub" -> 0
        "cbz" -> 1
        "pdf" -> 2
        "mobi" -> 3
        else -> 4
    }

    private fun formatKey(mime: String): String = when {
        mime.contains("epub") -> "epub"
        mime.contains("zip") && mime.contains("comic") -> "cbz"
        mime.contains("application/pdf") -> "pdf"
        mime.contains("mobi") || mime.contains("kindle") -> "mobi"
        else -> mime
    }
}
