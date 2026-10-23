package com.shilapi.xcertplay

import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
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
            runCatching { method(type, "getPresentationToDimSwitch", BOOL) }.getOrNull(),
            method(type, "getNaviInteraction", navi))
    }
    /** No factory invocation, service bind, key registration or display writes. */
    fun describe(contextType: Class<*>, load: (String) -> Class<*> = { Class.forName(it) }): String =
        listOf("Input" to { input(contextType, load) }, "DIM" to { dim(contextType, load) }).joinToString("\n") { (name, check) ->
            runCatching { check(); "$name required public signatures: MATCH (authorization and runtime state unverified)" }
                .getOrElse { "$name required public signatures: MISMATCH ${it.javaClass.simpleName}: ${it.message}" }
        } + "\ngetPresentationToDimSwitch is optional: declaration does not establish implementation support.\nOptional implementation fields mPowerOperation/isScreenProjection are checked only on live instances; absent/unreadable means UNKNOWN.\nPhysical visibility is never established by this report."

    data class Input(val type: Class<*>, val key: Class<*>, val create: Method, val request: Method, val abandon: Method)
    data class Dim(val type: Class<*>, val connectType: Class<*>, val watcher: Class<*>, val create: Method, val watch: Method,
        val unwatch: Method, val connect: Method, val disconnect: Method, val request: Method,
        val enabled: Method?, val navi: Method)
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
    private val ownerClass: String, load: (String) -> Class<*> = { Class.forName(it) },
    private val report: (String) -> Unit = {}) : XingruiDimPort {
    private val binding = XingruiSdkContract.dim(contextType, load)
    private val instance = checkNotNull(binding.create.invoke(null, context)).also {
        require(binding.type.isInstance(it) && binding.connectType.isInstance(it)) { "DIM implementation lacks lifecycle contract" }
    }
    private var watcher: Any? = null
    private var unsupportedGetter: String? = if (binding.enabled == null) "getter 不受支持：未找到兼容签名" else null
    // Preserve legacy raw arguments. Their OEM semantics have not been established by device evidence.
    private val legacyKind = 0
    private val legacyPowerMode = 2
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
        val before = projectionSnapshot()
        diagnostic("申请前 requested=$show kind=$legacyKind ownerPackage=$ownerPackage ownerClass=$ownerClass " +
            "powerBlocked=false powerMode=$legacyPowerMode 参数语义未证实 internal=${before.internal} ${before.note}")
        var step = "setPresentationToDimSwitch"
        try {
            binding.request.invoke(instance, legacyKind, ownerPackage, ownerClass, show)
            diagnostic("setPresentationToDimSwitch($show) 调用已返回；不代表服务接受或实屏可见")
            if (show) {
                step = "disableScreenProjection"
                val navi = checkNotNull(binding.navi.invoke(instance)) { "navigation service unavailable; source not requested" }
                val power = checkNotNull(field(navi, "mPowerOperation")) { "display power service not ready" }
                diagnostic("mPowerOperation actualClass=${power.javaClass.name}")
                XingruiSdkContract.method(power.javaClass, "disableScreenProjection", XingruiSdkContract.VOID,
                    XingruiSdkContract.BOOL, XingruiSdkContract.INT).invoke(power, false, legacyPowerMode)
                diagnostic("disableScreenProjection(false, $legacyPowerMode) 调用已返回；不代表服务接受或实屏可见")
            }
        } catch (error: Exception) {
            diagnostic("请求阶段=$step requested=$show 调用异常 ${failure(error)}")
            throw error
        } finally {
            val after = projectionSnapshot()
            diagnostic("申请后 requested=$show internal=${after.internal} ${after.note}")
        }
    }
    override fun read(): XingruiDimState {
        // Read this independently FIRST. An unimplemented public getter must not erase this observation.
        val snapshot = projectionSnapshot()
        val notes = mutableListOf(snapshot.note)
        var enabled: Boolean? = null
        if (unsupportedGetter == null) {
            val result = runCatching { binding.enabled?.invoke(instance) as? Boolean }
            enabled = result.getOrNull()
            result.exceptionOrNull()?.let { error ->
                val cause = cause(error)
                if (cause is AbstractMethodError || cause is NoSuchMethodError || cause is UnsupportedOperationException) {
                    unsupportedGetter = "getter 不受支持：${failure(error)}"
                } else notes.add("getter 读取不可用：${failure(error)}")
            }
        }
        unsupportedGetter?.let(notes::add)
        notes.add("SDK read-back only; physical display unverified")
        return XingruiDimState(enabled, snapshot.internal, notes.joinToString("; "))
    }
    private data class Snapshot(val internal: Boolean?, val note: String)
    private fun projectionSnapshot(): Snapshot {
        val notes = mutableListOf("dimClass=${instance.javaClass.name}")
        val naviResult = runCatching { binding.navi.invoke(instance) }
        val navi = naviResult.getOrNull()
        if (navi == null) return Snapshot(null, notes.joinToString("; ") +
            "; navigation service unavailable ${naviResult.exceptionOrNull()?.let(::failure).orEmpty()}")
        notes.add("naviClass=${navi.javaClass.name}")
        val internalResult = runCatching { field(navi, "isScreenProjection") as? Boolean }
        val internal = internalResult.getOrNull()
        notes.add(if (internal == null) "internal projection state unavailable ${internalResult.exceptionOrNull()?.let(::failure).orEmpty()}"
            else "isScreenProjection=$internal")
        val power = runCatching { field(navi, "mPowerOperation") }
        notes.add("mPowerOperation actualClass=${power.getOrNull()?.javaClass?.name ?: "unavailable"} " +
            power.exceptionOrNull()?.let(::failure).orEmpty())
        return Snapshot(internal, notes.joinToString("; "))
    }
    private fun cause(error: Throwable): Throwable {
        var result = error
        while (result is InvocationTargetException && result.targetException != null && result.targetException !== result)
            result = result.targetException
        return result
    }
    private fun failure(error: Throwable): String = cause(error).let { "${it.javaClass.simpleName}: ${it.message}" }
    private fun diagnostic(message: String) { runCatching { report(message) } }
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
