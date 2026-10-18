#!/system/bin/sh
# Android 9 / ADB shell. No pipe from logcat to a slow terminal or merged dumpsys stream.
BASE=${XINGRUI_LOG_DIR:-/sdcard/Download/XingruiLogs}
STATE=$BASE/current_capture
mkdir -p "$BASE"
case "${1:-status}" in
start)
    if [ -f "$STATE" ]; then
        active=$(cat "$STATE")
        if [ -f "$active/running" ]; then echo "Capture already active: $active"; exit 1; fi
    fi
    capture="$BASE/capture-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$capture"
    printf '%s\n' "$capture" > "$STATE"
    touch "$capture/running"
    # The worker survives ADB/phone terminal disconnection. No logcat buffer is cleared.
    nohup sh "$0" _run "$capture" </dev/null >"$capture/worker.txt" 2>&1 &
    echo "Capture starting: $capture"
    ;;
_run)
    capture=$2
    trap '' HUP
    logpid=
    cleanup() {
        if [ -n "$logpid" ]; then kill "$logpid" 2>/dev/null; wait "$logpid" 2>/dev/null; fi
        date '+collector_stopped=%Y-%m-%dT%H:%M:%S%z' >> "$capture/health.txt"
        rm -f "$capture/running"
    }
    trap cleanup EXIT
    trap 'exit 0' TERM INT
    date '+collector_started=%Y-%m-%dT%H:%M:%S%z' > "$capture/health.txt"
    echo 'collector_version=2026-10-18-v5' >> "$capture/health.txt"
    # Start native file capture BEFORE snapshots. Each producer owns its own file.
    logcat -b all -v threadtime -f "$capture/logcat.txt" -r 8192 -n 3 2>"$capture/logcat.err" &
    logpid=$!
    snapshot() {
        phase=$1
        date > "$capture/snapshot-$phase.txt"
        for service in display window input car_service audio media_session bluetooth_manager wifi connectivity; do
            echo "===== $service =====" >> "$capture/snapshot-$phase.txt"
            dumpsys -t 6 "$service" >> "$capture/snapshot-$phase.txt" 2>&1
        done
        echo '===== IPK NETWORK =====' >> "$capture/snapshot-$phase.txt"
        ip addr show dev usb0 >> "$capture/snapshot-$phase.txt" 2>&1
        ip neigh show dev usb0 >> "$capture/snapshot-$phase.txt" 2>&1
        ip route show table all >> "$capture/snapshot-$phase.txt" 2>&1
        getprop > "$capture/props-$phase.txt"
    }
    snapshot START
    while [ ! -f "$capture/stop" ]; do
        if ! kill -0 "$logpid" 2>/dev/null; then
            echo "ERROR: logcat exited; inspect logcat.err" >> "$capture/health.txt"
            break
        fi
        date '+heartbeat=%Y-%m-%dT%H:%M:%S%z' >> "$capture/health.txt"
        wc -c "$capture/logcat.txt" >> "$capture/health.txt" 2>&1
        sleep 3
    done
    # Stop and reap the writer before assembling the report; never export a still-growing file.
    kill "$logpid" 2>/dev/null
    wait "$logpid" 2>/dev/null
    logpid=
    snapshot END
    {
        cat "$capture/health.txt"
        echo '===== START SNAPSHOT ====='
        cat "$capture/snapshot-START.txt"
        echo '===== LOGCAT (oldest retained to newest) ====='
        for f in "$capture/logcat.txt.3" "$capture/logcat.txt.2" "$capture/logcat.txt.1" "$capture/logcat.txt"; do
            [ ! -f "$f" ] || cat "$f"
        done
        echo '===== LOGCAT STDERR ====='
        cat "$capture/logcat.err"
        echo '===== END SNAPSHOT ====='
        cat "$capture/snapshot-END.txt"
        date '+report_completed=%Y-%m-%dT%H:%M:%S%z'
    } > "$BASE/xingrui_carplay_test.txt.tmp"
    cp "$capture/props-END.txt" "$BASE/xingrui_props.txt.tmp"
    mv "$BASE/xingrui_props.txt.tmp" "$BASE/xingrui_props.txt"
    mv "$BASE/xingrui_carplay_test.txt.tmp" "$BASE/xingrui_carplay_test.txt"
    echo "Report ready: $BASE/xingrui_carplay_test.txt"
    ;;
stop)
    [ -f "$STATE" ] || { echo 'No capture'; exit 1; }
    capture=$(cat "$STATE")
    touch "$capture/stop"
    echo "Finishing snapshots; check status until READY (up to two minutes)."
    ;;
status)
    [ -f "$STATE" ] || { echo 'No capture'; exit 1; }
    capture=$(cat "$STATE")
    if [ -f "$capture/running" ]; then
        echo "RUNNING / FINALIZING: $capture"
        tail -n 5 "$capture/health.txt" 2>/dev/null
    elif [ -f "$capture/snapshot-END.txt" ] && grep -q 'report_completed=' "$BASE/xingrui_carplay_test.txt" 2>/dev/null; then
        echo "READY: $BASE/xingrui_carplay_test.txt"
        echo "READY: $BASE/xingrui_props.txt"
    else
        echo "ERROR: worker stopped without a complete report: $capture/worker.txt"
    fi
    ;;
*) echo "Usage: sh $0 start|stop|status"; exit 1 ;;
esac
