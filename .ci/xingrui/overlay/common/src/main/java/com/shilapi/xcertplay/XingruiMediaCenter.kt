package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.dx.stock.ProxyBuilder
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Proxy

/** Registers our own package as an ECARX music source; does not spoof a stock app or key code. */
internal object XingruiMediaCenter {
    private const val TAG = "XingruiMedia"
    private val main = Handler(Looper.getMainLooper())
    private var bridge: Bridge? = null

    fun attach(context: Context, controller: CarPlayController) { main.post {
        if (bridge?.controller === controller) return@post
        bridge?.close()
        bridge = Bridge(context.applicationContext, controller).also { it.start() }
    } }
    fun detach(controller: CarPlayController) { main.post {
        if (bridge?.controller === controller) { bridge?.close(); bridge = null }
    } }
    fun audioActive(active: Boolean) { main.post {
        if (active) bridge?.requestOwnership()
    } }

    fun metadataChanged(controller: CarPlayController) { main.post {
        if (bridge?.controller === controller) bridge?.publishPlayback()
    } }

    private class Bridge(val app: Context, val controller: CarPlayController) {
        @Volatile private var closed = false
        private var api: Any? = null
        private var apiClass: Class<*>? = null
        private var token: Any? = null
        private var music: Any? = null
        private var callback: Any? = null
        private var playback: Any? = null
        private var playbackClass: Class<*>? = null
        private var sourceType = 6 // SOURCE_TYPE_ONLINE; never Bluetooth or USB
        private var wanted = false
        private var claimed = false

        fun start() {
            try {
                val paths = listOf("/system/framework/ecarx.gkui.openapi.impl.jar",
                    "/system/framework/ecarx.adaptapi.impl.jar").filter { File(it).isFile }
                val loader = if (paths.isEmpty()) app.classLoader else
                    PathClassLoader(paths.joinToString(File.pathSeparator), app.classLoader)
                val cls = loader.loadClass("com.ecarx.sdk.mediacenter.MediaCenterAPI")
                val cb = loader.loadClass("com.ecarx.sdk.openapi.ECarXApiClient\$Callback")
                apiClass = cls
                api = checkNotNull(cls.getMethod("get", Context::class.java).invoke(null, app))
                callback = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { proxy, method, args ->
                    when (method.name) {
                        "onAPIReady" -> { main.post {
                            if (!closed) {
                                val ready = args?.firstOrNull() == true
                                Log.i(TAG, "MediaCenter ready=$ready")
                                if (ready) register(loader) else { token = null; claimed = false }
                            }
                        }; null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "XingruiMediaReady"
                        else -> null
                    }
                }
                cls.getMethod("init", Context::class.java, cb).invoke(api, app, callback)
                Log.i(TAG, "MediaCenter init requested package=${app.packageName}")
            } catch (error: Throwable) {
                Log.w(TAG, "MediaCenter unavailable", error.cause ?: error)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun register(loader: ClassLoader) {
            if (token != null || closed) return
            try {
                val client = loader.loadClass("com.ecarx.sdk.mediacenter.MusicClient") as Class<Any>
                val info = loader.loadClass("com.ecarx.sdk.mediacenter.MusicPlaybackInfo") as Class<Any>
                playbackClass = info
                sourceType = runCatching { info.getField("SOURCE_TYPE_ONLINE").getInt(null) }.getOrDefault(6)
                playback = ProxyBuilder.forClass(info).parentClassLoader(loader)
                    .dexCache(app.getDir("xingrui-media-dex", Context.MODE_PRIVATE))
                    .handler { proxy, method, args ->
                        val current = controller.nowPlayingSnapshot()
                        when (method.name) {
                            "getTitle" -> current.title ?: ""
                            "getArtist" -> current.artist ?: ""
                            "getAlbum" -> current.album ?: ""
                            "getDuration" -> current.durationMillis ?: 0L
                            "getPlaybackStatus" -> if (current.playing) 1 else 0
                            "getSourceType" -> sourceType
                            "getPackageName" -> app.packageName
                            "getAppName" -> "DiPlay"
                            else -> ProxyBuilder.callSuper(proxy, method, *(args ?: emptyArray()))
                        }
                    }.build()
                music = ProxyBuilder.forClass(client).parentClassLoader(loader)
                    .dexCache(app.getDir("xingrui-media-dex", Context.MODE_PRIVATE))
                    .handler { proxy, method, args ->
                        val button = when (method.name) {
                            "onNext" -> CarPlayMediaButton.NEXT
                            "onPrevious" -> CarPlayMediaButton.PREVIOUS
                            "onPlay" -> CarPlayMediaButton.PLAY
                            "onPause" -> CarPlayMediaButton.PAUSE
                            else -> null
                        }
                        if (button != null && method.parameterTypes.isEmpty()) {
                            val sent = !closed && !inCall(app) &&
                                runCatching { controller.sendMediaButton(button) }.getOrDefault(false)
                            Log.i(TAG, "OEM ${method.name} -> CarPlay sent=$sent")
                            sent
                        } else {
                            if (method.name == "onMediaCenterFocusChanged") {
                                Log.i(TAG, "OEM media focus=${args?.firstOrNull()}")
                            }
                            when (method.name) {
                                "getMusicPlaybackInfo" -> playback
                                "getMediaSourceTypeList" -> intArrayOf(sourceType)
                                "getCurrentSourceType" -> sourceType
                                "getCurrentProgress" -> controller.nowPlayingSnapshot().elapsedMillis ?: 0L
                                else -> ProxyBuilder.callSuper(proxy, method, *(args ?: emptyArray()))
                            }
                        }
                    }.build()
                token = apiClass!!.getMethod("registerMusic", String::class.java, client)
                    .invoke(api, app.packageName, music)
                Log.i(TAG, "music registration tokenPresent=${token != null}")
                if (token != null) {
                    apiClass!!.getMethod("updateMediaSourceTypeList", Any::class.java, IntArray::class.java)
                        .invoke(api, token, intArrayOf(sourceType))
                    apiClass!!.getMethod("updateCurrentSourceType", Any::class.java, Int::class.javaPrimitiveType)
                        .invoke(api, token, sourceType)
                    publishPlayback()
                }
                if (wanted) requestOwnership()
            } catch (error: Throwable) { Log.w(TAG, "music registration failed", error.cause ?: error) }
        }

        fun publishPlayback() {
            if (closed || token == null || playback == null) return
            try {
                apiClass!!.getMethod("updateMusicPlaybackState", Any::class.java, playbackClass!!)
                    .invoke(api, token, playback)
            } catch (error: Exception) { Log.w(TAG, "playback update failed", error.cause ?: error) }
        }

        fun requestOwnership() {
            if (closed) return
            wanted = true
            val registered = token ?: return
            if (claimed) return
            try {
                val result = apiClass!!.getMethod("requestPlay", Any::class.java).invoke(api, registered)
                claimed = result != false
                Log.i(TAG, "requestPlay result=$result; waiting for OEM callbacks")
            } catch (error: Exception) { Log.w(TAG, "requestPlay failed", error.cause ?: error) }
        }

        fun close() {
            closed = true
            token?.let { registered -> runCatching {
                apiClass?.getMethod("unregister", Any::class.java)?.invoke(api, registered)
            }.onFailure { Log.w(TAG, "unregister failed", it) } }
            runCatching { apiClass?.getMethod("release")?.invoke(api) }
            token = null
            music = null
            playback = null
            callback = null
            Log.i(TAG, "music source released")
        }
    }
}
