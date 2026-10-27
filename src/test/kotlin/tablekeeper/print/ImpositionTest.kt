package tablekeeper.print

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ImpositionTest {
    /** A part's column pages: [pages] empty narrow pages. */
    private fun pages(pages: Int): ByteArray = pdf(List(pages) { "" }, PDRectangle.A6)

    /** One page per text, e.g. the furniture the app renders from [Imposition.Sheet]s. */
    private fun pdf(texts: List<String>, size: PDRectangle = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)): ByteArray =
        PDDocument().use { doc ->
            texts.forEach { text ->
                val page = PDPage(size).also(doc::addPage)
                if (text.isNotEmpty()) PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9f); cs.newLineAtOffset(20f, 20f); cs.showText(text); cs.endText()
                }
            }
            ByteArrayOutputStream().use { doc.save(it); it.toByteArray() }
        }

    private fun sheetText(pdf: ByteArray): List<String> = PDDocument.load(pdf).use { doc ->
        (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).trim().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun `sections start in a new column and are headed again on each sheet they continue to`() {
        val sheets = Imposition.sheets(listOf("Landmarks", "Delves"), Imposition.layout(listOf(4, 1), 3), 3, firstNumber = 2)
        // Sheet 1: three Landmarks columns; sheet 2: the last Landmarks column, then Delves
        assertEquals(listOf(2 to listOf("Landmarks"), 3 to listOf("Landmarks", "Delves")), sheets.map { s -> s.number to s.headings.map { it.text } })
        val column = Imposition.columnWidthMm(3)
        val (wide, oneColumn) = sheets[0].headings.single() to sheets[1].headings[0]
        assertEquals(10f, wide.leftMm)
        assertEquals(3 * column + 2 * 8f, wide.widthMm, 0.01f)   // across all three columns and their gaps
        assertEquals(column, oneColumn.widthMm, 0.01f)
        assertEquals(10f + column + 8f, sheets[1].headings[1].leftMm, 0.01f)
    }

    @Test
    fun `each sheet gets its furniture page, after the cover`() {
        val pdf = Imposition.columns(pdf(listOf("Cover")), listOf(Imposition.Part("Landmarks", pages(4)), Imposition.Part("Delves", pages(1))), 3) { sheets ->
            pdf(sheets.map { s -> s.headings.joinToString(" ") { it.text.uppercase() } + " ${s.number}" })
        }
        assertEquals(listOf("Cover", "LANDMARKS 2", "LANDMARKS DELVES 3"), sheetText(pdf))
    }

    @Test
    fun `no parts, just the cover`() {
        assertEquals(listOf(""), sheetText(Imposition.columns(pages(1), emptyList(), 3) { error("No sheets, no furniture") }))
    }
}
