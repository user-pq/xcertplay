package com.ganfandw.carplay.patch;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** E02 Input interception, ported from xcertplay's XingruiWheelSession/KeyRouter.
 * Only the confirmed next/previous actions are intercepted. Existing APK code owns
 * audio focus, command transport, track-change deduplication and all other keys.
 * No Android classes are bundled or replaced by this patch.
 */
public final class XingruiWheelBridge {
    private static final Map<Object, State> owners = new WeakHashMap<Object, State>();
    private static final String INPUT = "com.ecarx.xui.adaptapi.input.Input";
    private static final String CALLBACK = "com.ecarx.xui.adaptapi.input.IKeyCallback";
    private XingruiWheelBridge() {}

    public static synchronized void start(Object host) {
        if (owners.containsKey(host)) return;
        State state = new State(host);
        owners.put(host, state);
        state.register();
    }

    public static synchronized void stop(Object host) {
        State state = owners.remove(host);
        if (state != null) state.close();
    }

    /** Run before the APK's KeyEvent fallback; do not capture volume/phone/OK keys. */
    public static synchronized boolean dispatch(Object host, Object event) {
        State state = owners.get(host);
        if (state == null || event == null) return false;
        try {
            int code = ((Integer) call(event, "getKeyCode")).intValue();
            int action = ((Integer) call(event, "getAction")).intValue();
            if (canonical(code) == 0) return false;
            if (action == 1) return state.up(code);
            if (action != 0) return false;
            int repeat = ((Integer) call(event, "getRepeatCount")).intValue();
            // A repeat with no owned first press must pass through, never synthesize a command.
            if (repeat != 0) return state.held(code);
            // Preserve the supplied APK's explicit key-learning interaction.
            try {
                Class<?> learning = Class.forName("com.ganfandw.carplay.MediaKeyLearning");
                Object instance = learning.getField("INSTANCE").get(null);
                if ((Boolean) learning.getMethod("capture", int.class).invoke(instance, code)) return true;
            } catch (ClassNotFoundException ignored) { /* JVM fixture */ }
            return state.down(code, "KeyEvent");
        } catch (Exception error) { log("dispatch unavailable: " + reason(error)); return false; }
    }

    static int canonical(int code) {
        if (code == 200087 || code == 305 || code == 87) return 200087;
        if (code == 200088 || code == 304 || code == 88) return 200088;
        return 0;
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static boolean eligible(Object host) {
        try {
            return host != null && (Boolean) field(host, "mediaKeysEnabled")
                && field(host, "activeAirPlaySession") != null
                && !((AtomicBoolean) field(host, "shuttingDown")).get()
                && !(Boolean) call(host, "getCallPresent")
                && !(Boolean) call(host, "getBluetoothAudioActive");
        } catch (Exception error) { return false; }
    }
    private static void log(String message) {
        try { Class.forName("android.util.Log").getMethod("i", String.class, String.class)
            .invoke(null, "XingruiWheelPatch", message); }
        catch (Exception ignored) { System.out.println("XingruiWheelPatch: " + message); }
    }
    private static String reason(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName();
    }

    private static final class State implements InvocationHandler {
        private final WeakReference<Object> host;
        private final Set<Integer> presses = new HashSet<Integer>();
        private Object input;
        private Object callback;
        private Method abandon;
        private volatile boolean sdkAccepting;
        private boolean closed;
        private int generation;
        State(Object host) { this.host = new WeakReference<Object>(host); }
        void register() {
            try {
                Object owner = host.get();
                ClassLoader loader = owner.getClass().getClassLoader();
                Class<?> context = Class.forName("android.content.Context", false, loader);
                Class<?> api = Class.forName(INPUT, false, loader);
                Class<?> key = Class.forName(CALLBACK, false, loader);
                if (!key.isInterface()) throw new IllegalStateException("callback is not interface");
                if (key.getMethod("onKeyPressed", int.class).getReturnType() != boolean.class ||
                    key.getMethod("onKeyReleased", int.class).getReturnType() != boolean.class)
                    throw new IllegalStateException("callback signature mismatch");
                Object app = owner.getClass().getMethod("getApplicationContext").invoke(owner);
                input = api.getMethod("create", context).invoke(null, app);
                if (!api.isInstance(input)) throw new IllegalStateException("input factory mismatch");
                abandon = api.getMethod("abandonKeysInterception", key);
                if (abandon.getReturnType() != boolean.class) throw new IllegalStateException("abandon signature mismatch");
                Method request = api.getMethod("requestKeysInterception", int[].class, key);
                if (request.getReturnType() != int[].class) throw new IllegalStateException("request signature mismatch");
                callback = Proxy.newProxyInstance(key.getClassLoader(), new Class<?>[]{key}, this);
                sdkAccepting = true;
                Object result = request.invoke(input, new int[]{200087, 200088}, callback);
                log("requested SDK actions=[200087, 200088] return=" + Arrays.toString((int[]) result)
                    + "; registration return is not proof of real key delivery");
            } catch (Exception error) {
                log("SDK registration unavailable: " + reason(error) + "; raw 304/305 fallback remains");
                releaseCallback();
            }
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
            if (name.equals("toString")) return "XingruiE02WheelCallback";
            if (!sdkAccepting) return false;
            if (name.equals("onKeyPressed")) return down((Integer) args[0], "SDK");
            if (name.equals("onKeyReleased")) return up((Integer) args[0]);
            return null;
        }
        synchronized boolean held(int code) { return presses.contains(canonical(code)); }
        synchronized boolean up(int code) { return presses.remove(canonical(code)); }
        synchronized boolean down(int code, final String source) {
            final int action = canonical(code);
            if (closed || action == 0) return false;
            if (presses.contains(action)) return true;
            final Object owner = host.get();
            if (!eligible(owner)) return false;
            final Object expectedSession;
            try { expectedSession = field(owner, "activeAirPlaySession"); }
            catch (Exception error) { return false; }
            presses.add(action);
            final int epoch = generation;
            try {
                owner.getClass().getMethod("runOnUiThread", Runnable.class).invoke(owner, new Runnable() {
                    @Override public void run() {
                        synchronized (State.this) { if (closed || epoch != generation) return; }
                        if (!eligible(owner)) return;
                        try {
                            if (field(owner, "activeAirPlaySession") != expectedSession) return;
                            Method forward = owner.getClass().getDeclaredMethod("requestMediaKey", int.class, String.class);
                            forward.setAccessible(true);
                            boolean sent = (Boolean) forward.invoke(owner, action == 200087 ? 87 : 88, "Xingrui-E02-" + source);
                            log("action=" + action + " source=" + source + " forwarded=" + sent);
                        } catch (Exception error) { log("forward failed: " + reason(error)); }
                    }
                });
                return true;
            } catch (Exception error) {
                presses.remove(action);
                log("main dispatch failed: " + reason(error));
                return false;
            }
        }
        void close() {
            synchronized (this) { closed = true; generation++; presses.clear(); }
            // Do not hold the press monitor across a synchronous OEM Binder call.
            releaseCallback();
        }
        private void releaseCallback() {
            sdkAccepting = false;
            if (callback == null || abandon == null || input == null) return;
            try { log("abandon SDK interception returned=" + abandon.invoke(input, callback)); }
            catch (Exception error) { log("abandon failed: " + reason(error)); }
            finally { callback = null; }
        }
    }
}
