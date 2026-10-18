package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** One ECARX callback owns learning and playback. Learning does not need an active phone. */
internal object XingruiMediaCenter {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var controller: CarPlayController? = null
    @Volatile private var app: Context? = null
    private var input: Any? = null
    private var callback: Any? = null
    private var contract: Class<*>? = null
    private var callbackClass: Class<*>? = null
    private val presses = XingruiWheelPresses()
    private val learningReleases = mutableSetOf<Int>()
    private data class Learning(val role: WheelZoomSettings.Role, val cancel: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit, val done: (WheelKey) -> Unit)
    @Volatile private var learning: Learning? = null
    private val timeout = Runnable { cancelLearning() }
    private val roles = listOf(WheelZoomSettings.Role.MEDIA_NEXT, WheelZoomSettings.Role.MEDIA_PREVIOUS,
        WheelZoomSettings.Role.MEDIA_PLAY_PAUSE, WheelZoomSettings.Role.SIRI)

    fun attach(context: Context, next: CarPlayController) { main.post {
        controller = next
        initialize(context)
        register()
    } }
    fun detach(expected: CarPlayController) { main.post {
        if (controller === expected) { controller = null; presses.clear(); register() }
    } }
    fun settingsChanged() { main.post { register() } }
    fun audioActive(active: Boolean) = Unit
    fun metadataChanged(controller: CarPlayController) = Unit

    fun learn(context: Context, role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        if (role !in roles || inCall(context)) return false
        cancelLearning()
        if (!initialize(context)) return false
        learning = Learning(role, cancelled, refused, done)
        if (!register()) { learning = null; return false }
        main.postDelayed(timeout, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        XingruiRuntimeStatus.record("方控学习", "等待真实按键：$role")
        return true
    }
    fun cancelLearning() {
        val pending = synchronized(this) { learning.also { learning = null } }
        main.removeCallbacks(timeout)
        if (pending != null) { pending.cancel(); register() }
    }
    private fun initialize(context: Context): Boolean {
        app = context.applicationContext
        if (input != null) return true
        return runCatching {
            val cls = Class.forName("com.ecarx.xui.adaptapi.input.Input")
            val cb = Class.forName("com.ecarx.xui.adaptapi.input.IKeyCallback")
            contract = cls; callbackClass = cb
            input = checkNotNull(cls.getMethod("create", Context::class.java).invoke(null, app))
            callback = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { proxy, method, args ->
                when (method.name) {
                    "onKeyPressed" -> (args?.firstOrNull() as? Int)?.let(::down) ?: false
                    "onKeyReleased" -> (args?.firstOrNull() as? Int)?.let { code -> synchronized(this) {
                        learningReleases.remove(code) || presses.up(code)
                    } } ?: false
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "XingruiInputCallback"
                    else -> null
                }
            }
            true
        }.getOrElse {
            XingruiRuntimeStatus.record("方控", "初始化失败：${it.cause ?: it}")
            input = null; false
        }
    }
    private fun candidates(): IntArray {
        val context = app ?: return intArrayOf()
        val codes = mutableSetOf(305, 87, 88, 85, 126, 127)
        roles.forEach { role -> WheelZoomSettings.key(context, role)?.code?.let(codes::add) }
        if (learning != null) {
            // Discover named SDK constants, not guessed vendor numeric mappings.
            runCatching { Class.forName("com.ecarx.xui.adaptapi.input.KeyCode").fields.forEach { field ->
                if (field.type == Int::class.javaPrimitiveType && Modifier.isStatic(field.modifiers) &&
                    listOf("MEDIA", "SEEK", "PLAY", "PAUSE", "NEXT", "PREVIOUS", "VOICE", "SPEECH", "VR_").any {
                        field.name.contains(it, ignoreCase = true)
                    }) codes.add(field.getInt(null))
            } }.onFailure { XingruiRuntimeStatus.record("方控学习", "SDK 按键常量读取失败：${it.cause ?: it}") }
            codes.add(KeyEvent.KEYCODE_VOICE_ASSIST)
        }
        // Volume, home, power and phone call controls remain with the vehicle.
        codes.removeAll(setOf(0, 3, 4, 5, 6, 24, 25, 26, 164, 301, 302))
        return codes.sorted().toIntArray()
    }
    private fun register(): Boolean {
        if (input == null || callback == null) return false
        return runCatching {
            contract!!.getMethod("abandonKeysInterception", callbackClass!!).invoke(input, callback)
            val codes = if (controller != null || learning != null) candidates() else intArrayOf()
            if (codes.isNotEmpty()) {
                val result = contract!!.getMethod("requestKeysInterception", IntArray::class.java, callbackClass!!)
                    .invoke(input, codes, callback)
                XingruiRuntimeStatus.record("方控", "请求=${codes.joinToString()} 返回=${(result as? IntArray)?.joinToString()}; 等待实际回调")
            }
            true
        }.getOrElse { XingruiRuntimeStatus.record("方控", "注册失败：${it.cause ?: it}"); false }
    }
    @Synchronized private fun down(code: Int): Boolean {
        if (code in learningReleases) return true
        val context = app ?: return false
        if (inCall(context)) return false
        val pending = learning
        if (pending != null) {
            learning = null
            learningReleases.add(code)
            main.removeCallbacks(timeout)
            main.post {
                val key = WheelKey(code, 0, "ecarx-sdk")
                val conflict = roles.firstOrNull { it != pending.role &&
                    (it != WheelZoomSettings.Role.SIRI || WheelZoomSettings.siriKey(context)) &&
                    WheelZoomSettings.key(context, it)?.code == code }
                if (conflict != null) pending.refused(conflict) else {
                    if (pending.role == WheelZoomSettings.Role.SIRI) WheelZoomSettings.setSiriKey(context, true)
                    WheelZoomSettings.assign(context, pending.role, key)
                    XingruiRuntimeStatus.record("方控学习", "已保存 ${pending.role}=$code")
                    pending.done(key)
                }
                register()
            }
            return true
        }
        return presses.down(code) send@ {
            val active = controller ?: return@send false
            val role = roles.firstOrNull { (it != WheelZoomSettings.Role.SIRI || WheelZoomSettings.siriKey(context)) &&
                WheelZoomSettings.key(context, it)?.code == code }
            val button = when (role) {
                WheelZoomSettings.Role.MEDIA_NEXT -> CarPlayMediaButton.NEXT
                WheelZoomSettings.Role.MEDIA_PREVIOUS -> CarPlayMediaButton.PREVIOUS
                WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
                else -> when (code) { 305, 87 -> CarPlayMediaButton.NEXT; 88 -> CarPlayMediaButton.PREVIOUS
                    85 -> CarPlayMediaButton.PLAY_PAUSE; 126 -> CarPlayMediaButton.PLAY; 127 -> CarPlayMediaButton.PAUSE; else -> null }
            }
            val sent = runCatching { if (role == WheelZoomSettings.Role.SIRI) active.requestSiri()
                else button != null && active.sendMediaButton(button) }.getOrDefault(false)
            XingruiRuntimeStatus.record("方控", "收到按键=$code role=$role CarPlay发送=$sent")
            sent
        }
    }
}
