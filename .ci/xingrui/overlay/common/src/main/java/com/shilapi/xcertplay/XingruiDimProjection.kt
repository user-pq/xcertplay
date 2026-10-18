package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.Proxy

/** ECARX IConnectable lifecycle, not merely a successful void call to DIM. */
internal class XingruiDimProjection(context: Context, private val displayId: Int) {
    private val app = context.applicationContext
    private val thread = HandlerThread("xingrui-dim").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private var dim: Any? = null
    private var contract: Class<*>? = null
    private var connection: Class<*>? = null
    private var watcher: Any? = null
    private var connected = false
    private var dispatched = false

    fun start() { handler.post {
        if (closed || dim != null) return@post
        try {
            val cls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
            val connectable = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
            val callback = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val instance = checkNotNull(cls.getMethod("create", Context::class.java).invoke(null, app))
            require(connectable.isInstance(instance)) { "DIM does not implement IConnectable" }
            contract = cls
            connection = connectable
            dim = instance
            watcher = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, args ->
                when (method.name) {
                    "onConnected" -> { handler.post { onConnected() }; null }
                    "onDisConnected" -> { handler.post {
                        connected = false
                        Log.w(TAG, "DIM service disconnected; waiting for SDK reconnect")
                    }; null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "XingruiDimConnection"
                    else -> null
                }
            }
            connectable.getMethod("registerConnectWatcher", callback).invoke(instance, watcher)
            connectable.getMethod("connect").invoke(instance)
            Log.i(TAG, "DIM connect requested; display=$displayId waiting for onConnected")
            handler.postDelayed({
                if (!closed && !connected) Log.w(TAG, "DIM connection not ready after 15s; no projection write sent")
            }, 15000L)
        } catch (error: Exception) { Log.w(TAG, "DIM initialization failed", error.cause ?: error) }
    } }

    private fun onConnected() {
        if (closed) return
        connected = true
        Log.i(TAG, "DIM service connected")
        if (!AirPlayPersistence.loadClusterMapEnabled(app)) return
        try {
            // The first argument is APP_TYPE, NOT a display ID. Retain the reference app's
            // APP_TYPE_AMAP contract (1), resolving the named constant when the firmware exposes it.
            val cls = checkNotNull(contract)
            val appType = runCatching { cls.getField("APP_TYPE_AMAP").getInt(null) }.getOrDefault(1)
            cls.getMethod("setPresentationToDimSwitch", Int::class.javaPrimitiveType,
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(dim, appType, "", "", true)
            dispatched = true
            val option = runCatching { cls.getMethod("getShowPresentationOption").invoke(dim) }.getOrNull()
            Log.i(TAG, "DIM projection dispatched appType=$appType option=$option; physical visibility unconfirmed")
        } catch (error: Exception) { Log.w(TAG, "DIM projection failed", error.cause ?: error) }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            val instance = dim
            if (instance != null) {
                if (dispatched && connected) runCatching {
                    val cls = checkNotNull(contract)
                    val appType = runCatching { cls.getField("APP_TYPE_AMAP").getInt(null) }.getOrDefault(1)
                    cls.getMethod("setPresentationToDimSwitch", Int::class.javaPrimitiveType,
                        String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(instance, appType, "", "", false)
                    Log.i(TAG, "DIM release dispatched")
                }.onFailure { Log.w(TAG, "DIM release failed", it) }
                runCatching { connection?.getMethod("unregisterConnectWatcher")?.invoke(instance) }
                runCatching { connection?.getMethod("disconnect")?.invoke(instance) }
            }
            dim = null
            watcher = null
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }
    companion object { private const val TAG = "XingruiDIM" }
}
