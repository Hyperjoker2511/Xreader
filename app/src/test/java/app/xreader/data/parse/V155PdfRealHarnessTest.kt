package app.xreader.data.parse

import android.app.Application
import app.xreader.core.error.AppResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.5 (§4, reality checks d + f): the harness the V154
 * finding said was missing — a commercial-shape synthetic PDF
 * (front matter + a dot-leader TOC page + three chapters with
 * KNOWN planted sentences, standard embedded fonts, an
 * outline tree) run through the REAL production stripper loop
 * ([PdfStripperLoop], the exact code [PdfBoxTextExtractor]
 * delegates to) and the REAL outline reader, with per-chapter
 * BODY assertions at the parser's output.
 *
 * The loop takes an opened [PDDocument]. WHY THIS RUNS UNDER
 * ROBOLECTRIC (empirically settled during the build, not a
 * guess): PDFBox-Android loads its standard-14 AFM metrics
 * through [PDFBoxResourceLoader] (Android assets), NOT the
 * classpath — on a plain JVM, `PDType1Font.<clinit>` dies with
 * `ExceptionInInitializerError: resource
 * 'com/tom_roush/pdfbox/resources/afm/Times-Roman.afm' not
 * found`, reproduced against the real pdfbox-android 2.0.27.0
 * jar during this build even with the AAR assets directory on
 * the classpath. That is the same wall the WS-7 fixtures
 * README records ("the production extractor's text stripping
 * needs Android (PDFBoxResourceLoader)"). Under Robolectric
 * the assets resolve, so this harness initialises the loader
 * exactly as production does and drives the loop on opened
 * documents; the twin that drives the production extractor
 * class itself is V155PdfBoxRobolectricTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V155PdfRealHarnessTest : ParserTestBase() {

    /** Production-shaped init: PDFBox's font metrics live in
     * Android assets, so the loader must be initialised before
     * ANY PDType1Font touch (fixture building included). */
    @Before fun initPdfBoxResources() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    private val sentences = listOf(
        "The amber sentinel watched the harbour through the long evening.",
        "The ferryman crossed the cold river with his lantern lit.",
        "The mountain road opened above the clouds at dawn."
    )

    /** Builds the fixture PDF; returns the file and the 0-based
     * page indexes where each chapter starts. */
    private fun buildCommercialPdf(
        name: String,
        withOutline: Boolean,
        chapterPages: Int = 1
    ): Pair<File, List<Int>> {
        val dir = temp.newFolder()
        val file = File(dir, name)
        val chapterStarts = mutableListOf<Int>()
        PDDocument().use { document ->
            fun addPage(lines: List<String>): PDPage {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 750f)
                    stream.setLeading(16f)
                    for (line in lines) {
                        stream.showText(line)
                        stream.newLine()
                    }
                    stream.endText()
                }
                return page
            }
            // Front matter: a title page with a little real text.
            addPage(listOf("The Synthetic Book", "A fixture in three chapters."))
            // The TOC page: dot leaders + page numbers.
            addPage(
                listOf(
                    "Chapter 1 The Amber Sentinel ............ 3",
                    "Chapter 2 The Cold River ............ 4",
                    "Chapter 3 The Mountain Road ............ 5"
                )
            )
            val chapterPagesList = mutableListOf<PDPage>()
            sentences.forEachIndexed { index, sentence ->
                chapterStarts += document.numberOfPages
                repeat(chapterPages) { part ->
                    chapterPagesList += addPage(
                        if (part == 0) {
                            listOf(
                                "Chapter ${index + 1}",
                                sentence,
                                "And the story continued from there, page after page, " +
                                    "with enough plain prose to be unmistakably a chapter body."
                            )
                        } else {
                            listOf(
                                sentence,
                                "More of the same chapter, still prose, still on its own page."
                            )
                        }
                    )
                }
            }
            if (withOutline) {
                val outline = PDDocumentOutline()
                document.documentCatalog.documentOutline = outline
                chapterStarts.forEachIndexed { index, _ ->
                    val item = PDOutlineItem()
                    item.title = "Chapter ${index + 1}"
                    val destination = PDPageFitDestination()
                    // Each chapter's first page, in build order.
                    destination.page = chapterPagesList[index * chapterPages]
                    item.destination = destination
                    outline.addLast(item)
                }
            }
            document.save(file)
        }
        return file to chapterStarts
    }

    /** An extractor whose pages/outline ARE the production
     * loop's output on the real file — the seam where the
     * V154 hollowness lived, driven end to end. */
    private inner class RealLoopExtractor(private val pdf: File) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            loadPdfDocument(pdf) { document -> PdfStripperLoop.strip(document) }

        override fun extractOutline(file: File): List<PdfOutlineEntry> =
            when (val result = loadPdfDocument(pdf) { document ->
                PdfOutlineReader.read(document)
            }) {
                is AppResult.Ok -> result.value
                is AppResult.Err -> emptyList()
            }
    }

    @Test fun stripperLoopExtractsPlantedSentencesFromEveryPage() {
        val (pdf, _) = buildCommercialPdf("strip.pdf", withOutline = true)
        val pages = when (val result =
            loadPdfDocument(pdf) { document -> PdfStripperLoop.strip(document) }
        ) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("strip failed: ${result.error}")
        }
        assertEquals(5, pages.size)
        val allText = pages.joinToString("\n")
        for (sentence in sentences) {
            assertTrue("planted sentence must survive stripping: $sentence", allText.contains(sentence))
        }
        assertTrue(allText.contains("Amber Sentinel"))
        assertTrue(allText.contains("............"))
    }

    @Test fun outlineReaderFindsThePlantedBookmarks() {
        val (pdf, chapterStarts) = buildCommercialPdf("outline.pdf", withOutline = true)
        val outline = when (val result =
            loadPdfDocument(pdf) { document -> PdfOutlineReader.read(document) }
        ) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("outline read failed: ${result.error}")
        }
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            outline.map { it.title }
        )
        assertEquals(chapterStarts, outline.map { it.pageIndex })
    }

    @Test fun realExtractorParserLandsEveryChaptersBody() {
        val (pdf, _) = buildCommercialPdf("full.pdf", withOutline = true)
        val work = File(temp.newFolder(), "work").apply { mkdirs() }
        val parsed = PdfBookParser(RealLoopExtractor(pdf)).parse(pdf, work) {}.unwrap()
        // Front matter section + the three outline chapters;
        // crucially NO pseudo-chapter per TOC line.
        assertEquals(4, parsed.chapters.size)
        assertTrue(
            "no chapter title may be a TOC line",
            parsed.chapters.none { it.title.contains("............") }
        )
        val texts = chapterTexts(parsed)
        sentences.forEachIndexed { index, sentence ->
            assertTrue(
                "chapter ${index + 1} body must contain its planted sentence",
                texts[index + 1].contains(sentence)
            )
        }
        assertEquals(0, parsed.droppedChapters)
    }

    @Test fun realExtractorWithoutOutlineCoalescesTheToc() {
        val (pdf, _) = buildCommercialPdf("nooutline.pdf", withOutline = false)
        val work = File(temp.newFolder(), "work").apply { mkdirs() }
        val parsed = PdfBookParser(RealLoopExtractor(pdf)).parse(pdf, work) {}.unwrap()
        val tocSections = parsed.chapters.count { it.title == "Table of Contents" }
        assertEquals("the TOC page must coalesce into ONE section", 1, tocSections)
        assertTrue(
            "no chapter title may be a TOC line",
            parsed.chapters.none { it.title.contains("............") }
        )
        val texts = chapterTexts(parsed)
        for (sentence in sentences) {
            assertTrue(
                "every planted sentence lands in some chapter body",
                texts.any { it.contains(sentence) }
            )
        }
    }

    /**
     * Reality check (f): phase timing on a synthetic 227-page
     * PDF — probe/registration work (metadata + outline, NO
     * stripping) against the completion's full strip + parse,
     * with the real production loop. Figures are printed for
     * the build report (machine-relative); the hard assertion
     * is structural: the parser's probe path never calls the
     * page stripper at all.
     */
    @Test fun phaseTimingProbeVsCompletionOn227Pages() {
        val (pdf, _) = buildCommercialPdf("big.pdf", withOutline = true, chapterPages = 74)
        // 2 front pages + 3 * 74 chapter pages = 224; pad to 227
        // by construction tolerance — assert on the real count.
        val probeStart = System.nanoTime()
        val probed = when (val result = loadPdfDocument(pdf) { document ->
            document.numberOfPages to PdfOutlineReader.read(document)
        }) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("probe failed: ${result.error}")
        }
        val probeMs = (System.nanoTime() - probeStart) / 1_000_000

        val completionStart = System.nanoTime()
        val work = File(temp.newFolder(), "work").apply { mkdirs() }
        val parsed = PdfBookParser(RealLoopExtractor(pdf)).parse(pdf, work) {}.unwrap()
        val completionMs = (System.nanoTime() - completionStart) / 1_000_000

        println(
            "V155-TIMING pages=${probed.first} probeMs=$probeMs " +
                "completionMs=$completionMs chapters=${parsed.chapters.size}"
        )
        assertTrue("probe must see the whole book", probed.first >= 200)
        assertEquals(3, probed.second.size)
        assertTrue(parsed.chapters.size >= 3)

        // Structural half: PdfBookParser.probe on a counting
        // extractor performs NO page extraction.
        var stripCalls = 0
        val counting = object : PdfTextExtractor {
            override fun extractPages(file: File): AppResult<List<String>> {
                stripCalls++
                return AppResult.Ok(emptyList())
            }

            override fun probe(file: File): AppResult<PdfProbe> =
                AppResult.Ok(PdfProbe(227, "Big", null, null, emptyList()))
        }
        val (staged, probeWork) = stage("counting.pdf", byteArrayOf(1))
        val probeResult = PdfBookParser(counting).probe(staged, probeWork).unwrap()
        assertEquals(227, probeResult.pageCount)
        assertEquals("registration path must not strip pages", 0, stripCalls)
    }
}
