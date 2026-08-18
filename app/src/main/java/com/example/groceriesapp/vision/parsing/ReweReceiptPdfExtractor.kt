package com.example.groceriesapp.vision.parsing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Extracts receipt text from an eBon PDF.
 *
 * [ReweReceiptParser] distinguishes item lines from their quantity/discount continuation lines by
 * indentation, which PDFBox's plain text output discards. So leading whitespace is reconstructed
 * from each line's glyph position relative to the receipt's left margin. Some eBons indent with
 * literal space glyphs whose first glyph sits at the left margin, so the position of the first
 * non-blank glyph is what identifies a continuation line.
 */
class ReweReceiptPdfExtractor(context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun extract(pdfBytes: ByteArray): String {
        val lines = mutableListOf<PositionedLine>()
        PDDocument.load(pdfBytes).use { document ->
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val startX = textPositions.firstOrNull { it.unicode.isNotBlank() }?.xDirAdj
                    if (text.isNotBlank() && startX != null) {
                        lines += PositionedLine(text.trim(), startX)
                    } else {
                        lines += PositionedLine("", null)
                    }
                    super.writeString(text, textPositions)
                }
            }
            stripper.sortByPosition = true
            stripper.getText(document)
        }
        return reconstructIndentation(lines)
    }

    internal data class PositionedLine(val text: String, val startX: Float?)

    internal companion object {
        /** Roughly one monospace character; centred header lines sit far beyond this. */
        const val INDENT_TOLERANCE_POINTS = 3f

        internal fun reconstructIndentation(lines: List<PositionedLine>): String {
            val leftMargin = lines.mapNotNull(PositionedLine::startX).minOrNull() ?: 0f
            return lines.joinToString("\n") { line ->
                when {
                    line.text.isEmpty() -> ""
                    line.startX != null && line.startX - leftMargin > INDENT_TOLERANCE_POINTS ->
                        " ${line.text}"
                    else -> line.text
                }
            }
        }
    }
}
