package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Optional OEM presentation activation. Contract reference: jiopia/DiPlay-ECARX,
 * EcarxDimApi at 7f78343642fe695bb56906c3d8761c1c68bae7ce (different ECARX vehicle).
 * A void return means dispatch only; E02 support and physical output remain unverified. */
internal class XingruiDimProjection(context: Context, private val displayId: Int) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xingrui-dim-switch").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    @Volatile private var wanted = false
    private var interaction: Any? = null
    private var setter: java.lang.reflect.Method? = null
    private var dispatched = false
    private val failures = mutableSetOf<String>()

    fun start() {
        if (wanted) return
        wanted = true
        task = executor.scheduleWithFixedDelay({
            if (!wanted) return@scheduleWithFixedDelay
            try {
                if (interaction == null) {
                    val cls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
                    setter = cls.getMethod("setPresentationToDimSwitch", Int::class.javaPrimitiveType,
                        String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    interaction = cls.getMethod("create", Context::class.java).invoke(null, app)
                    checkNotNull(interaction) { "DimInteraction.create returned null" }
                    Log.i(TAG, "DIM API present; permission DIM_MESSAGING=" + app.checkSelfPermission(
                        "android.cevt.dimsupportservice.permission.DIM_MESSAGING") +
                        " OPENAPI_NAVI=" + app.checkSelfPermission("ecarx.oem.permission.OPENAPI_NAVI_PERMISSION"))
                }
                if (wanted && AirPlayPersistence.loadClusterMapEnabled(app)) {
                    setter?.invoke(interaction, displayId, "", "", true)
                    if (!dispatched) Log.i(TAG,
                        "OEM projection requested display=$displayId enabled=true; physical visibility NOT confirmed")
                    dispatched = true
                }
            } catch (error: Exception) {
                val cause = error.cause ?: error
                if (failures.add(cause.javaClass.name)) Log.w(TAG,
                    "OEM projection unavailable ${cause.javaClass.simpleName}: ${cause.message}", cause)
                if (cause is ClassNotFoundException || cause is NoSuchMethodException || cause is SecurityException) {
                    wanted = false // Missing/incompatible/denied API: no repeated writes or fallback guesses.
                }
            }
        }, 0, 10, TimeUnit.SECONDS)
    }

    fun close() {
        wanted = false
        task?.cancel(false)
        executor.execute {
            if (dispatched) runCatching {
                setter?.invoke(interaction, displayId, "", "", false)
                Log.i(TAG, "OEM projection release requested display=$displayId; physical restoration unconfirmed")
            }.onFailure { Log.w(TAG, "OEM projection release failed", it) }
            interaction = null
        }
        executor.shutdown()
    }

    companion object { private const val TAG = "XingruiDIM" }
}
