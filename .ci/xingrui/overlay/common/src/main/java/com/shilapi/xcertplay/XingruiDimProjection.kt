package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.Proxy

/** ECARX IConnectable lifecycle, not merely a successful void call to DIM. */
internal class XingruiDimProjection(context: Context, private val displayId: Int) {
    private val app = context.applicationContext
    private val ownerClass = CarPlayHostActivity::class.java.name
    private var requestEpoch = 0
    private var retry = 0
    private val ownerType = 0 // APP_TYPE_DEFAULT, as used by the working E02 reference APK.
    private val thread = HandlerThread("xingrui-dim").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private var dim: Any? = null
    private var contract: Class<*>? = null
    private var connection: Class<*>? = null
    private var watcher: Any? = null
    private var connected = false
    private var dispatched = false
    private val gate = XingruiProjectionGate()

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
                        requestEpoch++
                        connected = false
                        gate.connection(false)
                        XingruiRuntimeStatus.record("仪表", "DIM 服务已断开")
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
        } catch (error: Exception) {
            Log.w(TAG, "DIM initialization failed", error.cause ?: error)
            XingruiRuntimeStatus.record("仪表", "DIM 初始化失败：${error.cause?.message ?: error.message}")
        }
    } }

    private fun onConnected() {
        if (closed) return
        connected = true
        Log.i(TAG, "DIM service connected")
        XingruiRuntimeStatus.record("仪表", "DIM 已连接，等待仪表视频首帧")
        gate.connection(true)?.let(::dispatch)
    }

    fun videoReady(ready: Boolean) { handler.post {
        if (closed) return@post
        XingruiRuntimeStatus.record("仪表视频", if (ready) "首帧已提交到 Display $displayId" else "视频流已停止")
        gate.video(ready && AirPlayPersistence.loadClusterMapEnabled(app))?.let(::dispatch)
    } }

    private fun dispatch(show: Boolean) {
        requestEpoch++
        retry = 0
        attempt(show, requestEpoch)
    }

    private fun fieldValue(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val currentType = type
            val found = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (found != null) {
                found.isAccessible = true
                return found.get(if (java.lang.reflect.Modifier.isStatic(found.modifiers)) null else instance)
            }
            type = currentType.superclass
        }
        throw NoSuchFieldException("${instance.javaClass.name}.$name")
    }

    private fun attempt(show: Boolean, epoch: Int) {
        if (epoch != requestEpoch || !connected || (show && (closed || !AirPlayPersistence.loadClusterMapEnabled(app)))) return
        val cls = contract ?: return
        var retryNeeded = false
        try {
            cls.getMethod("setPresentationToDimSwitch", Int::class.javaPrimitiveType,
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(dim, ownerType, app.packageName, ownerClass, show)
            dispatched = show
            val navi = runCatching { cls.getMethod("getNaviInteraction").invoke(dim) }.getOrNull()
            var source = "not requested"
            if (show && navi != null) {
                val result = runCatching {
                    val power = checkNotNull(fieldValue(navi, "mPowerOperation")) { "power service not ready" }
                    power.javaClass.getMethod("disableScreenProjection", Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(power, false, 2)
                    "request dispatched"
                }
                source = result.getOrElse { "${it.cause ?: it}" }
                retryNeeded = result.isFailure
            }
            val enabled = runCatching { cls.getMethod("getPresentationToDimSwitch").invoke(dim) }.getOrNull()
            val option = runCatching { cls.getMethod("getShowPresentationOption").invoke(dim) }.getOrNull()
            val internal = navi?.let { runCatching { fieldValue(it, "isScreenProjection") }.getOrNull() }
            val property = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "sys.ecarx.navsyncscreen.status", "unknown")
            }.getOrNull()
            val message = "请求=$show appType=$ownerType switch=$enabled option=$option navsync=$property internal=$internal source=$source"
            XingruiRuntimeStatus.record("仪表", "$message；物理画面待实测")
            Log.i(TAG, message)
            retryNeeded = retryNeeded || enabled != show || (show && internal == false)
        } catch (error: Exception) {
            retryNeeded = true
            Log.w(TAG, "DIM projection failed", error.cause ?: error)
            XingruiRuntimeStatus.record("仪表", "切换失败：${error.cause ?: error}")
        }
        if (retryNeeded && retry < 3) {
            retry++
            handler.postDelayed({ attempt(show, epoch) }, retry * 1500L)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        requestEpoch++
        handler.post {
            val instance = dim
            if (instance != null) {
                gate.close()
                if (dispatched && connected) runCatching {
                    val cls = checkNotNull(contract)
                    cls.getMethod("setPresentationToDimSwitch", Int::class.javaPrimitiveType,
                        String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(instance, ownerType, app.packageName, ownerClass, false)
                    Log.i(TAG, "DIM release dispatched")
                    XingruiRuntimeStatus.record("仪表", "已释放投屏，请确认原厂仪表恢复")
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
