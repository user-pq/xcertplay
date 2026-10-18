package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Release only the selected iPhone's A2DP sink, after a verified wireless session renders its first main-screen frame.
 * Never disable the adapter: RFCOMM/iAP2, pairing and other devices remain available. */
internal class XingruiBluetoothAudioGuard(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var profile: BluetoothProfile? = null
    private var address: String? = null
    private var pending = false
    private var closed = false
    private var lastStatus: String? = null
    private fun status(message: String) {
        if (lastStatus != message) { lastStatus = message; XingruiRuntimeStatus.record("蓝牙媒体", message) }
    }
    private var failures = mutableSetOf<String>()
    private val listener: BluetoothProfile.ServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
            if (closed) { adapter?.closeProfileProxy(id, proxy); return }
            profile = proxy
            handler.removeCallbacks(check)
            handler.post(check)
        }
        override fun onServiceDisconnected(id: Int) { profile = null; pending = false }
    }
    private val check: Runnable = object : Runnable {
        override fun run() {
            if (closed || address == null) return
            val proxy = profile
            try {
                if (proxy == null && !pending) {
                    pending = adapter?.getProfileProxy(context.applicationContext, listener, A2DP_SINK) == true
                    Log.i(TAG, "A2DP sink proxy requested=$pending")
                    status("A2DP 接收服务请求=$pending")
                }
                val phones = proxy?.connectedDevices?.filter { it.address.equals(address, true) }
                if (phones != null && phones.isEmpty()) status("所选 iPhone 的 A2DP 媒体通道已断开")
                phones?.forEach { phone ->
                    val accepted = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        .invoke(proxy, phone) == true
                    Log.i(TAG, "selected iPhone A2DP disconnect accepted=$accepted; verify next profile state")
                    status("A2DP 断开请求=$accepted，等待状态确认")
                }
            } catch (error: Exception) {
                val cause = error.cause ?: error
                status("断开失败：${cause.javaClass.simpleName}: ${cause.message}")
                if (failures.add(cause.javaClass.name)) Log.w(TAG,
                    "A2DP release unavailable ${cause.javaClass.simpleName}: ${cause.message}", cause)
            }
            handler.postDelayed(this, 3000L)
        }
    }
    fun begin(phoneAddress: String?, evidence: String) {
        handler.post {
            if (closed) return@post
            if (phoneAddress == null) { status("无线已连接，但未取得所选 iPhone 蓝牙地址，未执行断开"); return@post }
            if (address == phoneAddress) return@post
            address = phoneAddress
            Log.i(TAG, "$evidence; guarding selected iPhone A2DP; PCM output is verified separately")
            handler.post(check)
        }
    }
    fun stop() {
        handler.post {
            address = null
            handler.removeCallbacks(check)
            // No connection policies or pairing are changed, so the next bootstrap can reconnect.
        }
    }
    fun close() {
        closed = true
        address = null
        handler.removeCallbacksAndMessages(null)
        profile?.let { adapter?.closeProfileProxy(A2DP_SINK, it) }
        profile = null
    }
    companion object {
        private const val A2DP_SINK = 11 // Android Automotive receiver, not phone-side A2DP (2).
        private const val TAG = "XingruiAudioRoute"
    }
}
