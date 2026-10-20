package org.icculus.chunky.morsecar.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import java.io.ByteArrayOutputStream

/**
 * The book's title (and author) as "album art". Kept in the top part of the
 * image: Android Auto's dashboard card uses the artwork as a background and
 * lays the title line over its middle.
 */
object BookArtwork {
    private const val SIZE_PX = 480
    private const val MARGIN_PX = 36
    /** The title gets at most this much of the height, so the middle stays clear. */
    private const val TITLE_MAX_HEIGHT_PX = 220
    private const val MAX_TITLE_PX = 72f
    private const val MIN_TITLE_PX = 32f
    private const val AUTHOR_PX = 34f

    private const val BACKGROUND = 0xFF1B2430.toInt()
    private const val TITLE_TEXT = 0xFFE8EAED.toInt()
    private const val AUTHOR_TEXT = 0xFF9AA0A6.toInt()

    /** PNG bytes for MediaMetadata artwork. */
    fun render(title: String, author: String?): ByteArray {
        val width = SIZE_PX - 2 * MARGIN_PX
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = TITLE_TEXT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        var size = MAX_TITLE_PX
        var titleLayout: StaticLayout
        while (true) {
            titlePaint.textSize = size
            titleLayout = layout(title, titlePaint, width)
            if (titleLayout.height <= TITLE_MAX_HEIGHT_PX || size <= MIN_TITLE_PX) break
            size -= 4f
        }
        val authorLayout = author?.let {
            layout(it, TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = AUTHOR_TEXT
                textSize = AUTHOR_PX
                typeface = Typeface.SANS_SERIF
            }, width)
        }

        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.withClip(MARGIN_PX, MARGIN_PX, SIZE_PX - MARGIN_PX, MARGIN_PX + TITLE_MAX_HEIGHT_PX) {
            translate(MARGIN_PX.toFloat(), MARGIN_PX.toFloat())
            titleLayout.draw(this)
        }
        authorLayout?.let {
            canvas.translate(MARGIN_PX.toFloat(), MARGIN_PX + minOf(titleLayout.height, TITLE_MAX_HEIGHT_PX) + 16f)
            it.draw(canvas)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}
