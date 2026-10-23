#!/usr/bin/env python3
"""Check that final Android output retains the actual read-back and diagnostic wiring."""
from pathlib import Path
import sys


def verify(root):
    source = root / 'common/src/main/java/com/shilapi/xcertplay'
    adapter = (source / 'XingruiSdkContract.kt').read_text()
    read = adapter.split('override fun read(): XingruiDimState {', 1)[1].split('private data class Snapshot', 1)[0]
    assert read.index('projectionSnapshot()') < read.index('binding.enabled?.invoke')
    assert 'unsupportedGetter' in read and 'snapshot.internal' in read
    assert '申请前 requested=' in adapter and '申请后 requested=' in adapter
    assert 'mPowerOperation actualClass=' in adapter and '参数语义未证实' in adapter
    session = (source / 'XingruiDimSession.kt').read_text()
    assert 'XingruiSwitchVerdict.SENT_UNVERIFIED ->' in session
    window = (source / 'ClusterMapPresentation.kt').read_text()
    for item in ('fun xingruiWindowSnapshot', 'isShowing=$isShowing', 'displayState=${display.state}',
                 'surfaceValid=${outputSurface?.isValid}', 'stream111-first-frame-released-to-surface'):
        assert item in window, item
    host = (source / 'CarPlayHostActivity.kt').read_text()
    assert 'presentation.xingruiWindowSnapshot("show-return")' in host
    assert 'SDK释放=${result.sdkReleased}' not in host
    assert '未获确认（含不可读状态）' in host
    diagnostic = (source / 'XingruiDiagnostics.kt').read_text()
    for item in ('XingruiCluster:V', 'DiPlay-Cluster:V', 'ClusterMapPresentation:V', 'display-state.txt'):
        assert item in diagnostic, item
    assert 'state=${display.state}' in (source / 'XingruiClusterProbe.kt').read_text()
    print('PASS: independent DIM read-back, unverified state, Android window snapshots and diagnostic export wiring')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
