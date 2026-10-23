package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Test fixtures model the observed public E02 signatures. They are not vendor binaries or firmware proof. */
class XingruiFixtureContext
interface XingruiFixtureKey {
    fun onKeyPressed(code: Int): Boolean
    fun onKeyReleased(code: Int): Boolean
}
interface XingruiFixtureWatcher { fun onConnected(); fun onDisConnected() }
interface XingruiFixtureConnect {
    fun registerConnectWatcher(watcher: XingruiFixtureWatcher)
    fun unregisterConnectWatcher()
    fun connect()
    fun disconnect()
}
open class XingruiFixtureNavi
class XingruiFixturePower(private val events: MutableList<String>) {
    var fail = false
    fun disableScreenProjection(blocked: Boolean, mode: Int) {
        events.add("source:$blocked:$mode")
        if (fail) throw SecurityException("power refused")
    }
}
class XingruiFixtureLiveNavi(events: MutableList<String>) : XingruiFixtureNavi() {
    @JvmField var mPowerOperation: XingruiFixturePower? = XingruiFixturePower(events)
    @JvmField var isScreenProjection: Boolean? = false
}
abstract class XingruiFixtureDim {
    abstract fun setPresentationToDimSwitch(kind: Int, pkg: String, cls: String, show: Boolean)
    abstract fun getPresentationToDimSwitch(): Boolean
    abstract fun getNaviInteraction(): XingruiFixtureNavi?
    companion object {
        var factoryCalls = 0
        lateinit var current: XingruiFixtureDim
        @JvmStatic fun create(context: XingruiFixtureContext): XingruiFixtureDim { factoryCalls++; return current }
    }
}
class XingruiFixtureDimImpl : XingruiFixtureDim(), XingruiFixtureConnect {
    val events = mutableListOf<String>()
    var watcher: XingruiFixtureWatcher? = null
    var navi: XingruiFixtureNavi? = XingruiFixtureLiveNavi(events)
    var enabled = false
    var followsRequest = true
    var readFails = false
    var readAbstract = false
    var readMutatesInternal: Boolean? = null
    var readAttempts = 0
    var closeFails = false
    val requests = mutableListOf<Boolean>()
    override fun registerConnectWatcher(watcher: XingruiFixtureWatcher) { events.add("watch"); this.watcher = watcher }
    override fun unregisterConnectWatcher() { events.add("unwatch"); watcher = null }
    override fun connect() { events.add("connect") }
    override fun disconnect() { events.add("disconnect") }
    override fun setPresentationToDimSwitch(kind: Int, pkg: String, cls: String, show: Boolean) {
        events.add("request:$kind:$pkg:$cls:$show"); requests.add(show)
        if (!show && closeFails) throw SecurityException("release refused")
        enabled = show
        if (followsRequest) (navi as? XingruiFixtureLiveNavi)?.isScreenProjection = show
    }
    override fun getPresentationToDimSwitch(): Boolean {
        readAttempts++
        readMutatesInternal?.let { (navi as? XingruiFixtureLiveNavi)?.isScreenProjection = it }
        if (readAbstract) throw AbstractMethodError("getter not implemented on this firmware")
        if (readFails) throw SecurityException("read refused")
        events.add("read"); return enabled
    }
    override fun getNaviInteraction(): XingruiFixtureNavi? = navi
}
class XingruiFixtureDimWithoutGetter : XingruiFixtureConnect {
    val navi = XingruiFixtureLiveNavi(mutableListOf())
    fun setPresentationToDimSwitch(kind: Int, pkg: String, cls: String, show: Boolean) { navi.isScreenProjection = show }
    fun getNaviInteraction(): XingruiFixtureNavi = navi
    override fun registerConnectWatcher(watcher: XingruiFixtureWatcher) = Unit
    override fun unregisterConnectWatcher() = Unit
    override fun connect() = Unit
    override fun disconnect() = Unit
    companion object {
        @JvmStatic fun create(context: XingruiFixtureContext): XingruiFixtureDimWithoutGetter = XingruiFixtureDimWithoutGetter()
    }
}
class XingruiFixtureInput {
    var callback: XingruiFixtureKey? = null
    var result: IntArray? = intArrayOf(305)
    var fail = false
    val events = mutableListOf<String>()
    var codes = intArrayOf()
    fun requestKeysInterception(codes: IntArray, callback: XingruiFixtureKey): IntArray? {
        if (fail) throw SecurityException("input refused")
        events.add("request"); this.codes = codes.copyOf(); this.callback = callback; return result
    }
    fun abandonKeysInterception(callback: XingruiFixtureKey): Boolean {
        events.add("abandon"); if (this.callback === callback) this.callback = null; return true
    }
    companion object {
        var factoryCalls = 0
        lateinit var current: XingruiFixtureInput
        @JvmStatic fun create(context: XingruiFixtureContext): XingruiFixtureInput { factoryCalls++; return current }
    }
}
class XingruiFixtureKeys {
    companion object { const val KEYCODE_MEDIA_EXTRA = 400; const val KEYCODE_VOLUME_UP = 24 }
}
class XingruiFixtureBadInput {
    fun requestKeysInterception(codes: IntArray, callback: XingruiFixtureKey): Boolean = true
    fun abandonKeysInterception(callback: XingruiFixtureKey): Boolean = true
    companion object { @JvmStatic fun create(context: XingruiFixtureContext): XingruiFixtureBadInput = error("must not create") }
}
internal fun xingruiFixtureClass(name: String): Class<*> = when (name) {
    XingruiSdkContract.DIM -> XingruiFixtureDim::class.java
    XingruiSdkContract.NAVI -> XingruiFixtureNavi::class.java
    XingruiSdkContract.CONNECT -> XingruiFixtureConnect::class.java
    XingruiSdkContract.WATCHER -> XingruiFixtureWatcher::class.java
    XingruiSdkContract.INPUT -> XingruiFixtureInput::class.java
    XingruiSdkContract.KEY -> XingruiFixtureKey::class.java
    "com.ecarx.xui.adaptapi.input.KeyCode" -> XingruiFixtureKeys::class.java
    else -> throw ClassNotFoundException(name)
}
internal fun xingruiDimPort() = XingruiReflectiveDim(XingruiFixtureContext(), XingruiFixtureContext::class.java,
    "owner.pkg", "owner.Host", ::xingruiFixtureClass)
internal fun xingruiInputPort() = XingruiReflectiveInput(XingruiFixtureContext(), XingruiFixtureContext::class.java, ::xingruiFixtureClass)
internal class XingruiManualScheduler : XingruiScheduler {
    private data class Entry(val time: Long, val order: Int, val task: () -> Unit)
    private val tasks = mutableListOf<Entry>()
    var now = 0L
    private var next = 0
    override fun post(delayMillis: Long, task: () -> Unit) { tasks.add(Entry(now + delayMillis, next++, task)) }
    fun ready() = advance(0)
    fun advance(delta: Long) {
        val target = now + delta
        var guard = 0
        while (true) {
            check(++guard < 1000) { "unbounded scheduling" }
            val item = tasks.filter { it.time <= target }.minWithOrNull(compareBy<Entry> { it.time }.thenBy { it.order }) ?: break
            tasks.remove(item); now = item.time; item.task()
        }
        now = target
    }
}

class XingruiSdkContractTest {
    private lateinit var dim: XingruiFixtureDimImpl
    private lateinit var input: XingruiFixtureInput
    @Before fun setup() {
        dim = XingruiFixtureDimImpl(); XingruiFixtureDim.current = dim; XingruiFixtureDim.factoryCalls = 0
        input = XingruiFixtureInput(); XingruiFixtureInput.current = input; XingruiFixtureInput.factoryCalls = 0
    }
    @Test fun readOnlyProbeDoesNotCreateOrBindOrRegister() {
        val text = XingruiSdkContract.describe(XingruiFixtureContext::class.java, ::xingruiFixtureClass)
        assertFalse(text, text.contains("MISMATCH")); assertEquals(0, XingruiFixtureDim.factoryCalls)
        assertEquals(0, XingruiFixtureInput.factoryCalls); assertTrue(dim.events.isEmpty()); assertTrue(input.events.isEmpty())
    }
    @Test fun wrongInputReturnTypeFailsBeforeCallingFactory() {
        val error = runCatching { XingruiSdkContract.input(XingruiFixtureContext::class.java) {
            if (it == XingruiSdkContract.INPUT) XingruiFixtureBadInput::class.java else xingruiFixtureClass(it)
        } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertTrue(error!!.message!!.contains("requestKeysInterception"))
    }
    @Test fun missingClassIsExplicitMismatchAndDoesNotSkipOtherContract() {
        val text = XingruiSdkContract.describe(XingruiFixtureContext::class.java) {
            if (it == XingruiSdkContract.INPUT) throw ClassNotFoundException(it) else xingruiFixtureClass(it)
        }
        assertTrue(text.contains("Input required public signatures: MISMATCH"))
        assertTrue(text.contains("DIM required public signatures: MATCH"))
    }
    @Test fun factoryBaseNeedNotImplementLifecycleButCreatedObjectMust() {
        assertFalse(XingruiFixtureConnect::class.java.isAssignableFrom(XingruiFixtureDim::class.java))
        xingruiDimPort().watch({}, {})
        assertEquals(listOf("watch"), dim.events)
    }
    @Test fun requestPreservesLegacyRawArgumentsAndActualOwnerWithoutAssumingTheirOemMeaning() {
        xingruiDimPort().request(true)
        assertEquals(listOf("request:0:owner.pkg:owner.Host:true", "source:false:2"), dim.events)
    }
    @Test fun absentNavigationThrowsEvenIfSwitchReadBackIsTrue() {
        dim.navi = null
        assertNotNull(runCatching { xingruiDimPort().request(true) }.exceptionOrNull())
        assertTrue(dim.enabled); assertFalse(dim.events.any { it.startsWith("source:") })
    }
    @Test fun missingPrivateImplementationFieldRemainsUnknown() {
        dim.navi = XingruiFixtureNavi()
        val state = xingruiDimPort().read()
        assertNull(state.internal); assertTrue(state.note.contains("unavailable"))
    }
    @Test fun deniedGetterRetainsInternalStateWithoutBecomingConfirmation() {
        dim.readFails = true
        val state = xingruiDimPort().read()
        assertNull(state.enabled); assertEquals(false, state.internal)
        assertTrue(state.note.contains("SecurityException"))
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED,
            XingruiSwitchPolicy.verdict(false, state.enabled, state.internal, false, 0))
    }
    @Test fun wrappedAbstractGetterCannotEraseTheInternalValueReadBeforeIt() {
        val navi = dim.navi as XingruiFixtureLiveNavi
        navi.isScreenProjection = true
        dim.readAbstract = true; dim.readMutatesInternal = false
        val port = xingruiDimPort()
        val state = port.read()
        assertEquals(false, navi.isScreenProjection)
        assertEquals(true, state.internal); assertNull(state.enabled)
        assertTrue(state.note.contains("getter 不受支持")); assertTrue(state.note.contains("AbstractMethodError"))
        assertEquals(false, port.read().internal); assertEquals(1, dim.readAttempts)
    }
    @Test fun anAbsentOptionalGetterDoesNotPreventFactoryOrWriteOrIndependentReadback() {
        val load: (String) -> Class<*> = {
            if (it == XingruiSdkContract.DIM) XingruiFixtureDimWithoutGetter::class.java else xingruiFixtureClass(it)
        }
        assertNull(XingruiSdkContract.dim(XingruiFixtureContext::class.java, load).enabled)
        val port = XingruiReflectiveDim(XingruiFixtureContext(), XingruiFixtureContext::class.java,
            "owner.pkg", "owner.Host", load)
        port.request(true)
        assertEquals(true, port.read().internal); assertNull(port.read().enabled)
        assertTrue(port.read().note.contains("getter 不受支持"))
    }
    @Test fun requestDiagnosticsRecordIndependentBeforeAfterValuesClassesAndRawArguments() {
        val lines = mutableListOf<String>()
        val port = XingruiReflectiveDim(XingruiFixtureContext(), XingruiFixtureContext::class.java,
            "owner.pkg", "owner.Host", ::xingruiFixtureClass, lines::add)
        dim.readAbstract = true; port.request(true)
        assertTrue(lines.first().contains("申请前")); assertTrue(lines.first().contains("internal=false"))
        assertTrue(lines.last().contains("申请后")); assertTrue(lines.last().contains("internal=true"))
        assertTrue(lines.any { it.contains("mPowerOperation actualClass=${XingruiFixturePower::class.java.name}") })
        assertTrue(lines.any { it.contains("kind=0") && it.contains("powerMode=2") && it.contains("参数语义未证实") })
        assertEquals(0, dim.readAttempts)
        assertEquals(true, port.read().internal); assertEquals(1, dim.readAttempts)
    }
    @Test fun missingInternalStateDoesNotEraseAWorkingPublicGetter() {
        dim.navi = XingruiFixtureNavi(); dim.enabled = true
        val state = xingruiDimPort().read()
        assertNull(state.internal); assertEquals(true, state.enabled)
    }
    @Test fun diagnosticCallbackFailureCannotBlockTheWrite() {
        val port = XingruiReflectiveDim(XingruiFixtureContext(), XingruiFixtureContext::class.java,
            "owner.pkg", "owner.Host", ::xingruiFixtureClass) { throw IllegalStateException("log destination") }
        port.request(true)
        assertEquals(listOf(true), dim.requests); assertTrue(dim.events.contains("source:false:2"))
    }
    @Test fun inputCallbackPreservesPressAndReleaseReturnValuesAndRawResult() {
        val port = xingruiInputPort(); input.result = null
        val seen = mutableListOf<Int>()
        assertNull(port.request(intArrayOf(305), { seen.add(it); true }, { false }))
        assertTrue(input.callback!!.onKeyPressed(305)); assertFalse(input.callback!!.onKeyReleased(305))
        assertEquals(listOf(305), seen); port.abandon(); assertNull(input.callback)
    }
    @Test fun replacingRegistrationAbandonsPreviousCallbackOnce() {
        val port = xingruiInputPort()
        port.request(intArrayOf(305), { false }, { false })
        port.request(intArrayOf(88), { false }, { false })
        assertEquals(listOf("request", "abandon", "request"), input.events)
        port.abandon(); port.abandon(); assertEquals(2, input.events.count { it == "abandon" })
    }
    @Test fun sdkDiscoveryKeepsMediaNamesWithoutHardcodingTheirNumericValue() {
        assertEquals(listOf(400), xingruiInputPort().discover())
    }
}
