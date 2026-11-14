import android.content.Context;
import com.ecarx.xui.adaptapi.input.*;
import com.ganfandw.carplay.patch.XingruiWheelBridge;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercise the production bridge, including SDK reflection and actual forwarded calls. */
public class WheelBridgeTest {
    public static class Host extends Context {
        private boolean mediaKeysEnabled = true;
        private Object activeAirPlaySession = new Object();
        private AtomicBoolean shuttingDown = new AtomicBoolean();
        private boolean call, bluetooth, defer;
        private List<Integer> sent = new ArrayList<Integer>();
        private List<Runnable> queue = new ArrayList<Runnable>();
        public Context getApplicationContext() { return this; }
        public void runOnUiThread(Runnable task) { if (defer) queue.add(task); else task.run(); }
        private boolean getCallPresent() { return call; }
        private boolean getBluetoothAudioActive() { return bluetooth; }
        private boolean requestMediaKey(int key, String source) { sent.add(key); return true; }
    }
    public static class Key {
        private final int code, action, repeat;
        Key(int code, int action, int repeat) { this.code = code; this.action = action; this.repeat = repeat; }
        public int getKeyCode() { return code; }
        public int getAction() { return action; }
        public int getRepeatCount() { return repeat; }
    }
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static boolean key(Host host, int code, int action, int repeat) {
        return XingruiWheelBridge.dispatch(host, new Key(code, action, repeat));
    }
    public static void main(String[] args) throws Exception {
        Host host = new Host(); Input sdk = Input.current = new Input();
        XingruiWheelBridge.start(host); XingruiWheelBridge.start(host);
        check(sdk.requests == 1, "duplicate start must not duplicate registrations");
        check(Arrays.equals(sdk.codes, new int[]{200087, 200088}), "request confirmed SDK ids only");
        IKeyCallback callback = sdk.callback;
        check(callback.onKeyPressed(200087), "SDK next accepted");
        check(callback.onKeyPressed(200087), "held SDK repeat consumed");
        check(key(host, 305, 0, 0), "raw duplicate of owned SDK press consumed");
        check(key(host, 305, 0, 1), "raw held repeat consumed");
        check(host.sent.equals(Arrays.asList(87)), "exactly one NEXT, independent of duplicate input paths");
        check(callback.onKeyReleased(200087), "SDK release owned");
        check(!key(host, 305, 1, 0), "second release not owned");
        check(key(host, 304, 0, 0) && host.sent.equals(Arrays.asList(87, 88)), "raw previous forwarded correctly");
        host.call = true;
        check(key(host, 304, 1, 0), "release remains consumed when call starts during held press");
        check(!callback.onKeyPressed(200087), "new media key passes through during call");
        host.call = false; host.bluetooth = true;
        check(!key(host, 305, 0, 0), "Bluetooth audio session is excluded");
        host.bluetooth = false;
        for (int reserved : new int[]{3,4,5,6,23,24,25,26,85,164,231,301,302,306,200024,200025})
            check(!key(host, reserved, 0, 0) && !callback.onKeyPressed(reserved), "reserved or unconfirmed key " + reserved);
        check(!key(host, 305, 0, 1), "unowned repeat never sends a command");
        host.activeAirPlaySession = null;
        check(!callback.onKeyPressed(200087), "no active session passes through");
        host.activeAirPlaySession = new Object(); host.mediaKeysEnabled = false;
        check(!callback.onKeyPressed(200087), "driver disable respected");
        host.mediaKeysEnabled = true; host.shuttingDown.set(true);
        check(!callback.onKeyPressed(200087), "shutdown excluded");
        host.shuttingDown.set(false); host.defer = true;
        check(callback.onKeyPressed(200087), "queued press accepted");
        XingruiWheelBridge.stop(host); XingruiWheelBridge.stop(host);
        for (Runnable task : host.queue) task.run();
        check(host.sent.size() == 2, "queued delivery invalidated on stop");
        check(sdk.abandons == 1, "one unregister for repeated stop");
        check(!callback.onKeyPressed(200088), "late SDK callback cannot forward after stop");
        check(!key(host, 305, 0, 0), "stopped owner cannot consume raw input");
        Host replacement = new Host(); sdk = Input.current = new Input(); sdk.failRequest = true;
        XingruiWheelBridge.start(replacement);
        check(sdk.abandons == 1, "partial registration abandoned even when request throws");
        check(!sdk.callback.onKeyPressed(200087), "failed registration's stale callback rejected");
        check(key(replacement, 305, 0, 0), "raw fallback works after SDK failure");
        check(replacement.sent.equals(Arrays.asList(87)), "fallback forwarded only once");
        XingruiWheelBridge.stop(replacement);
        check(sdk.abandons == 1, "already abandoned callback not abandoned twice");
        System.out.println("PASS " + checks + " production bridge assertions");
    }
}
