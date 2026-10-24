#!/usr/bin/env python3
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace

def main():
    root=Path(sys.argv[1]); c=root/'common/src/main/java/com/shilapi/xcertplay'
    a=c/'DiPlayActivity.kt'; h=c/'CarPlayHostActivity.kt'; p=c/'ClusterMapPresentation.kt'
    replace(root/'common/build.gradle.kts', '    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")\n', '')
    replace(root/'mobile/build.gradle.kts', 'versionNameSuffix = "-xingrui-r3"', 'versionNameSuffix = "-xingrui-r4"')
    replace(root/'mobile/build.gradle.kts', 'versionCode = 40', 'versionCode = 41')
    replace(p, '    fun setStreamActive(active: Boolean) {', '''    fun xingruiVideoReady() { xingruiDim?.videoReady(true) }

    fun setStreamActive(active: Boolean) {
        if (!active) xingruiDim?.videoReady(false)''')
    sink=root/'shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt'
    replace(sink, '    private val onAudioDiagnostic: (String) -> Unit = {},', '''    private val onAudioDiagnostic: (String) -> Unit = {},
    /** Decoder released its first buffer to a real output Surface; not proof of physical visibility. */
    private val onVideoFirstFrameReleased: (Int) -> Unit = {},''')
    # An alternate CarPlay stream must not consume its one-shot first-frame event on the centre display
    # while the real cluster Presentation is still being created. When cluster output is disabled the host
    # explicitly maps SCREEN_TYPE_ALT to the main Surface, so removing this implicit fallback is safe.
    replace(sink,
        '            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)',
        '''            val targetSurface = surfaces[type] ?: defaultSurface.takeIf { type == MAIN_SCREEN_TYPE }
            val decoder = newVideoDecoder(type, targetSurface, startImmediately = false)''')
    replace(sink, '        report = { videoDiagnosticHandlers[type]?.invoke(it) },', '''        report = {
            if (it == "first frame rendered" && statsLabel == null) onVideoFirstFrameReleased(type)
            videoDiagnosticHandlers[type]?.invoke(it)
        },''')
    replace(h, '            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,', '''            onVideoFirstFrameReleased = { type -> runOnUiThread {
                if (controllerGeneration == restartGeneration && !shuttingDown.get() && type == SCREEN_TYPE_ALT) {
                    Log.i("XingruiCluster", "stream=111 first frame on bound output presentation=${clusterPresentation != null} surfaceValid=${clusterSurface?.isValid}")
                    clusterPresentation?.xingruiVideoReady()
                }
            } },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,''')
    replace(a, '连接 CarPlay 后自动接入原车媒体控制。按键学习用于应用能收到的按键；原车媒体回调不依赖学习。音量仍交给原车。',
        '连接 CarPlay 后申请系统方控回调。若车机拒绝或没有回调，可在适配诊断中查看并导出结果。音量仍交给原车。')
    replace(a, '            card.addView(button("恢复原厂仪表", false)', '''            card.addView(button("适配诊断 / 导出", false) { XingruiDiagnostics.show(this) }, matchButton(6, 56))
            card.addView(button("恢复原厂仪表", false)''')
    replace(a, '    override fun onCreate(savedInstanceState: Bundle?) {', '''    @Deprecated("Platform activity-result bridge for diagnostic document export")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == XingruiDiagnostics.REQUEST && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { XingruiDiagnostics.export(this, it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {''')
    # Copy focused pure-JVM state tests, leaving upstream behavior assertions intact.
    # The Wi-Fi tests mock every address/capability. A host interface supplies only a real name/index;
    # isolated builders can legitimately expose only loopback. No vehicle networking code changes.
    wifi_test=root/'common/src/test/java/com/shilapi/xcertplay/ExistingWifiManagerTest.kt'
    replace(wifi_test, '    private val iface = NetworkInterface.getNetworkInterfaces().toList().first { !it.isLoopback }',
        '''    private val iface = NetworkInterface.getNetworkInterfaces().toList().let { interfaces ->
        interfaces.firstOrNull { !it.isLoopback } ?: interfaces.firstOrNull()
            ?: error("Wi-Fi test requires an interface name and index")
    }''')
    target=root/'common/src/test/java/com/shilapi/xcertplay'
    source=Path(__file__).parent/'tests'
    for f in source.glob('Xingrui*Test.kt'):
        (target/f.name).write_text(f.read_text())

if __name__=='__main__': main()
