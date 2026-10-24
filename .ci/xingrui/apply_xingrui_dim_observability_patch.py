#!/usr/bin/env python3
"""R6.2: preserve independent read-back and record real Presentation/window state."""
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
    build = root / 'mobile/build.gradle.kts'
    replace(build, 'versionCode = 45', 'versionCode = 46')
    replace(build, 'versionNameSuffix = "-xingrui-r6.1-platform"', 'versionNameSuffix = "-xingrui-r6.2-platform"')
    print('Xingrui R6.2 independent read-back and DIM/window diagnostics applied; legacy raw arguments unchanged')


if __name__ == '__main__':
    main()
    apply_fragrance_patch()
