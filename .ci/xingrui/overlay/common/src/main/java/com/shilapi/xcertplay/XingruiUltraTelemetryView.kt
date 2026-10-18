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
    private var reader: XingruiCarPropertyReader? = null
    private var worker: android.os.HandlerThread? = null
    private var io: Handler? = null
    private var generation = 0

    private var speedKmh: Int? = null
    private var rpm: Int? = null
    private var gear: String? = null

    private val poll = object : Runnable {
        override fun run() {
            val ticket = generation
            val source = reader ?: return
            io?.post {
                val speed = source.float("PERF_VEHICLE_SPEED")
                    ?.takeIf { it.isFinite() && it >= 0f }?.let { (it * 3.6f).roundToInt() }
                val engine = source.float("ENGINE_RPM")?.takeIf { it.isFinite() && it >= 0f }?.roundToInt()
                val selected = (source.int("CURRENT_GEAR") ?: source.int("GEAR_SELECTION"))?.let(source::gearName)
                handler.post {
                    if (generation != ticket || !isAttachedToWindow) return@post
                    speedKmh = speed
                    rpm = engine
                    gear = selected
                    invalidate()
                    handler.postDelayed(this, 250L)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        generation++
        reader = XingruiCarPropertyReader(context.applicationContext)
        worker = android.os.HandlerThread("xingrui-vhal").also { it.start() }
        io = Handler(worker!!.looper)
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        generation++
        handler.removeCallbacks(poll)
        reader?.close()
        reader = null
        io?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        io = null
        worker = null
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

private class XingruiCarPropertyReader(private val context: Context) {
    @Volatile private var car: Any? = null
    @Volatile private var manager: Any? = null
    @Volatile private var getter: java.lang.reflect.Method? = null
    @Volatile private var closed = false
    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var step = "load Car"
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            if (!closed) bindManager()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            manager = null
            getter = null
            android.util.Log.w(TAG, "car service disconnected; values unavailable")
        }
    }

    init {
        try {
            val type = Class.forName("android.car.Car")
            // Android 9 uses createCar(Context, ServiceConnection[, Handler]) + connect().
            val legacy = type.methods.firstOrNull { it.name == "createCar" &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java,
                    android.content.ServiceConnection::class.java, Handler::class.java)) }
            val legacy2 = type.methods.firstOrNull { it.name == "createCar" &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java,
                    android.content.ServiceConnection::class.java)) }
            step = "createCar"
            car = when {
                legacy != null -> legacy.invoke(null, context, connection, Handler(Looper.getMainLooper()))
                legacy2 != null -> legacy2.invoke(null, context, connection)
                else -> type.getMethod("createCar", Context::class.java).invoke(null, context)
            }
            checkNotNull(car) { "createCar returned null" }
            if (legacy != null || legacy2 != null) {
                step = "connect"
                type.getMethod("connect").invoke(car)
                android.util.Log.i(TAG, "legacy car service connection requested")
            } else bindManager()
        } catch (error: Throwable) { failure(step, error) }
    }

    private fun bindManager() {
        try {
            val current = car ?: return
            val m = current.javaClass.getMethod("getCarManager", String::class.java).invoke(current, "property")
                ?: error("property service unavailable")
            val method = m.javaClass.getMethod("getProperty", Class::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            if (!closed) {
                getter = method
                manager = m
                android.util.Log.i(TAG, "CarPropertyManager ready")
            }
        } catch (error: Throwable) { failure("getCarManager/getProperty", error) }
    }

    fun float(name: String): Float? = (value(name, java.lang.Float::class.java) as? Number)?.toFloat()
    fun int(name: String): Int? = (value(name, java.lang.Integer::class.java) as? Number)?.toInt()

    private fun value(name: String, klass: Class<*>): Any? {
        if (closed) return null
        val target = manager ?: return null
        val method = getter ?: return null
        try {
            val id = IDS[name] ?: return null
            val property = method.invoke(target, klass, id, 0) ?: return null
            val status = property.javaClass.getMethod("getStatus").invoke(property) as Int
            if (status != 0) {
                if (reported.add("$name/status=$status")) android.util.Log.w(TAG, "$name unavailable status=$status")
                return null
            }
            val result = property.javaClass.getMethod("getValue").invoke(property)
            if (reported.add("$name/read")) android.util.Log.i(TAG, "$name first read OK value=$result")
            return result
        } catch (error: Throwable) { failure(name, error); return null }
    }

    private fun failure(stage: String, error: Throwable) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        if (reported.add("$stage/${cause.javaClass.name}")) {
            android.util.Log.w(TAG, "stage=$stage failed ${cause.javaClass.simpleName}: ${cause.message}", cause)
        }
    }

    // Standard AAOS VehicleGear bit values only. Vendor codes are never interpreted here.
    fun gearName(value: Int): String? = when (value) {
        1 -> "N"; 2 -> "R"; 4 -> "P"; 8 -> "D"
        16 -> "1"; 32 -> "2"; 64 -> "3"; 128 -> "4"; 256 -> "5"
        512 -> "6"; 1024 -> "7"; 2048 -> "8"; 4096 -> "9"
        else -> null
    }

    fun close() {
        closed = true
        manager = null
        getter = null
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
    }

    companion object {
        private const val TAG = "XingruiVHAL"
        private val IDS = mapOf("PERF_VEHICLE_SPEED" to 0x11600207, "ENGINE_RPM" to 0x11600305,
            "GEAR_SELECTION" to 0x11400400, "CURRENT_GEAR" to 0x11400401)
    }
}
