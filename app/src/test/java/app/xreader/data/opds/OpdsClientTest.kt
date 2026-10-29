package app.xreader.data.opds

import app.xreader.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded-style Komga/Kavita Atom fixtures (structure-faithful). */
class OpdsClientTest {

    private val feedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>urn:komga:root</id>
          <title>Komga Library</title>
          <link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>
          <link rel="next" href="/opds/books?page=2" type="application/atom+xml"/>
          <entry>
            <title>All Series</title>
            <id>urn:komga:series</id>
            <link rel="subsection" href="/opds/series" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>The Fixture Novel</title>
            <id>urn:komga:book:42</id>
            <author><name>Jane Author</name></author>
            <summary>A novel about tests.</summary>
            <link rel="http://opds-spec.org/image" href="/opds/books/42/cover.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/image/thumbnail" href="/opds/books/42/thumb.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/books/42/file.epub" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/books/42/file.pdf" type="application/pdf"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test fun parsesNavigationBooksAndPaging() {
        val feed = when (val r = OpdsFeedParser.parse(feedXml.byteInputStream(), "https://komga.local/opds")) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("parse failed: ${r.error}")
        }
        assertEquals("Komga Library", feed.title)
        assertEquals("https://komga.local/opds/books?page=2", feed.nextPageUrl)
        assertEquals(2, feed.entries.size)

        val nav = feed.entries[0]
        assertTrue(!nav.isBook)
        assertEquals("https://komga.local/opds/series", nav.navigationUrl)

        val book = feed.entries[1]
        assertTrue(book.isBook)
        assertEquals("The Fixture Novel", book.title)
        assertEquals("Jane Author", book.author)
        assertEquals("A novel about tests.", book.summary)
        assertEquals("https://komga.local/opds/books/42/cover.jpg", book.coverUrl)
        // EPUB ranks before PDF in the acquisition list.
        assertEquals("epub", book.acquisitions.first().formatKey)
        assertEquals(
            "https://komga.local/opds/books/42/file.epub",
            book.acquisitions.first().url
        )
    }

    @Test fun fetchGoesThroughTransportWithAuth() {
        val client = OpdsClient(
            app.xreader.data.FakeTransport { request ->
                assertEquals("user", request.basicAuth?.first)
                app.xreader.data.FakeTransport.ok(feedXml)
            }
        )
        kotlinx.coroutines.test.runTest {
            val result = client.fetchFeed(7, "https://komga.local/opds") { "user" to "pass" }
            assertTrue(result is AppResult.Ok)
        }
    }
}
