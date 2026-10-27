package tablekeeper.print

import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream

/**
 * Multi-column print pages by imposition: text is laid out as one column on narrow pages ([columnWidthMm] ×
 * [columnHeightMm]) and those pages are placed side by side on landscape A4 sheets here. CSS columns can't do what a
 * packet needs: a section's heading repeated on each sheet it continues to.
 *
 * The text comes in [Part]s (a packet's sections: Characters, Landmarks…), each starting in a new column. Each sheet
 * gets the heading of every part on it in a band at the top, its line spanning that part's columns, and its number.
 * Those are drawn as HTML too (`furniture`, one page per [Sheet]) and laid over the sheet, so they're set like the text.
 */
object Imposition {
    private const val SHEET_MARGIN_MM = 10f
    private const val BOTTOM_MARGIN_MM = 12f
    private const val GAP_MM = 8f
    /** The band for section headings above the columns. */
    private const val HEADING_MM = 7f
    private val sheet = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)  // landscape

    /** A section's text, laid out as column pages ([pdf]), under [heading], e.g. "Landmarks". */
    class Part(val heading: String, val pdf: ByteArray)

    /** A part's heading on a sheet, across its columns: [leftMm] from the sheet's left edge, [widthMm] wide. */
    data class Heading(val text: String, val leftMm: Float, val widthMm: Float)

    /** What a sheet shows over its columns: [headings] at [topMm] from its top edge, and its [number]. */
    data class Sheet(val number: Int, val headings: List<Heading>) {
        val topMm = SHEET_MARGIN_MM
        val marginMm = SHEET_MARGIN_MM
    }

    fun columnWidthMm(columns: Int): Float = (mm(sheet.width) - 2 * SHEET_MARGIN_MM - (columns - 1) * GAP_MM) / columns
    fun columnHeightMm(): Float = mm(sheet.height) - SHEET_MARGIN_MM - BOTTOM_MARGIN_MM - HEADING_MM

    /** Which column pages go on which sheet, as (part, page) per column: a part's first page starts a new column. */
    internal fun layout(pageCounts: List<Int>, columns: Int): List<List<Pair<Int, Int>>> =
        pageCounts.flatMapIndexed { part, n -> (0 until n).map { part to it } }.chunked(columns)

    /** The sheets' headings and numbers, sheet numbers counting on from [firstNumber] (after the cover). */
    internal fun sheets(headings: List<String>, layout: List<List<Pair<Int, Int>>>, columns: Int, firstNumber: Int): List<Sheet> =
        layout.mapIndexed { i, onSheet ->
            // One heading per run of columns of the same part
            val runs = mutableListOf<Heading>()
            var start = 0
            for (k in 1..onSheet.size) {
                if (k == onSheet.size || onSheet[k].first != onSheet[start].first) {
                    val left = columnLeftMm(start, columns)
                    runs += Heading(headings[onSheet[start].first], left, columnLeftMm(k - 1, columns) + columnWidthMm(columns) - left)
                    start = k
                }
            }
            Sheet(firstNumber + i, runs)
        }

    /**
     * [cover] pages as they are, then the [parts]' column pages placed [columns] per landscape sheet, each sheet under
     * its page of [furniture] (landscape A4 pages, one per sheet, from the sheets' headings and numbers).
     */
    fun columns(cover: ByteArray?, parts: List<Part>, columns: Int, furniture: (List<Sheet>) -> ByteArray): ByteArray {
        val coverDoc = cover?.let { PDDocument.load(it) }
        val docs = parts.map { PDDocument.load(it.pdf) }
        val layout = layout(docs.map { it.numberOfPages }, columns)
        val sheets = sheets(parts.map { it.heading }, layout, columns, (coverDoc?.numberOfPages ?: 0) + 1)
        val furnitureDoc = sheets.takeIf { it.isNotEmpty() }?.let { PDDocument.load(furniture(it)) }
        try {
            PDDocument().use { out ->
                coverDoc?.pages?.forEach { out.importPage(it) }
                val layer = LayerUtility(out)
                layout.forEachIndexed { i, onSheet ->
                    val page = PDPage(sheet).also(out::addPage)
                    PDPageContentStream(out, page).use { cs ->
                        onSheet.forEachIndexed { k, (part, index) ->
                            cs.saveGraphicsState()
                            cs.transform(Matrix.getTranslateInstance(pt(columnLeftMm(k, columns)), pt(BOTTOM_MARGIN_MM)))
                            cs.drawForm(layer.importPageAsForm(docs[part], index))
                            cs.restoreGraphicsState()
                        }
                        cs.drawForm(layer.importPageAsForm(furnitureDoc!!, i))
                    }
                }
                return ByteArrayOutputStream().use { bytes -> out.save(bytes); bytes.toByteArray() }
            }
        } finally {
            coverDoc?.close()
            furnitureDoc?.close()
            docs.forEach { it.close() }
        }
    }

    private fun columnLeftMm(k: Int, columns: Int) = SHEET_MARGIN_MM + k * (columnWidthMm(columns) + GAP_MM)

    private fun pt(mm: Float) = mm / 25.4f * 72f
    private fun mm(pt: Float) = pt / 72f * 25.4f
}
