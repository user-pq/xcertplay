#!/usr/bin/env python3
"""R5: unified ECARX learning, physical source request and persistent evidence."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace

def main():
    root = Path(sys.argv[1])
    c = root / 'common/src/main/java/com/shilapi/xcertplay'
    a = c / 'DiPlayActivity.kt'
    build = root / 'mobile/build.gradle.kts'
    replace(build, 'versionNameSuffix = "-xingrui-r4"', 'versionNameSuffix = "-xingrui-r5"')
    replace(build, 'versionCode = 41', 'versionCode = 42')
    manifest = root / 'mobile/src/main/AndroidManifest.xml'
    replace(manifest, 'android:label="@string/app_name"', 'android:label="DiPlay 星瑞 R5"')
    manifest = root / 'common/src/main/AndroidManifest.xml'
    replace(manifest, '    <application>', '''    <uses-permission android:name="android.permission.READ_LOGS" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.BLUETOOTH_PRIVILEGED" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.TETHER_PRIVILEGED" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.car.permission.CAR_VENDOR_EXTENSION" />
    <uses-permission android:name="android.car.permission.CAR_CONTROL_AUDIO_VOLUME" />
    <application>''')
    replace(a, '        PhoneAudioRoute.attach(applicationContext)', '''        PhoneAudioRoute.attach(applicationContext)
        XingruiRuntimeStatus.attach(this)
        XingruiRuntimeStatus.record("启动", "R5 Activity opened uid=${android.os.Process.myUid()}")''')
    replace(a, '            val started = WheelKeyService.learn(role, cancelled, refused)', '''            cancelKeyLearning()
            val started = XingruiMediaCenter.learn(this, role, cancelled, refused) { key -> runOnUiThread {
                assign.text = current(key)
                applyPendingAppearanceRender()
            } } || WheelKeyService.learn(role, cancelled, refused)''')
    replace(a, '    private fun cancelKeyLearning() {', '''    private fun cancelKeyLearning() {
        XingruiMediaCenter.cancelLearning()''')
    replace(a, '    override fun onStop() {\n        cancelUsbPermissionSetup()', '''    override fun onStop() {
        XingruiMediaCenter.cancelLearning()
        cancelUsbPermissionSetup()''')
    replace(a, '连接 CarPlay 后申请系统方控回调。若车机拒绝或没有回调，可在适配诊断中查看并导出结果。音量仍交给原车。',
        '点击上一曲、下一曲或播放/暂停，再按对应方向盘键，页面显示编号后即已保存。请停车操作；音量仍由原车控制。')
    replace(a, '            wheelKeyServiceControls(card)\n            wheelKeyAssignButton(card, WheelZoomSettings.Role.MEDIA_PREVIOUS',
        '            wheelKeyAssignButton(card, WheelZoomSettings.Role.MEDIA_PREVIOUS')
    replace(a, '                log = { Log.i("DiPlay-Hotspot", it) },',
        '                log = { Log.i("DiPlay-Hotspot", it); XingruiRuntimeStatus.record("热点", it) },')
    replace(c / 'WheelKeyService.kt', '        fun settingsChanged() { running?.onMain { settingsChanged() } }', '''        fun settingsChanged() {
            XingruiMediaCenter.settingsChanged()
            running?.onMain { settingsChanged() }
        }''')
    boot = c / 'BootReceiver.kt'
    replace(boot, '        // Starts the watcher', '''        XingruiRuntimeStatus.attach(context)
        XingruiRuntimeStatus.record("启动", "BOOT_COMPLETED received")
        // Starts the watcher''')
    replace(boot, '            Log.i(TAG, "boot activity launch requested")', '''            Log.i(TAG, "boot activity launch requested")
            XingruiRuntimeStatus.record("启动", "已请求开机启动 Activity")''')
    replace(boot, '            StartupDiagnosticSnapshot.launchResult(context, error)', '''            StartupDiagnosticSnapshot.launchResult(context, error)
            XingruiRuntimeStatus.record("启动", "开机启动失败：$error")''')
    host = c / 'CarPlayHostActivity.kt'
    replace(host, '                if (message.startsWith("XingruiAudioVerified ")) runOnUiThread {', '''                if (message.startsWith("XingruiAudioVerified ")) runOnUiThread {
                    XingruiRuntimeStatus.record("音频输出", message)''')
    print('Xingrui R5 source/learning patch applied')

if __name__ == '__main__': main()
