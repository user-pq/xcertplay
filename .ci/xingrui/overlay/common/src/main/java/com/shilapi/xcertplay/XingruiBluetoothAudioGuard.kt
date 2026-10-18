package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Release only the selected iPhone's A2DP sink, after local CarPlay PCM playback progresses.
 * Never disable the adapter: RFCOMM/iAP2, pairing and other devices remain available. */
internal class XingruiBluetoothAudioGuard(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var profile: BluetoothProfile? = null
    private var address: String? = null
    private var pending = false
    private var closed = false
    private var failures = mutableSetOf<String>()
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
            if (closed) { adapter?.closeProfileProxy(id, proxy); return }
            profile = proxy
            handler.removeCallbacks(check)
            handler.post(check)
        }
        override fun onServiceDisconnected(id: Int) { profile = null; pending = false }
    }
    private val check = object : Runnable {
        override fun run() {
            if (closed || address == null) return
            val proxy = profile
            try {
                if (proxy == null && !pending) {
                    pending = adapter?.getProfileProxy(context.applicationContext, listener, A2DP_SINK) == true
                    Log.i(TAG, "A2DP sink proxy requested=$pending")
                }
                proxy?.connectedDevices?.filter { it.address.equals(address, true) }?.forEach { phone ->
                    val accepted = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        .invoke(proxy, phone) == true
                    Log.i(TAG, "selected iPhone A2DP disconnect accepted=$accepted; verify next profile state")
                }
            } catch (error: Exception) {
                val cause = error.cause ?: error
                if (failures.add(cause.javaClass.name)) Log.w(TAG,
                    "A2DP release unavailable ${cause.javaClass.simpleName}: ${cause.message}", cause)
            }
            handler.postDelayed(this, 3000L)
        }
    }
    fun onVerifiedPlayback(phoneAddress: String?) {
        handler.post {
            if (closed || phoneAddress == null || address == phoneAddress) return@post
            address = phoneAddress
            Log.i(TAG, "CarPlay local media playback verified; guarding selected iPhone A2DP")
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
