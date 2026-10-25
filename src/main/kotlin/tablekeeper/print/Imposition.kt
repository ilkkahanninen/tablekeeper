package tablekeeper.print

import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream

/**
 * Multi-column print pages by imposition. openhtmltopdf's CSS columns are unreliable (blocks squeeze to one word
 * per line, nested first children jump columns), so text is laid out as one column on narrow pages
 * ([columnWidthMm] × [columnHeightMm]) and those pages are placed side by side on landscape A4 sheets here.
 *
 * The text comes in [Part]s (a packet's sections: Characters, Landmarks…), each starting in a new column. Each sheet
 * gets the heading of every part on it in a band at the top, its line spanning that part's columns, so a part
 * that continues onto the next sheet is headed again there.
 */
object Imposition {
    private const val SHEET_MARGIN_MM = 10f
    private const val BOTTOM_MARGIN_MM = 12f
    private const val GAP_MM = 8f
    /** The band for section headings above the columns: text, then the line (at 5 mm), then a little space. */
    private const val HEADING_MM = 7f
    private val sheet = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)  // landscape

    /** A section's text, laid out as column pages ([pdf]), under [heading], e.g. "Landmarks". */
    class Part(val heading: String, val pdf: ByteArray)

    fun columnWidthMm(columns: Int): Float = (mm(sheet.width) - 2 * SHEET_MARGIN_MM - (columns - 1) * GAP_MM) / columns
    fun columnHeightMm(): Float = mm(sheet.height) - SHEET_MARGIN_MM - BOTTOM_MARGIN_MM - HEADING_MM

    /** [cover] pages as they are, then the [parts]' column pages placed [columns] per landscape sheet, headed, with sheet numbers. */
    fun columns(cover: ByteArray?, parts: List<Part>, columns: Int): ByteArray {
        val coverDoc = cover?.let { PDDocument.load(it) }
        val docs = parts.map { PDDocument.load(it.pdf) }
        try {
            PDDocument().use { out ->
                coverDoc?.pages?.forEach { out.importPage(it) }
                val layer = LayerUtility(out)
                // Every column page as (part, page); a part's first page starts a new column
                val slots = docs.flatMapIndexed { part, doc -> (0 until doc.numberOfPages).map { part to it } }
                for (onSheet in slots.chunked(columns)) {
                    val page = PDPage(sheet).also(out::addPage)
                    PDPageContentStream(out, page).use { cs ->
                        onSheet.forEachIndexed { k, (part, index) ->
                            cs.saveGraphicsState()
                            cs.transform(Matrix.getTranslateInstance(columnX(k, columns), pt(BOTTOM_MARGIN_MM)))
                            cs.drawForm(layer.importPageAsForm(docs[part], index))
                            cs.restoreGraphicsState()
                        }
                        // One heading per run of columns of the same part
                        var start = 0
                        for (k in 1..onSheet.size) {
                            if (k == onSheet.size || onSheet[k].first != onSheet[start].first) {
                                heading(cs, parts[onSheet[start].first].heading, columnX(start, columns), columnX(k - 1, columns) + pt(columnWidthMm(columns)))
                                start = k
                            }
                        }
                        sheetNumber(cs, out.numberOfPages.toString())
                    }
                }
                return ByteArrayOutputStream().use { bytes -> out.save(bytes); bytes.toByteArray() }
            }
        } finally {
            coverDoc?.close()
            docs.forEach { it.close() }
        }
    }

    private fun columnX(k: Int, columns: Int) = pt(SHEET_MARGIN_MM + k * (columnWidthMm(columns) + GAP_MM))

    /** A section heading from [x1] to [x2] at the top: spaced capitals in the packet's red over a red line (as its h2 style). */
    private fun heading(cs: PDPageContentStream, text: String, x1: Float, x2: Float) {
        val top = sheet.height - pt(SHEET_MARGIN_MM)
        cs.setNonStrokingColor(140 / 255f, 28 / 255f, 19 / 255f)
        cs.setStrokingColor(140 / 255f, 28 / 255f, 19 / 255f)
        cs.beginText()
        cs.setFont(PDType1Font.TIMES_ROMAN, 9f)
        cs.setCharacterSpacing(0.6f)
        cs.newLineAtOffset(x1, top - pt(3.5f))
        cs.showText(text.uppercase())
        cs.endText()
        cs.setCharacterSpacing(0f)
        cs.setLineWidth(pt(0.3f))
        cs.moveTo(x1, top - pt(5f))
        cs.lineTo(x2, top - pt(5f))
        cs.stroke()
    }

    /** The sheet's number, bottom right. */
    private fun sheetNumber(cs: PDPageContentStream, number: String) {
        val font = PDType1Font.HELVETICA
        cs.beginText()
        cs.setFont(font, 7f)
        cs.setNonStrokingColor(0.53f, 0.53f, 0.53f)
        cs.newLineAtOffset(sheet.width - pt(SHEET_MARGIN_MM) - font.getStringWidth(number) / 1000 * 7f, pt(5f))
        cs.showText(number)
        cs.endText()
    }

    private fun pt(mm: Float) = mm / 25.4f * 72f
    private fun mm(pt: Float) = pt / 72f * 25.4f
}
