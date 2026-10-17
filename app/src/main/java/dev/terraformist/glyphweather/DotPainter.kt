package dev.terraformist.glyphweather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Paints a 25x25 grid as round dots, like the Glyph Matrix: white on black with faint unlit dots. */
object DotPainter {
    private const val GHOST = 0xFF232323.toInt()
    private const val DIM = 0xFF6E6E6E.toInt()
    private const val LIT = 0xFFF4F4F4.toInt()

    fun paint(g: CharArray, pitch: Int): Bitmap {
        val n = WeatherArt.N
        val bmp = Bitmap.createBitmap(n * pitch, n * pitch, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = pitch * 0.40f
        for (y in 0 until n) {
            for (x in 0 until n) {
                paint.color = when (g[y * n + x]) {
                    ' ' -> continue
                    '#' -> LIT
                    '-' -> DIM
                    else -> GHOST
                }
                canvas.drawCircle(x * pitch + pitch / 2f, y * pitch + pitch / 2f, radius, paint)
            }
        }
        return bmp
    }
}
