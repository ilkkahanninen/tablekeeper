package tablekeeper.print

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ImpositionTest {
    /** A part's column pages: [pages] empty narrow pages. */
    private fun pages(pages: Int): ByteArray = PDDocument().use { doc ->
        repeat(pages) { doc.addPage(PDPage()) }
        ByteArrayOutputStream().use { doc.save(it); it.toByteArray() }
    }

    private fun sheetText(pdf: ByteArray): List<String> = PDDocument.load(pdf).use { doc ->
        (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).trim().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun `sections start in a new column and are headed again on each sheet they continue to`() {
        val pdf = Imposition.columns(null, listOf(Imposition.Part("Landmarks", pages(4)), Imposition.Part("Delves", pages(1))), 3)
        // Sheet 1: three Landmarks columns; sheet 2: the last Landmarks column, then Delves
        assertEquals(listOf("LANDMARKS 1", "LANDMARKS DELVES 2"), sheetText(pdf))
    }

    @Test
    fun `no parts, just the cover`() {
        assertEquals(listOf(""), sheetText(Imposition.columns(pages(1), emptyList(), 3)))
    }
}
