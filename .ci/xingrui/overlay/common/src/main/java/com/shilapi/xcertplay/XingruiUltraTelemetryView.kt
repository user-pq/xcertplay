package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.roundToInt

/**
 * Ultra-inspired instrument frame for the dedicated Xingrui E02 build.
 *
 * Values are read from Android Automotive VHAL through reflection so the common Android
 * module does not need a compile-time android.car dependency. Missing/denied properties
 * are shown as dashes; this view never invents vehicle data.
 */
internal class XingruiUltraTelemetryView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val handler = Handler(Looper.getMainLooper())
    private val reader = XingruiCarPropertyReader(context.applicationContext)

    private var speedKmh: Int? = null
    private var rpm: Int? = null
    private var gear: String? = null

    private val poll = object : Runnable {
        override fun run() {
            val speed = reader.float("PERF_VEHICLE_SPEED")
            speedKmh = speed?.takeIf { it.isFinite() && it >= 0f }?.let { (it * 3.6f).roundToInt() }
            rpm = reader.float("ENGINE_RPM")?.takeIf { it.isFinite() && it >= 0f }?.roundToInt()
            gear = reader.int("GEAR_SELECTION")?.let(reader::gearName)
            invalidate()
            if (isAttachedToWindow) handler.postDelayed(this, 250L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(poll)
        poll.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        reader.close()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Keep the centre clear for Apple's stream 111 map while visually framing it like a
        // modern full-width cluster. The side panels are translucent so route context is retained.
        val side = (w * 0.205f).coerceIn(260f * density, 390f * density)
        val fade = side + 90f * density
        paint.shader = LinearGradient(0f, 0f, fade, 0f,
            intArrayOf(Color.argb(238, 6, 10, 15), Color.argb(205, 6, 10, 15), Color.TRANSPARENT),
            floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fade, h, paint)
        paint.shader = LinearGradient(w, 0f, w - fade, 0f,
            intArrayOf(Color.argb(238, 6, 10, 15), Color.argb(205, 6, 10, 15), Color.TRANSPARENT),
            floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(w - fade, 0f, w, h, paint)
        paint.shader = null

        // Header pill.
        paint.color = Color.argb(150, 5, 9, 14)
        val pillW = 250f * density
        val pillH = 38f * density
        val pill = RectF(w / 2f - pillW / 2f, 18f * density, w / 2f + pillW / 2f, 18f * density + pillH)
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, paint)
        drawText(canvas, "CARPLAY · XINGRUI", w / 2f, 44f * density, 13f * density, Color.WHITE, Paint.Align.CENTER)

        // Left: real VHAL speed when available.
        val leftX = 44f * density
        drawText(canvas, "SPEED", leftX, h * .28f, 12f * density, Color.rgb(165, 176, 188))
        drawText(canvas, speedKmh?.toString() ?: "—", leftX, h * .55f, 56f * density, Color.WHITE)
        drawText(canvas, "km/h", leftX, h * .65f, 15f * density, Color.rgb(165, 176, 188))

        // Right: real RPM and gear when VHAL permits reads.
        val rightX = w - 44f * density
        drawText(canvas, "ENGINE", rightX, h * .28f, 12f * density, Color.rgb(165, 176, 188), Paint.Align.RIGHT)
        drawText(canvas, rpm?.toString() ?: "—", rightX, h * .48f, 30f * density, Color.WHITE, Paint.Align.RIGHT)
        drawText(canvas, "rpm", rightX, h * .56f, 14f * density, Color.rgb(165, 176, 188), Paint.Align.RIGHT)
        drawText(canvas, "GEAR", rightX, h * .69f, 12f * density, Color.rgb(165, 176, 188), Paint.Align.RIGHT)
        drawText(canvas, gear ?: "—", rightX, h * .86f, 34f * density, Color.WHITE, Paint.Align.RIGHT)
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        text.textSize = size
        text.color = color
        text.textAlign = align
        canvas.drawText(value, x, baseline, text)
    }
}

private class XingruiCarPropertyReader(context: Context) {
    private val car: Any?
    private val manager: Any?
    private val getProperty: java.lang.reflect.Method?
    private val vehiclePropertyIds: Class<*>?
    private val vehicleGear: Class<*>?

    init {
        var c: Any? = null
        var m: Any? = null
        var getter: java.lang.reflect.Method? = null
        var ids: Class<*>? = null
        var gears: Class<*>? = null
        runCatching {
            val carClass = Class.forName("android.car.Car")
            c = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            val service = runCatching { carClass.getField("PROPERTY_SERVICE").get(null) as String }.getOrDefault("property")
            m = c?.javaClass?.getMethod("getCarManager", String::class.java)?.invoke(c, service)
            getter = m?.javaClass?.methods?.firstOrNull {
                it.name == "getProperty" && it.parameterTypes.size == 3 && it.parameterTypes[0] == Class::class.java
            }
            ids = Class.forName("android.car.VehiclePropertyIds")
            gears = Class.forName("android.car.VehicleGear")
        }.onFailure { android.util.Log.i(TAG, "VHAL reflection unavailable: ${it.javaClass.simpleName}") }
        car = c
        manager = m
        getProperty = getter
        vehiclePropertyIds = ids
        vehicleGear = gears
    }

    fun float(name: String): Float? = value(name, java.lang.Float::class.java)?.let {
        when (it) {
            is Number -> it.toFloat()
            else -> null
        }
    }

    fun int(name: String): Int? = value(name, java.lang.Integer::class.java)?.let {
        when (it) {
            is Number -> it.toInt()
            else -> null
        }
    }

    private fun value(name: String, klass: Class<*>): Any? = runCatching {
        val id = vehiclePropertyIds?.getField(name)?.getInt(null) ?: return null
        val property = getProperty?.invoke(manager, klass, id, 0) ?: return null
        property.javaClass.getMethod("getValue").invoke(property)
    }.onFailure {
        if (it is SecurityException || it.cause is SecurityException) {
            android.util.Log.d(TAG, "$name denied by VHAL permissions")
        }
    }.getOrNull()

    fun gearName(value: Int): String? {
        val names = listOf(
            "GEAR_PARK" to "P", "GEAR_REVERSE" to "R", "GEAR_NEUTRAL" to "N", "GEAR_DRIVE" to "D",
            "GEAR_1" to "1", "GEAR_2" to "2", "GEAR_3" to "3", "GEAR_4" to "4", "GEAR_5" to "5",
            "GEAR_6" to "6", "GEAR_7" to "7", "GEAR_8" to "8", "GEAR_9" to "9",
        )
        return names.firstOrNull { (field, _) ->
            runCatching { vehicleGear?.getField(field)?.getInt(null) == value }.getOrDefault(false)
        }?.second
    }

    fun close() {
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
    }

    companion object { private const val TAG = "XingruiVHAL" }
}
