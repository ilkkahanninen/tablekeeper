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
 */
object Imposition {
    private const val SHEET_MARGIN_MM = 10f
    private const val BOTTOM_MARGIN_MM = 12f
    private const val GAP_MM = 8f
    private val sheet = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)  // landscape

    fun columnWidthMm(columns: Int): Float = (mm(sheet.width) - 2 * SHEET_MARGIN_MM - (columns - 1) * GAP_MM) / columns
    fun columnHeightMm(): Float = mm(sheet.height) - SHEET_MARGIN_MM - BOTTOM_MARGIN_MM

    /** [cover] pages as they are, then [columnPages] placed [columns] per landscape sheet, with sheet numbers. */
    fun columns(cover: ByteArray?, columnPages: ByteArray?, columns: Int): ByteArray {
        val coverDoc = cover?.let { PDDocument.load(it) }
        val textDoc = columnPages?.let { PDDocument.load(it) }
        try {
            PDDocument().use { out ->
                coverDoc?.pages?.forEach { out.importPage(it) }
                val layer = LayerUtility(out)
                val text = textDoc
                if (text != null) for (first in 0 until text.numberOfPages step columns) {
                    val page = PDPage(sheet).also(out::addPage)
                    PDPageContentStream(out, page).use { cs ->
                        for (k in 0 until columns) {
                            val index = first + k
                            if (index >= text.numberOfPages) break
                            val form = layer.importPageAsForm(text, index)
                            val x = pt(SHEET_MARGIN_MM + k * (columnWidthMm(columns) + GAP_MM))
                            cs.saveGraphicsState()
                            cs.transform(Matrix.getTranslateInstance(x, pt(BOTTOM_MARGIN_MM)))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                        }
                        // Sheet number, bottom right
                        val number = out.numberOfPages.toString()
                        val font = PDType1Font.HELVETICA
                        cs.beginText()
                        cs.setFont(font, 7f)
                        cs.setNonStrokingColor(0.53f, 0.53f, 0.53f)
                        cs.newLineAtOffset(sheet.width - pt(SHEET_MARGIN_MM) - font.getStringWidth(number) / 1000 * 7f, pt(5f))
                        cs.showText(number)
                        cs.endText()
                    }
                }
                return ByteArrayOutputStream().use { bytes -> out.save(bytes); bytes.toByteArray() }
            }
        } finally {
            coverDoc?.close()
            textDoc?.close()
        }
    }

    private fun pt(mm: Float) = mm / 25.4f * 72f
    private fun mm(pt: Float) = pt / 72f * 25.4f
}
