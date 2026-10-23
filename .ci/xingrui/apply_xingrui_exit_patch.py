#!/usr/bin/env python3
"""R6.1: release barrier before host teardown; no persistent native-navigation disable or process kill."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace


def main():
    root = Path(sys.argv[1])
    host = root / 'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt'
    replace(host, '    private val shuttingDown = AtomicBoolean(false)',
        '    private val shuttingDown = AtomicBoolean(false)\n    private val xingruiShutdownCompletion = XingruiReleaseCompletion()')
    replace(host, '        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }',
        '''        xingruiShutdownCompletion.await { complete -> if (complete) completion() }
        if (!shuttingDown.compareAndSet(false, true)) return''')
    replace(host, '''                shutdown(false, "DiPlay disconnect", completion)
                finish()''', '''                shutdown(false, "DiPlay disconnect") { completion(); finish() }''')
    replace(host, '''                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()''', '''                shutdown(terminateProcess = false, reason = "DiPlay disconnect") { completion(); finish() }''')
    replace(host, '''        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")''',
        '''        shutdown(terminateProcess = true, reason = "settings exit application") { finishAndRemoveTask() }''')
    replace(host, '''        xingruiAudioGuard.stop()
        callPopupSessionReady = false''', '''        xingruiAudioGuard.stop()
        resetSidePanel()
        releaseSidePanelEffects()
        // onStop can already have queued an asynchronous release: the registry also tracks those.
        dismissClusterPresentation()
        val pending = XingruiDimProjection.pendingReleases()
        XingruiRuntimeStatus.record("退出恢复", "正在释放仪表并恢复旧版本导航状态；等待 ${pending.size} 个显示会话")
        XingruiExitCoordinator(
            scheduler = XingruiScheduler { delay, task -> mainHandler.postDelayed({ task() }, delay) },
            releases = pending,
            recoverNative = { done ->
                Thread({
                    val restored = runCatching { XingruiClusterGuard.release(applicationContext, "退出前恢复") }
                        .getOrDefault(false)
                    done(restored)
                }, "xingrui-exit-recovery").apply { isDaemon = true; start() }
            },
            complete = { result ->
                XingruiRuntimeStatus.record("退出恢复", "SDK释放=${result.sdkReleased} 原车导航恢复=${result.nativeRestored} 超时=${result.timedOut}；实屏待确认")
                if (terminateProcess && !result.nativeRestored) {
                    // A legacy R6 disable record is still unresolved. Keep the app alive for a retry.
                    shuttingDown.set(false)
                    android.widget.Toast.makeText(this,
                        "原车导航恢复未获确认，暂未退出；请重试并查看适配诊断，暂勿卸载。",
                        android.widget.Toast.LENGTH_LONG).show()
                } else finishShutdown(terminateProcess, reason)
            },
        ).start()
    }

    private fun finishShutdown(terminateProcess: Boolean, reason: String) {
        callPopupSessionReady = false''')
    replace(host, '            mainHandler.post { completion() }',
        '            mainHandler.post { xingruiShutdownCompletion.finish(true) }')
    # Existing host tests must await the new barrier before asserting downstream codec teardown.
    side_test = root / 'common/src/test/java/com/shilapi/xcertplay/SidePanelLifecycleTest.kt'
    replace(side_test, '''        assertPanelStopped()
        assertTrue((get("teardownExecutor") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))''', '''        assertPanelStopped()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val executor = get("teardownExecutor") as ExecutorService
        while (!executor.isShutdown && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Release barrier must complete before host teardown", executor.isShutdown)
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))''')
    video_test = root / 'common/src/test/java/com/shilapi/xcertplay/SmoothVideoHostLifecycleTest.kt'
    replace(video_test, '''Function0::class.java).apply { isAccessible = true }.invoke(activity, false, reason, {})''', '''Function0::class.java).apply { isAccessible = true }.invoke(activity, false, reason, {})
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (getField("sink") != null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertNull("Release barrier must reach controller teardown", getField("sink"))''')
    replace(host, '''            if (terminateProcess) Process.killProcess(Process.myPid())''',
        '''            // Leave process lifetime to Android: never interrupt outstanding vendor-service cleanup.''')
    replace(host, '''    private fun ensureClusterPresentation() {
        if (AirPlayPersistence''', '''    private fun ensureClusterPresentation() {
        if (shuttingDown.get()) return
        if (AirPlayPersistence''')
    build = root / 'mobile/build.gradle.kts'
    replace(build, 'versionCode = 44', 'versionCode = 45')
    replace(build, 'versionNameSuffix = "-xingrui-r6-platform"', 'versionNameSuffix = "-xingrui-r6.1-platform"')
    manifest = root / 'common/src/main/AndroidManifest.xml'
    replace(manifest, '''android:name="com.shilapi.xcertplay.XingruiGuardBootReceiver"
            android:exported="true"''', '''android:name="com.shilapi.xcertplay.XingruiGuardBootReceiver"
            android:exported="false"''')
    print('Xingrui R6.1 exit/recovery barrier applied; persistent navigation disable and forced process kill removed')


if __name__ == '__main__':
    main()
