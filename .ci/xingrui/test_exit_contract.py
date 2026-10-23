#!/usr/bin/env python3
"""Check production Android lifecycle wiring after every build patch has run."""
from pathlib import Path
import sys


def verify(root):
    source = root / 'common/src/main/java/com/shilapi/xcertplay'
    host = (source / 'CarPlayHostActivity.kt').read_text()
    exit_method = host.split('    private fun exitApplication() {', 1)[1].split('    private fun shutdown(', 1)[0]
    assert 'shutdown(terminateProcess = true' in exit_method
    assert '{ finishAndRemoveTask() }' in exit_method
    assert '\n        finishAndRemoveTask()' not in exit_method
    shutdown = host.split('    private fun shutdown(', 1)[1].split('    private fun finishShutdown(', 1)[0]
    assert shutdown.index('dismissClusterPresentation()') < shutdown.index('XingruiDimProjection.pendingReleases()')
    assert 'XingruiExitCoordinator(' in shutdown and 'if (terminateProcess && !result.nativeRestored)' in shutdown
    assert 'teardownExecutor.execute' not in shutdown
    assert 'xingruiShutdownCompletion.await' in shutdown
    assert 'if (!shuttingDown.compareAndSet(false, true)) return' in shutdown
    assert 'mainHandler.post { xingruiShutdownCompletion.finish(true) }' in host
    assert 'Process.killProcess' not in host
    assert 'shutdown(false, "DiPlay disconnect") { completion(); finish() }' in host
    assert 'reason = "DiPlay disconnect") { completion(); finish() }' in host
    projection = (source / 'XingruiDimProjection.kt').read_text()
    assert 'active.add(this)' in projection and 'active.remove(this)' in projection
    assert 'released.await' in projection and 'released.finish(success)' in projection
    guard = (source / 'XingruiClusterGuard.kt').read_text()
    assert 'XingruiLegacyNavigationRecovery.recover' in guard
    assert 'COMPONENT_ENABLED_STATE_DISABLED' not in guard
    assert 'putBoolean(KEY_HELD, true)' not in guard
    manifest = (root / 'common/src/main/AndroidManifest.xml').read_text()
    assert 'android:name="com.shilapi.xcertplay.XingruiGuardBootReceiver"\n            android:exported="false"' in manifest
    print('PASS: generated Android exit callbacks await the barrier; no forced kill or new persistent navigation disable')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
