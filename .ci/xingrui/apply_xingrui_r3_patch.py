#!/usr/bin/env python3
"""R3: E02 log-driven DIM connection, OEM media source, startup settings."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace

def main():
    root=Path(sys.argv[1]); c=root/'common/src/main/java/com/shilapi/xcertplay'
    a=c/'DiPlayActivity.kt'; h=c/'CarPlayHostActivity.kt'; m=c/'CarPlayMediaKeys.kt'
    replace(root/'common/build.gradle.kts', 'dependencies {', 'dependencies {\n    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")')
    replace(m, '        GeelyWheelKeys.start(context)', '        GeelyWheelKeys.start(context)\n        XingruiMediaCenter.attach(context, next)')
    replace(m, '        GeelyWheelKeys.stop()', '        GeelyWheelKeys.stop()\n        XingruiMediaCenter.detach(expected)')
    replace(m, '    fun onMediaAudioChanged(active: Boolean) {', '    fun onMediaAudioChanged(active: Boolean) {\n        XingruiMediaCenter.audioActive(active)')
    replace(a, '            toggle(card, "自动开启车机热点",', '''            toggle(card, "开机自动启动", "车机完整开机后打开 DiPlay，再按自动连接设置连接 iPhone。",
                AirPlayPersistence.loadAutoStartOnBoot(this)) { enabled ->
                AirPlayPersistence.saveAutoStartOnBoot(this, enabled)
            }
            toggle(card, "自动开启车机热点",''')
    replace(a, '        if (!xingruiPrefs.getBoolean("hotspot_v1", false)) {', '''        if (!xingruiPrefs.getBoolean("boot_r3", false)) {
            AirPlayPersistence.saveAutoStartOnBoot(this, true)
            xingruiPrefs.edit().putBoolean("boot_r3", true).apply()
        }
        if (!xingruiPrefs.getBoolean("hotspot_v1", false)) {''')
    replace(c/'BootReceiver.kt', '        if (!launchEnabled) return', '''        Log.i(TAG, "BOOT_COMPLETED received enabled=$launchEnabled package=${context.packageName}")
        if (!launchEnabled) return''')
    replace(c/'BootReceiver.kt', '            StartupDiagnosticSnapshot.launchResult(context)', '            StartupDiagnosticSnapshot.launchResult(context)\n            Log.i(TAG, "boot activity launch requested")')
    replace(h, '    override fun dispatchKeyEvent(event: KeyEvent): Boolean {', '''    private val xingruiLearnedPresses = mutableSetOf<Triple<Int, Int, Int>>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val xingruiPhysical = Triple(event.deviceId, event.keyCode, event.scanCode)
        if (event.action == KeyEvent.ACTION_UP && xingruiLearnedPresses.remove(xingruiPhysical)) return true
        if (event.action == KeyEvent.ACTION_DOWN && xingruiPhysical in xingruiLearnedPresses) return true
        if (!menuOpen && activeAirPlaySession != null && !inCall(this) && event.action == KeyEvent.ACTION_DOWN) {
            val key = WheelKey.of(event)
            val learnedButton = when {
                WheelZoomSettings.isMediaNextKey(this, key) -> CarPlayMediaButton.NEXT
                WheelZoomSettings.isMediaPreviousKey(this, key) -> CarPlayMediaButton.PREVIOUS
                WheelZoomSettings.isMediaPlayPauseKey(this, key) -> CarPlayMediaButton.PLAY_PAUSE
                else -> null
            }
            if (learnedButton != null && event.repeatCount == 0) {
                val sent = controller?.sendMediaButton(learnedButton) == true
                appendLog("Xingrui learned foreground key=${event.keyCode} button=$learnedButton sent=$sent")
                if (sent) { xingruiLearnedPresses.add(xingruiPhysical); return true }
            }
        }''')
    replace(a, '点学习后按一下方向盘按键。已学习的切歌键会直接发送给 CarPlay，避免走蓝牙 AVRCP；音量仍交给原车。',
        '连接 CarPlay 后自动接入原车媒体控制。按键学习用于应用能收到的按键；原车媒体回调不依赖学习。音量仍交给原车。')
    replace(root/'mobile/build.gradle.kts', 'versionNameSuffix = "-xingrui-r2"', 'versionNameSuffix = "-xingrui-r3"')
    # v0.2.20 uses a shared versionCode; override only this dedicated mobile variant.
    replace(root/'mobile/build.gradle.kts', 'versionCode = 39', 'versionCode = 40')

if __name__=='__main__': main()
