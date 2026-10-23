package com.shilapi.xcertplay

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Public signatures observed in the E02 SDK dump, not proof of permission or physical output. */
internal object XingruiSdkContract {
    const val DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    const val NAVI = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction"
    const val CONNECT = "com.ecarx.xui.adaptapi.binder.IConnectable"
    const val WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher"
    const val INPUT = "com.ecarx.xui.adaptapi.input.Input"
    const val KEY = "com.ecarx.xui.adaptapi.input.IKeyCallback"
    val BOOL: Class<*> = java.lang.Boolean.TYPE
    val INT: Class<*> = java.lang.Integer.TYPE
    val VOID: Class<*> = java.lang.Void.TYPE

    fun method(type: Class<*>, name: String, returns: Class<*>, vararg args: Class<*>): Method {
        val method = type.getMethod(name, *args)
        require(method.returnType == returns) { "${type.name}.$name returns ${method.returnType.name}; expected ${returns.name}" }
        return method
    }
    fun factory(type: Class<*>, contextType: Class<*>): Method = method(type, "create", type, contextType).also {
        require(Modifier.isStatic(it.modifiers)) { "${type.name}.create must be static" }
    }
    fun input(contextType: Class<*>, load: (String) -> Class<*>): Input {
        val type = load(INPUT); val key = load(KEY)
        require(key.isInterface) { "IKeyCallback must be an interface" }
        method(key, "onKeyPressed", BOOL, INT); method(key, "onKeyReleased", BOOL, INT)
        return Input(type, key, factory(type, contextType),
            method(type, "requestKeysInterception", IntArray::class.java, IntArray::class.java, key),
            method(type, "abandonKeysInterception", BOOL, key))
    }
    fun dim(contextType: Class<*>, load: (String) -> Class<*>): Dim {
        val type = load(DIM); val connect = load(CONNECT); val watcher = load(WATCHER); val navi = load(NAVI)
        require(watcher.isInterface) { "IConnectWatcher must be an interface" }
        method(watcher, "onConnected", VOID); method(watcher, "onDisConnected", VOID)
        // Only the factory implementation is known to implement the lifecycle interface.
        // Lifecycle return values are unused and were not in the device's public DIM dump.
        return Dim(type, connect, watcher, factory(type, contextType),
            connect.getMethod("registerConnectWatcher", watcher),
            connect.getMethod("unregisterConnectWatcher"), connect.getMethod("connect"),
            connect.getMethod("disconnect"),
            method(type, "setPresentationToDimSwitch", VOID, INT, String::class.java, String::class.java, BOOL),
            method(type, "getPresentationToDimSwitch", BOOL), method(type, "getNaviInteraction", navi))
    }
    /** No factory invocation, service bind, key registration or display writes. */
    fun describe(contextType: Class<*>, load: (String) -> Class<*> = { Class.forName(it) }): String =
        listOf("Input" to { input(contextType, load) }, "DIM" to { dim(contextType, load) }).joinToString("\n") { (name, check) ->
            runCatching { check(); "$name required public signatures: MATCH (authorization and runtime state unverified)" }
                .getOrElse { "$name required public signatures: MISMATCH ${it.javaClass.simpleName}: ${it.message}" }
        } + "\nOptional implementation fields mPowerOperation/isScreenProjection are checked only on live instances; absent/unreadable means UNKNOWN.\nPhysical visibility is never established by this report."

    data class Input(val type: Class<*>, val key: Class<*>, val create: Method, val request: Method, val abandon: Method)
    data class Dim(val type: Class<*>, val connectType: Class<*>, val watcher: Class<*>, val create: Method, val watch: Method,
        val unwatch: Method, val connect: Method, val disconnect: Method, val request: Method,
        val enabled: Method, val navi: Method)
}

internal interface XingruiInputPort {
    fun request(codes: IntArray, pressed: (Int) -> Boolean, released: (Int) -> Boolean): IntArray?
    fun abandon()
    fun discover(): List<Int>
}

internal class XingruiReflectiveInput(context: Any, contextType: Class<*>,
    private val load: (String) -> Class<*> = { Class.forName(it) }) : XingruiInputPort {
    private val binding = XingruiSdkContract.input(contextType, load)
    private val instance = checkNotNull(binding.create.invoke(null, context)).also { require(binding.type.isInstance(it)) }
    private var callback: Any? = null
    override fun request(codes: IntArray, pressed: (Int) -> Boolean, released: (Int) -> Boolean): IntArray? {
        abandon()
        val next = Proxy.newProxyInstance(binding.key.classLoader, arrayOf(binding.key)) { proxy, method, args ->
            when (method.name) {
                "onKeyPressed" -> pressed(args!![0] as Int)
                "onKeyReleased" -> released(args!![0] as Int)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "XingruiInputCallback"
                else -> null
            }
        }
        // Retain before invocation: even a throwing service may have stored the callback.
        callback = next
        return binding.request.invoke(instance, codes.copyOf(), next) as? IntArray
    }
    override fun abandon() {
        val old = callback ?: return
        binding.abandon.invoke(instance, old)
        callback = null
    }
    override fun discover(): List<Int> = load("com.ecarx.xui.adaptapi.input.KeyCode").fields.filter {
        it.type == XingruiSdkContract.INT && Modifier.isStatic(it.modifiers) && XingruiKeyRouter.isSdkKeyName(it.name)
    }.map { it.getInt(null) }
}

internal data class XingruiDimState(val enabled: Boolean?, val internal: Boolean?, val note: String = "")
internal interface XingruiDimPort {
    fun watch(connected: () -> Unit, disconnected: () -> Unit)
    fun connect()
    fun request(show: Boolean)
    fun read(): XingruiDimState
    fun dispose()
}

internal class XingruiReflectiveDim(context: Any, contextType: Class<*>, private val ownerPackage: String,
    private val ownerClass: String, load: (String) -> Class<*> = { Class.forName(it) }) : XingruiDimPort {
    private val binding = XingruiSdkContract.dim(contextType, load)
    private val instance = checkNotNull(binding.create.invoke(null, context)).also {
        require(binding.type.isInstance(it) && binding.connectType.isInstance(it)) { "DIM implementation lacks lifecycle contract" }
    }
    private var watcher: Any? = null
    override fun watch(connected: () -> Unit, disconnected: () -> Unit) {
        watcher = Proxy.newProxyInstance(binding.watcher.classLoader, arrayOf(binding.watcher)) { proxy, method, args ->
            when (method.name) {
                "onConnected" -> { connected(); null }
                "onDisConnected" -> { disconnected(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "XingruiDimConnection"
                else -> null
            }
        }
        binding.watch.invoke(instance, watcher)
    }
    override fun connect() { binding.connect.invoke(instance) }
    override fun request(show: Boolean) {
        binding.request.invoke(instance, 0, ownerPackage, ownerClass, show)
        if (show) {
            val navi = checkNotNull(binding.navi.invoke(instance)) { "navigation service unavailable; source not requested" }
            val power = checkNotNull(field(navi, "mPowerOperation")) { "display power service not ready" }
            XingruiSdkContract.method(power.javaClass, "disableScreenProjection", XingruiSdkContract.VOID,
                XingruiSdkContract.BOOL, XingruiSdkContract.INT).invoke(power, false, 2)
        }
    }
    override fun read(): XingruiDimState {
        val enabled = binding.enabled.invoke(instance) as Boolean
        val navi = binding.navi.invoke(instance)
        val internal = navi?.let { runCatching { field(it, "isScreenProjection") as? Boolean }.getOrNull() }
        return XingruiDimState(enabled, internal, if (navi == null) "navigation service unavailable" else
            if (internal == null) "internal projection state unavailable" else "SDK read-back only; physical display unverified")
    }
    override fun dispose() {
        try { if (watcher != null) binding.unwatch.invoke(instance) }
        finally { watcher = null; binding.disconnect.invoke(instance) }
    }
    private fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(if (Modifier.isStatic(field.modifiers)) null else instance)
            }
            type = current.superclass
        }
        throw NoSuchFieldException("${instance.javaClass.name}.$name")
    }
}
