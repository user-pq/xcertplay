#!/usr/bin/env python3
"""Fixes driven by the 2026-10-18 E02 test; applied after all product patches."""
from pathlib import Path
import sys

def replace(p, old, new):
    s=p.read_text()
    if s.count(old)!=1: raise SystemExit(f'Expected one anchor in {p}: {old[:90]} ({s.count(old)})')
    p.write_text(s.replace(old,new,1))
    print('[ok]',p.name,old.splitlines()[0][:65])

def main():
    r=Path(sys.argv[1]); c=r/'common/src/main/java/com/shilapi/xcertplay'
    a=c/'DiPlayActivity.kt'; h=c/'CarPlayHostActivity.kt'
    replace(a, '        right.addView(homeButton(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(height = if (compact) 54 else 68))\n        if (!compact) right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })\n        else right.addView(space(16))\n', '')
    # The simplified settings removed this toggle while upstream defaults auto_enable to false.
    replace(a, '            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(10, 58))', '''            toggle(card, "自动开启车机热点", "打开应用时开启已保存的车机热点。",
                CarHotspotSettings.enabled(this)) { enabled ->
                CarHotspotSettings.setEnabled(this, enabled)
                startupHotspotCancelled = false
                if (enabled) {
                    if (!CarHotspotTethering.permitted(this)) {
                        hotspotStartupResult = CarHotspotTethering.Result.PERMISSION_REQUIRED
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            android.net.Uri.parse("package:$packageName")))
                    } else startCarHotspotOnLaunch()
                }
            }
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(10, 58))''')
    replace(a, '        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false', '''        val xingruiPrefs = getSharedPreferences("xingrui_runtime", Context.MODE_PRIVATE)
        if (!xingruiPrefs.getBoolean("hotspot_v1", false)) {
            CarHotspotSettings.setEnabled(this, true)
            xingruiPrefs.edit().putBoolean("hotspot_v1", true).apply()
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false''')
    replace(a, '        super.onResume()\n', '''        super.onResume()
        startupHotspotCancelled = false
''')
    # Attempt startup even if the SSID has not yet been captured. Existing tethering config is preserved.
    replace(a, '''    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())''', '''    private fun startupHotspotEligible(): Boolean =
        !CarPlayBackgroundSession.hasSession() && CarHotspotSettings.shouldEnable(this,
            AirPlayPersistence.loadWirelessEnabled(this), AirPlayPersistence.loadWirelessHotspotMode(this))''')
    replace(h, '    private fun ensureClusterPresentation() {', '    private var xingruiSpeedPermissionAsked = false\n\n    private fun ensureClusterPresentation() {\n        if (AirPlayPersistence.loadClusterMapEnabled(this) && !xingruiSpeedPermissionAsked) {\n            xingruiSpeedPermissionAsked = true\n            val permission = "android.car.permission.CAR_SPEED"\n            val info = runCatching { packageManager.getPermissionInfo(permission, 0) }.getOrNull()\n            if (info != null && (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) ==\n                android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&\n                checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n                requestPermissions(arrayOf(permission), 7902)\n                Log.i("XingruiVHAL", "runtime CAR_SPEED authorization requested")\n            }\n        }')
    replace(h, '    override fun dispatchKeyEvent(event: KeyEvent): Boolean {', '    override fun dispatchKeyEvent(event: KeyEvent): Boolean {\n        // 305 is confirmed on this E02; consume its release so the native BT player cannot receive it.\n        if (event.keyCode == 305 && activeAirPlaySession != null && !menuOpen && !inCall(this) &&\n            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {\n            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {\n                appendLog("Xingrui wheel NEXT -> CarPlay sent=${controller?.sendMediaButton(CarPlayMediaButton.NEXT) == true}")\n            }\n            return true\n        }')
    replace(h, '        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }',
        '        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }\n        xingruiAudioGuard.stop()')
    replace(h, '        if (reconnectScheduled) return',
        '        if (reconnectScheduled) return\n        xingruiAudioGuard.stop()')
    replace(h, '    override fun onDestroy() {\n', '''    private val xingruiAudioGuard by lazy { XingruiBluetoothAudioGuard(this) }

    override fun onDestroy() {
        xingruiAudioGuard.close()
''')
    replace(h, '            onAudioDiagnostic = { message ->\n', '''            onAudioDiagnostic = { message ->
                if (message.startsWith("XingruiAudioVerified ")) runOnUiThread {
                    if (controllerGeneration == restartGeneration && activeAirPlaySession != null &&
                        wirelessEnabled && !shuttingDown.get()) {
                        xingruiAudioGuard.begin(DiPlayPreferences.phoneAddress(this@CarPlayHostActivity), "local media PCM progressed")
                    }
                }
''')
    replace(h, '                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread', '                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread\n                    // Do not wait for PCM: iOS may not send it while A2DP still owns its music route.\n                    mainHandler.postDelayed({\n                        if (wirelessEnabled && controllerGeneration == restartGeneration &&\n                            activeAirPlaySession === session && sink != null && !shuttingDown.get()) {\n                            xingruiAudioGuard.begin(DiPlayPreferences.phoneAddress(this@CarPlayHostActivity),\n                                "wireless session and main video verified")\n                        }\n                    }, 2000L)')
    replace(h, '                    activeAirPlaySession = null\n                    CarPlayBackgroundSession.active = false', '''                    xingruiAudioGuard.stop()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false''')
    replace(h, '                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")', '''                Log.i("XingruiCluster", "stream=111 active=$active presentation=${clusterPresentation != null} surfaceValid=${clusterSurface?.isValid}")
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")''')
    presentation=c/'ClusterMapPresentation.kt'
    replace(presentation, '    // The placeholder until the map arrives: a small spinner.', '    private var xingruiDim: XingruiDimProjection? = null\n\n    override fun onStart() {\n        super.onStart()\n        if (XingruiClusterProbe.isCandidate(display) && AirPlayPersistence.loadClusterMapEnabled(context)) {\n            xingruiDim = XingruiDimProjection(context, display.displayId).also { it.start() }\n        }\n    }\n\n    override fun onStop() {\n        xingruiDim?.close()\n        xingruiDim = null\n        super.onStop()\n    }\n\n    // The placeholder until the map arrives: a small spinner.')
    media=r/'shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt'
    replace(media, '        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }', '''        val route = currentTrack?.routedDevice
        if (!force && mappedChannel == AudioChannel.MEDIA && playbackStarted &&
            (playbackAdvanceFrames ?: 0L) > 0L && writeErrorsThisWindow == 0 &&
            route != null && route.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            route.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            report("XingruiAudioVerified audioType=${format.audioType} routeType=${route.type} head=$playbackHeadFrames")
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }''')
    replace(r/'mobile/build.gradle.kts', 'applicationIdSuffix = ".hudtest"', 'applicationIdSuffix = ".xingrui"')
    replace(r/'mobile/build.gradle.kts', 'versionNameSuffix = "-hud-test"', 'versionNameSuffix = "-xingrui-r2"')
    manifest=c.parents[3]/'AndroidManifest.xml'
    # c is .../common/src/main/java/com/shilapi/xcertplay; use explicit path.
    manifest=r/'common/src/main/AndroidManifest.xml'
    replace(manifest, '    <application>', '''    <uses-permission android:name="android.car.permission.CAR_SPEED" />
    <uses-permission android:name="android.car.permission.CAR_POWERTRAIN" />
    <uses-permission android:name="android.car.permission.CAR_ENGINE_DETAILED" />
    <uses-permission android:name="android.cevt.dimsupportservice.permission.DIM_MESSAGING" />
    <uses-permission android:name="ecarx.oem.permission.OPENAPI_NAVI_PERMISSION" />
    <application>''')
    # Permission declarations do not imply that the E02 grants them to this app.
    replace(manifest, '    <application>', '''    <application>
        <uses-library android:name="android.car" android:required="false" />''')

if __name__=='__main__': main()
