package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/** Android preferences and controller boundary; sessions and reflection adapters are contract-tested. */
internal object XingruiMediaCenter {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var controller: CarPlayController? = null
    private var session: XingruiWheelSession? = null
    private val mapping = linkedMapOf(
        XingruiWheelRole.NEXT to WheelZoomSettings.Role.MEDIA_NEXT,
        XingruiWheelRole.PREVIOUS to WheelZoomSettings.Role.MEDIA_PREVIOUS,
        XingruiWheelRole.PLAY_PAUSE to WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
        XingruiWheelRole.SIRI to WheelZoomSettings.Role.SIRI)
    private fun initialize(context: Context): Boolean {
        if (session != null) return true
        val app = context.applicationContext
        return runCatching {
            val port = XingruiReflectiveInput(app, Context::class.java)
            session = XingruiWheelSession(port,
                XingruiScheduler { delay, task -> main.postDelayed({ task() }, delay) },
                learned = { mapping.filterKeys { it != XingruiWheelRole.SIRI || WheelZoomSettings.siriKey(app) }
                    .mapNotNull { (role, stored) -> WheelZoomSettings.key(app, stored)?.code?.let { role to it } }.toMap() },
                save = { role, code ->
                    if (role == XingruiWheelRole.SIRI) WheelZoomSettings.setSiriKey(app, true)
                    WheelZoomSettings.assign(app, mapping.getValue(role), WheelKey(code, 0, "ecarx-sdk"))
                },
                send = { role, code ->
                    val active = controller
                    if (active == null) false else if (role == XingruiWheelRole.SIRI) active.requestSiri()
                    else {
                        val action = when (role) {
                            XingruiWheelRole.NEXT -> XingruiKeyAction.NEXT
                            XingruiWheelRole.PREVIOUS -> XingruiKeyAction.PREVIOUS
                            XingruiWheelRole.PLAY_PAUSE -> XingruiKeyAction.PLAY_PAUSE
                            else -> XingruiKeyRouter.defaultAction(code)
                        }
                        val button = when (action) {
                            XingruiKeyAction.NEXT -> CarPlayMediaButton.NEXT
                            XingruiKeyAction.PREVIOUS -> CarPlayMediaButton.PREVIOUS
                            XingruiKeyAction.PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
                            XingruiKeyAction.PLAY -> CarPlayMediaButton.PLAY
                            XingruiKeyAction.PAUSE -> CarPlayMediaButton.PAUSE
                            null -> null
                        }
                        button != null && active.sendMediaButton(button)
                    }
                },
                inCall = { inCall(app) },
                report = { area, message -> XingruiRuntimeStatus.recordAsync(area, message) })
            true
        }.getOrElse {
            XingruiRuntimeStatus.record("方控", "接口初始化失败：${it.cause ?: it}")
            false
        }
    }
    fun attach(context: Context, next: CarPlayController) { main.post {
        controller = next
        if (initialize(context)) session?.playback(true)
    } }
    fun detach(expected: CarPlayController) { main.post {
        if (controller === expected) { controller = null; session?.playback(false) }
    } }
    fun learn(context: Context, role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        val selected = mapping.entries.firstOrNull { it.value == role }?.key ?: return false
        if (!initialize(context)) return false
        return session!!.learn(selected, WheelKeyService.LEARNING_TIMEOUT_MILLIS,
            done = { done(WheelKey(it, 0, "ecarx-sdk")) }, cancel = cancelled,
            refused = { refused(mapping.getValue(it)) })
    }
    fun cancelLearning() { session?.cancelLearning() }
    fun settingsChanged() { main.post { session?.settingsChanged() } }
    fun audioActive(active: Boolean) = Unit
    fun metadataChanged(controller: CarPlayController) = Unit
}
