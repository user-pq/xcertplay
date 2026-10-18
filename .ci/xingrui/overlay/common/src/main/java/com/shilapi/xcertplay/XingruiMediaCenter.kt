package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.reflect.Proxy

/** E02 rejected MediaCenter authorization. Use the SDK's own key interception API,
 * with this app's real Context, and respect rejection. No media-license or identity changes. */
internal object XingruiMediaCenter {
    private val main = Handler(Looper.getMainLooper())
    private var bridge: Bridge? = null
    private var sharedInput: Any? = null
    fun attach(context: Context, controller: CarPlayController) { main.post {
        if (bridge?.controller === controller) return@post
        bridge?.close()
        bridge = Bridge(context.applicationContext, controller).also { it.start() }
    } }
    fun detach(controller: CarPlayController) { main.post {
        if (bridge?.controller === controller) { bridge?.close(); bridge = null }
    } }
    fun audioActive(active: Boolean) = Unit
    fun metadataChanged(controller: CarPlayController) = Unit

    private class Bridge(val app: Context, val controller: CarPlayController) {
        @Volatile private var closed = false
        private var input: Any? = null
        private var callback: Any? = null
        private var contract: Class<*>? = null
        private var callbackClass: Class<*>? = null
        private val presses = XingruiWheelPresses()
        fun start() {
            try {
                val cls = Class.forName("com.ecarx.xui.adaptapi.input.Input")
                val cb = Class.forName("com.ecarx.xui.adaptapi.input.IKeyCallback")
                contract = cls
                callbackClass = cb
                input = sharedInput ?: checkNotNull(cls.getMethod("create", Context::class.java).invoke(null, app)).also { sharedInput = it }
                callback = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { proxy, method, args ->
                    when (method.name) {
                        "onKeyPressed" -> {
                            val code = args?.firstOrNull() as? Int
                            if (code == null || closed) false else presses.down(code) {
                                val button = buttonFor(code)
                                val sent = button != null && !inCall(app) &&
                                    runCatching { controller.sendMediaButton(button) }.getOrDefault(false)
                                XingruiRuntimeStatus.record("方控", "收到按键 $code，CarPlay 发送=$sent")
                                sent
                            }
                        }
                        "onKeyReleased" -> {
                            val code = args?.firstOrNull() as? Int
                            code != null && presses.up(code)
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "XingruiInputCallback"
                        else -> null
                    }
                }
                // Only E02-confirmed 305 plus standard Android media keys. Never guess vendor codes.
                val codes = intArrayOf(305, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
                val result = cls.getMethod("requestKeysInterception", IntArray::class.java, cb)
                    .invoke(input, codes, callback)
                val detail = (result as? IntArray)?.joinToString(",") ?: "null"
                XingruiRuntimeStatus.record("方控", "已申请系统按键回调，返回=[$detail]；等待真实按键确认")
            } catch (error: Throwable) {
                val cause = error.cause ?: error
                XingruiRuntimeStatus.record("方控", "接口不可用：${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
        private fun buttonFor(code: Int): Int? = when (code) {
            305, KeyEvent.KEYCODE_MEDIA_NEXT -> CarPlayMediaButton.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> CarPlayMediaButton.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> CarPlayMediaButton.PAUSE
            else -> null
        }
        fun close() {
            closed = true
            runCatching {
                if (input != null && callback != null) contract?.getMethod("abandonKeysInterception", callbackClass!!)
                    ?.invoke(input, callback)
            }.onFailure { XingruiRuntimeStatus.record("方控", "释放回调失败：${it.cause?.message ?: it.message}") }
            presses.clear()
            callback = null
            input = null
        }
    }
}
