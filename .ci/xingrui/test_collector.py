"""Exercise start/disconnect-safe capture/stop/export without an Android device."""
from pathlib import Path
import os
import subprocess
import tempfile
import time

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    commands = root/'bin'; commands.mkdir()
    for name, body in {
        'dumpsys': 'echo snapshot-"$*"',
        'getprop': 'echo "[ro.product.model]: [FS11A1]"',
        'ip': 'echo mock-network',
        'logcat': '''while [ "$#" -gt 0 ]; do
  if [ "$1" = '-f' ]; then target=$2; shift; fi
  shift
done
trap 'exit 0' TERM INT
while :; do date '+%m-%d %H:%M:%S mock-logcat' >> "$target"; sleep 0.1; done''',
    }.items():
        f=commands/name; f.write_text('#!/bin/sh\n'+body+'\n'); f.chmod(0o755)
    base=root/'logs'
    env=dict(os.environ, PATH=str(commands)+os.pathsep+os.environ['PATH'], XINGRUI_LOG_DIR=str(base))
    script=Path(__file__).with_name('collect_xingrui_test.sh').resolve()
    def run(action):
        return subprocess.run(['sh',str(script),action],env=env,check=True,capture_output=True,text=True).stdout
    run('start')
    for _ in range(50):
        capture=Path((base/'current_capture').read_text().strip())
        if (capture/'health.txt').exists() and 'heartbeat=' in (capture/'health.txt').read_text(): break
        time.sleep(.1)
    else: raise AssertionError('worker never started')
    assert 'RUNNING' in run('status')
    time.sleep(.3)
    run('stop')
    for _ in range(100):
        if not (capture/'running').exists(): break
        time.sleep(.1)
    else: raise AssertionError('worker did not exit')
    report=(base/'xingrui_carplay_test.txt').read_text()
    assert 'mock-logcat' in report and 'report_completed=' in report
    assert report.index('START SNAPSHOT') < report.index('LOGCAT (') < report.index('END SNAPSHOT')
    assert 'READY:' in run('status')
    size=(base/'xingrui_carplay_test.txt').stat().st_size
    time.sleep(.3)
    assert size == (base/'xingrui_carplay_test.txt').stat().st_size, 'report still growing'
    assert 'FS11A1' in (base/'xingrui_props.txt').read_text()
    print('PASS: detached capture, stop/reap, ordered stable export, status and props')
