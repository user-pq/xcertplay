#!/usr/bin/env python3
"""R6.4: harden Xingrui instrument-cluster handoff and validate iPhone cluster content."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace
from apply_xingrui_fragrance_patch import main as apply_fragrance_patch


def main():
    root = Path(sys.argv[1])
    source = root / 'common/src/main/java/com/shilapi/xcertplay'
    presentation = source / 'ClusterMapPresentation.kt'
    # The Xingrui host auto-enables a real DashboardMapDisplay even when the legacy map preference is off.
    # DIM ownership must be created under the same physical-display decision or stream 111 can render with
    # no corresponding presentation switch request.
    replace(presentation,
        '        if (XingruiClusterProbe.isCandidate(display) && AirPlayPersistence.loadClusterMapEnabled(context)) {',
        '        if (XingruiClusterProbe.isCandidate(display)) {')
    replace(presentation, '''    override fun onStart() {
        super.onStart()''', '''    override fun onStart() {
        super.onStart()
        xingruiWindowSnapshot("onStart")''')
    replace(presentation, '''    override fun onStop() {
        xingruiDim?.close()''', '''    override fun onStop() {
        xingruiWindowSnapshot("onStop-before-release")
        xingruiDim?.close()''')
    replace(presentation, '''    fun xingruiVideoReady() { xingruiDim?.videoReady(true) }''', '''    fun xingruiWindowSnapshot(reason: String) {
        if (!XingruiClusterProbe.isCandidate(display)) return
        val size = sizeOf(display)
        XingruiRuntimeStatus.record("仪表窗口", "$reason isShowing=$isShowing " +
            "displayId=${display.displayId} displayName=${display.name} displayState=${display.state} " +
            "displayValid=${display.isValid} flags=${display.flags} size=${size.x}x${size.y} " +
            "surfaceValid=${outputSurface?.isValid} streamActive=$streamActive mapVisible=$mapVisible " +
            "windowAlpha=${window?.attributes?.alpha}；仅软件窗口状态，不代表实屏可见")
    }

    fun xingruiVideoReady() {
        xingruiWindowSnapshot("stream111-first-frame-released-to-surface")
        xingruiDim?.videoReady(true)
    }''')
    replace(presentation, '''        if (streamActive == active) return
        // Update before cancelling''', '''        if (streamActive == active) return
        xingruiWindowSnapshot("streamActive-changing-to-$active")
        // Update before cancelling''')
    replace(presentation, '''                    outputSurface = holder.surface
                    onSurface(holder.surface)''', '''                    outputSurface = holder.surface
                    xingruiWindowSnapshot("surfaceCreated")
                    onSurface(holder.surface)''')
    replace(presentation, '''                    outputSurface = null
                }
            })''', '''                    outputSurface = null
                    xingruiWindowSnapshot("surfaceDestroyed")
                }
            })''')

    host = source / 'CarPlayHostActivity.kt'
    # Existing installs can retain MAP in SharedPreferences. Migrate once so this test build actually
    # requests the iPhone's combined instrument-cluster UI rather than only the map URL.
    replace(host, '''        val xingruiPrefs = getSharedPreferences("xingrui_runtime", Context.MODE_PRIVATE)
        if (!xingruiPrefs.getBoolean("hotspot_v1", false)) {''', '''        val xingruiPrefs = getSharedPreferences("xingrui_runtime", Context.MODE_PRIVATE)
        if (!xingruiPrefs.getBoolean("cluster_instruments_v1", false)) {
            AirPlayPersistence.saveClusterContent(this, CarPlayClusterDisplay.Content.INSTRUMENTS)
            xingruiPrefs.edit().putBoolean("cluster_instruments_v1", true).apply()
            Log.i("XingruiCluster", "cluster content migrated to INSTRUMENTS url=${CarPlayClusterDisplay.Content.INSTRUMENTS.url}")
        }
        if (!xingruiPrefs.getBoolean("hotspot_v1", false)) {''')
    replace(host, '''            presentation.show()
            clusterPresentation = presentation''', '''            presentation.show()
            presentation.xingruiWindowSnapshot("show-return")
            clusterPresentation = presentation''')
    replace(host, '''                presentation.show()
                appendLog("Cluster map: retained layer''', '''                presentation.show()
                presentation.xingruiWindowSnapshot("retained-layer-show-return")
                appendLog("Cluster map: retained layer''')
    replace(host, '''"SDK释放=${result.sdkReleased} 原车导航恢复=${result.nativeRestored} 超时=${result.timedOut}；实屏待确认"''',
        '''"SDK释放确认=${if (result.sdkReleased) "已确认" else "未获确认（含不可读状态）"} 原车导航恢复=${result.nativeRestored} 超时=${result.timedOut}；实屏待确认"''')

    # The upstream debug log already contains the whole /info request. Add a stable INFO-level line for
    # the one field we need on the vehicle: the cluster URLs the connected iPhone actually offers.
    airplay = root / 'shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt'
    replace(airplay, '''                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }''', '''                if (request.body.isNotEmpty()) {
                    val decodedInfo = try { BplistCodec.decode(request.body) } catch (_: Exception) { null }
                    val requestInfo = decodedInfo?.toString() ?: "<unparseable ${request.body.size} bytes>"
                    debugLog("airplay /info request=$requestInfo")
                    val requestMap = asMap(decodedInfo)
                    Log.i(TAG, "airplay /info altScreenURLs=${requestMap?.get("altScreenURLs") ?: "<missing>"}")
                }''')

    build = root / 'mobile/build.gradle.kts'
    replace(build, 'versionCode = 45', 'versionCode = 48')
    replace(build, 'versionNameSuffix = "-xingrui-r6.1-platform"', 'versionNameSuffix = "-xingrui-r6.4-platform"')
    mobile_manifest = root / 'mobile/src/main/AndroidManifest.xml'
    replace(mobile_manifest, 'android:label="DiPlay 星瑞 R6"', 'android:label="DiPlay 星瑞 R6.4"')
    print('Xingrui R6.4 instrument-cluster INSTRUMENTS selection, altScreenURLs logging and DIM diagnostics applied')


if __name__ == '__main__':
    main()
    apply_fragrance_patch()
