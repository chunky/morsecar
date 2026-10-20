package org.icculus.chunky.morsecar.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.createBitmap
import org.icculus.chunky.morsecar.text.WordWindow
import java.io.ByteArrayOutputStream

/**
 * Draws the word being sent, with its neighbors, as the "album art" that
 * Android Auto, the notification, and the lock screen show.
 */
object WordArtwork {
    private const val SIZE_PX = 480
    private const val NBSP = '\u00A0'
    private const val MARGIN_PX = 32
    private const val MAX_TEXT_PX = 64f
    private const val MIN_TEXT_PX = 28f

    private const val BACKGROUND = 0xFF101418.toInt()
    private const val CONTEXT_TEXT = 0xFF9AA0A6.toInt()
    private const val CURRENT_TEXT = 0xFF000000.toInt()
    private const val CURRENT_HIGHLIGHT = 0xFFFFC107.toInt()

    /** PNG bytes for [MediaMetadata.Builder.setArtworkData]. */
    fun render(window: WordWindow): ByteArray {
        val text = SpannableStringBuilder()
        window.before.forEach { text.append(it).append(' ') }
        val start = text.length
        // Non-breaking spaces pad the highlight a little without letting the line break there.
        text.append(NBSP).append(window.current).append(NBSP)
        val end = text.length
        window.after.forEach { text.append(' ').append(it) }
        text.setSpan(ForegroundColorSpan(CURRENT_TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(BackgroundColorSpan(CURRENT_HIGHLIGHT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = CONTEXT_TEXT
            typeface = Typeface.SANS_SERIF
        }
        val width = SIZE_PX - 2 * MARGIN_PX
        var textSize = MAX_TEXT_PX
        var layout: StaticLayout
        // Largest text that fits; long words shrink it.
        while (true) {
            paint.textSize = textSize
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.2f)
                .build()
            if (layout.height <= SIZE_PX - 2 * MARGIN_PX || textSize <= MIN_TEXT_PX) break
            textSize -= 4f
        }

        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.translate(MARGIN_PX.toFloat(), ((SIZE_PX - layout.height) / 2f).coerceAtLeast(MARGIN_PX.toFloat()))
        layout.draw(canvas)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
