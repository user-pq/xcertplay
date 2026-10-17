package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * Ultra-inspired waiting screen shown before the iPhone cluster stream produces frames.
 * It intentionally contains no fake speed, gear, ADAS or warning data.
 */
internal class XingruiUltraPlaceholderView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val route = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = min(w / 1920f, h / 720f)

        paint.shader = LinearGradient(0f, 0f, w, h, Color.rgb(8, 13, 20), Color.rgb(24, 31, 43), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val sideW = w * 0.235f
        val margin = 30f * u
        val radius = 32f * u
        paint.color = Color.argb(170, 28, 37, 50)
        canvas.drawRoundRect(RectF(margin, margin, sideW, h - margin), radius, radius, paint)
        canvas.drawRoundRect(RectF(w - sideW, margin, w - margin, h - margin), radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(133, 211, 255)
        route.reset()
        route.moveTo(w * 0.38f, h * 0.74f)
        route.cubicTo(w * 0.44f, h * 0.60f, w * 0.47f, h * 0.48f, w * 0.53f, h * 0.45f)
        route.cubicTo(w * 0.60f, h * 0.41f, w * 0.61f, h * 0.27f, w * 0.66f, h * 0.22f)
        canvas.drawPath(route, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 30f * u
        paint.isFakeBoldText = true
        canvas.drawText("WIRELESS CARPLAY", w / 2f, 56f * u, paint)
        paint.isFakeBoldText = false
        paint.color = Color.argb(180, 255, 255, 255)
        paint.textSize = 22f * u
        canvas.drawText("Instrument stream is getting ready", w / 2f, h - 42f * u, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.argb(230, 255, 255, 255)
        paint.textSize = 26f * u
        canvas.drawText("NAVIGATION", margin * 2f, 90f * u, paint)
        paint.color = Color.argb(150, 255, 255, 255)
        paint.textSize = 20f * u
        canvas.drawText("iPhone cluster view", margin * 2f, 126f * u, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = Color.argb(230, 255, 255, 255)
        paint.textSize = 26f * u
        canvas.drawText("MEDIA", w - margin * 2f, 90f * u, paint)
        paint.color = Color.argb(150, 255, 255, 255)
        paint.textSize = 20f * u
        canvas.drawText("Steering-wheel ready", w - margin * 2f, 126f * u, paint)
    }
}
