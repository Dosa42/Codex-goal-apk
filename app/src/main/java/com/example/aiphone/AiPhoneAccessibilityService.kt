package com.example.aiphone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import android.util.Log

/** Android owns lifecycle; the socket is resident and only reconnects after actual disconnect. */
class AiPhoneAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    private var destroyed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var epoch = UUID.randomUUID().toString()
    @Volatile private var revision = 0L
    @Volatile private var protocolReady = false
    @Volatile private var snapshot = JSONObject()
    private var fingerprint = ""
    private var nodeRefs = linkedMapOf<String, AccessibilityNodeInfo>()
    private var nodeHandles = NativeNodeHandles()
    @Volatile private var outbound = LinkedBlockingQueue<String>()
    // Only this worker traverses trees. Main owns Android mutations and power/window lifecycle.
    private val observations = Executors.newSingleThreadExecutor { Thread(it, "ai-phone-observation") }
    private val residentReads = Executors.newSingleThreadExecutor { Thread(it, "ai-phone-resident") }
    private val stateLock = Any()
    private val captureLock = Any()
    private val dirtyWindows = linkedSetOf<Int>()
    private val dirtyDisplays = linkedSetOf<Int>()
    private var dirtyAll = false
    private var invalidation = 0L
    private var globalInvalidation = 0L
    private val windowInvalidations = mutableMapOf<Int, Long>()
    private val displayInvalidations = mutableMapOf<Int, Long>()
    private data class CaptureStamp(val global: Long, val display: Long, val window: Long = 0)
    private val windowCaptures = mutableMapOf<Pair<Int, Int>, CaptureStamp>()
    private val displayCaptures = mutableMapOf<Int, CaptureStamp>()
    private var activeCaptureDisplay: Int? = null
    private var activeCaptureWindows: Set<Int>? = null
    @Volatile private var captureScheduled = false
    @Volatile private var captureRunning = false
    @Volatile private var windowTimes = emptyMap<Pair<Int, Int>, String>()
    private var lastCaptureAt = ""
    private data class RequestContext(val epoch: String, val queue: LinkedBlockingQueue<String>, val id: String, val arrived: Long)
    private val requestContext = ThreadLocal<RequestContext>()
    private data class WriteTiming(val context: RequestContext, val enqueued: Long)
    private val writeTimings = java.util.IdentityHashMap<String, WriteTiming>()
    private fun trace(stage: String, started: Long = System.nanoTime(), id: String? = null) {
        val context = requestContext.get()
        val now = System.nanoTime()
        Log.i("AiPhoneLatency", obj("requestId" to (id ?: context?.id ?: "event"), "serviceInstanceId" to (context?.epoch ?: epoch),
            "stage" to stage, "atNs" to now, "durationNs" to (now-started), "thread" to Thread.currentThread().name).toString())
    }
    private fun currentRequest() = socket?.isClosed != true && (requestContext.get()?.let { it.epoch == epoch && it.queue === outbound } ?: true)
    private fun sendRaw(raw: String) {
        if (socket == null || !currentRequest()) return
        val context = requestContext.get() ?: RequestContext(epoch, outbound, "event", System.nanoTime())
        val lockStart = System.nanoTime()
        synchronized(writeTimings) {
            trace("output_lock_wait", lockStart)
            writeTimings[raw] = WriteTiming(context, System.nanoTime())
            context.queue.offer(raw)
        }
    }
    private fun writeLoop(connection: Socket, queue: LinkedBlockingQueue<String>) {
        try { connection.getOutputStream().bufferedWriter(Charsets.UTF_8).use { out ->
            while (!Thread.currentThread().isInterrupted) {
                val raw = queue.take()
                val timing = synchronized(writeTimings) { writeTimings.remove(raw) }
                if(timing != null) requestContext.set(timing.context)
                try {
                    if(timing != null) trace("write_queue_wait", timing.enqueued)
                    val writeStart = System.nanoTime()
                    out.write(raw); out.newLine(); out.flush()
                    trace("socket_write", writeStart)
                } finally { requestContext.remove() }
            }
        } } catch (_: Exception) { try { connection.close() } catch (_: Exception) {} }
        finally { synchronized(writeTimings) { writeTimings.entries.removeAll { it.value.context.queue === queue } } }
    }
    // Ingest never waits for traversal. Posting admission to main orders helloAck before requests;
    // controls/mutations stay main-affine while read-only bulk work runs on the observation worker.
    private fun dispatch(line: String) {
        val arrival = System.nanoTime()
        val parsed = try { JSONObject(line) } catch (_: Exception) { JSONObject() }
        val context = RequestContext(epoch, outbound, parsed.optString("requestId", "handshake"), arrival)
        trace("arrival", arrival, context.id)
        main.post {
            if (context.epoch != epoch || context.queue !== outbound || socket == null || socket?.isClosed == true) return@post
            val bulk = parsed.optString("method") in setOf("observe", "apps")
            val work = Runnable {
                requestContext.set(context)
                try {
                    if (currentRequest()) { trace("dispatch", arrival); receive(line) }
                } finally { requestContext.remove() }
            }
            if (parsed.optString("method") == "observe" && parsed.optJSONObject("params")?.optString("mode") == "summary") residentReads.execute(work)
            else if (bulk) observations.execute(work) else work.run()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { scheduleDisplayCapture(displayId); powerLeases.observationChanged() }
        override fun onDisplayRemoved(displayId: Int) { scheduleDisplayCapture(displayId); powerLeases.observationChanged() }
        override fun onDisplayChanged(displayId: Int) { scheduleDisplayCapture(displayId); powerLeases.observationChanged() }
    }
    private val powerLeases by lazy { PowerLeases.get(this) }
    private val powerListener: (JSONObject) -> Unit = { state -> emitPowerState(state) }
    private fun emitPowerState(state: JSONObject) {
        emit(obj("type" to "event","event" to "power","serviceInstanceId" to epoch,"revision" to revision,
            "observedAt" to state.getString("observedAt"),"source" to "android_power","powerState" to state))
    }
    private lateinit var receipts: ActionReceipts
    private val wire by lazy { WireSchema(this) }
    private val observationClock: () -> String = { if (Build.VERSION.SDK_INT >= 26) Instant.now().toString() else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date()) }
    private fun time() = observationClock()
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().also { j -> values.forEach { j.put(it.first, it.second ?: JSONObject.NULL) } }
    private fun base() = obj("source" to "android_accessibility", "observedAt" to time(), "serviceInstanceId" to epoch, "revision" to revision)
    private fun emit(value: JSONObject, validated: Boolean = false) {
        if (!currentRequest()) return
        var start = System.nanoTime()
        if (!validated) { wire.validate(value); trace("outbound_validation", start) }
        start = System.nanoTime()
        val raw = value.toString(); trace("serialization", start)
        sendRaw(raw); trace("enqueue")
    }
    private fun emitFullState() = emitState(snapshot)
    private fun emitState(state: JSONObject) {
        // This envelope describes an existing observation, not a new clock read.
        emit(obj("type" to "event", "event" to "state", "serviceInstanceId" to state.getString("serviceInstanceId"),
            "revision" to state.getLong("revision"), "observedAt" to state.getString("observedAt"), "state" to state))
    }
    private fun onMain(block: () -> Unit) { val task = FutureTask { block() }; main.post(task); task.get() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0
        }
        receipts = ActionReceipts(this)
        alive = true
        powerLeases.bindService(this)
        powerLeases.addListener(powerListener)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        Thread({ connectLoop() }, "ai-phone-link").start()
    }
    private fun scheduleCapture(windowId: Int? = null) {
        synchronized(stateLock) {
            if (!alive) return
            invalidation++
            if (windowId == null || windowId < 0 || knownWindowDisplay(windowId) == null) {
                dirtyAll = true; globalInvalidation = invalidation
            } else {
                dirtyWindows.add(windowId); windowInvalidations[windowId] = invalidation
            }
            enqueueCaptureLocked()
        }
    }
    private fun scheduleDisplayCapture(displayId: Int) {
        synchronized(stateLock) {
            if (!alive) return
            invalidation++
            dirtyDisplays.add(displayId); displayInvalidations[displayId] = invalidation
            enqueueCaptureLocked()
        }
    }
    // Call these helpers under stateLock. Versions acknowledge only the actual captured scope;
    // unrelated events never prevent its acknowledgement, but later same-scope events still do.
    private fun knownWindowDisplay(windowId: Int): Int? {
        val windows = snapshot.optJSONArray("windows") ?: return null
        for(i in 0 until windows.length()) if(windows.getJSONObject(i).getInt("id") == windowId)
            return windows.getJSONObject(i).getInt("displayId")
        return null
    }
    private fun stampDirty(stamp: CaptureStamp?, display: Int, window: Int? = null): Boolean =
        stamp == null || stamp.global < globalInvalidation || stamp.display < (displayInvalidations[display] ?: 0L) ||
            (window != null && stamp.window < (windowInvalidations[window] ?: 0L))
    private fun scopeDirty(display: Int, window: Int? = null): Boolean {
        if(window != null) return stampDirty(windowCaptures[display to window],display,window)
        val windows = snapshot.optJSONArray("windows") ?: return true
        val ids = (0 until windows.length()).map { windows.getJSONObject(it) }.filter { it.getInt("displayId") == display }.map { it.getInt("id") }
        return if(ids.isEmpty()) stampDirty(displayCaptures[display],display)
            else ids.any { stampDirty(windowCaptures[display to it],display,it) }
    }
    private fun captureTouches(display: Int, window: Int? = null): Boolean {
        if(!captureRunning || (activeCaptureDisplay != null && activeCaptureDisplay != display)) return false
        val windows = activeCaptureWindows ?: return true
        return if(window != null) window in windows
            else windows.any { knownWindowDisplay(it)?.let { d -> d == display } ?: true }
    }
    private fun enqueueCaptureLocked() {
        if (!alive || captureScheduled) return
        captureScheduled = true
        val scheduledEpoch = epoch
        val scheduledInvalidation = invalidation
        observations.execute {
            var succeeded = false
            try {
                if (scheduledEpoch == epoch && alive) {
                    val selected = synchronized(stateLock) {
                        when {
                            dirtyAll -> null to null
                            dirtyDisplays.size == 1 && dirtyWindows.all { knownWindowDisplay(it) == dirtyDisplays.first() } -> dirtyDisplays.first() to null
                            dirtyDisplays.isNotEmpty() -> null to null
                            else -> null to dirtyWindows.toSet()
                        }
                    }
                    captureScoped(true, selected.first, selected.second)
                    succeeded = true
                }
            } catch (e: Exception) {
                if (scheduledEpoch == epoch && alive) emit(base().put("type","event").put("event","status").put("status","unavailable")
                    .put("error",obj("code" to "observation_unavailable","message" to (e.message ?: "Android observation failed"))))
            } finally {
                synchronized(stateLock) {
                    captureScheduled = false
                    // Events arriving during a capture get their own pass; never poll or heartbeat.
                    if (alive && (succeeded || invalidation != scheduledInvalidation) && (dirtyAll || dirtyDisplays.isNotEmpty() || dirtyWindows.isNotEmpty())) enqueueCaptureLocked()
                }
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { scheduleCapture(event?.windowId) }
    override fun onInterrupt() { if (alive) emit(base().put("type", "event").put("event", "status").put("status", "unavailable")) }
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        alive = false
        powerLeases.removeListener(powerListener)
        powerLeases.unbindService(this)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        socket?.close()
        synchronized(stateLock) {
            epoch = UUID.randomUUID().toString() // In-flight reads cannot publish after teardown.
            val retiredRefs = nodeRefs; val retiredHandles = nodeHandles
            nodeRefs = linkedMapOf(); nodeHandles = NativeNodeHandles(); snapshot = JSONObject()
            observations.execute { synchronized(captureLock) { retiredRefs.values.forEach { it.recycle() }; retiredHandles.resetEpoch() } }
        }
        observations.shutdown()
        residentReads.shutdown()
        synchronized(writeTimings) { writeTimings.clear() }
        if(::receipts.isInitialized) receipts.close()
        super.onDestroy()
    }
    private fun resetObservationEpoch(queue: LinkedBlockingQueue<String>) {
        synchronized(stateLock) {
            outbound = queue
            epoch = UUID.randomUUID().toString()
            revision = 0; protocolReady = false; fingerprint = ""
            val retiredRefs = nodeRefs; val retiredHandles = nodeHandles
            nodeRefs = linkedMapOf(); nodeHandles = NativeNodeHandles(); snapshot = JSONObject(); windowTimes = emptyMap()
            dirtyAll = true; dirtyWindows.clear(); dirtyDisplays.clear(); invalidation++; globalInvalidation = invalidation
            windowInvalidations.clear(); displayInvalidations.clear(); windowCaptures.clear(); displayCaptures.clear()
            observations.execute { synchronized(captureLock) { retiredRefs.values.forEach { it.recycle() }; retiredHandles.resetEpoch() } }
        }
    }
    private fun connectLoop() {
        while (alive) {
            var writer: Thread? = null
            try {
                val connection = Socket()
                connection.keepAlive = true
                connection.connect(InetSocketAddress("127.0.0.1", 8793), 5000)
                socket = connection
                val queue = LinkedBlockingQueue<String>()
                onMain {
                    if (!alive) return@onMain
                    resetObservationEpoch(queue)
                    emit(obj("type" to "hello", "protocol" to "ai-phone/2", "serviceInstanceId" to epoch, "capabilities" to capabilities(), "displays" to displayList()))
                    emitPowerState(powerLeases.state())
                    synchronized(stateLock) { enqueueCaptureLocked() }
                }
                writer = Thread({ writeLoop(connection, queue) }, "ai-phone-output").also { it.start() }
                connection.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    while (alive) {
                        val line = reader.readLine() ?: break
                        dispatch(line)
                    }
                }
            } catch (_: Exception) { /* Peer unavailable: reconnect, never replay requests. */ }
            finally {
                writer?.interrupt()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                protocolReady = false
            }
            if (alive) try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }
    private fun displayList(): JSONArray = JSONArray().also { out ->
        getSystemService(DisplayManager::class.java).displays.forEach { d ->
            val size=android.graphics.Point();d.getRealSize(size)
            out.put(obj("width" to size.x,"height" to size.y,"id" to d.displayId, "displayId" to d.displayId, "name" to d.name, "state" to d.state.toString(),
                "accessibleWindowsSupported" to (Build.VERSION.SDK_INT >= 30 || d.displayId == 0)))
        }
    }
    private val actionIds = mapOf("click" to AccessibilityNodeInfo.ACTION_CLICK, "long_click" to AccessibilityNodeInfo.ACTION_LONG_CLICK,
        "focus" to AccessibilityNodeInfo.ACTION_FOCUS, "accessibility_focus" to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
        "set_text" to AccessibilityNodeInfo.ACTION_SET_TEXT, "scroll_forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        "scroll_backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    private val globals = mapOf("home" to GLOBAL_ACTION_HOME, "back" to GLOBAL_ACTION_BACK, "recents" to GLOBAL_ACTION_RECENTS,
        "notifications" to GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to GLOBAL_ACTION_QUICK_SETTINGS,
        "power_dialog" to GLOBAL_ACTION_POWER_DIALOG, "lock_screen" to 8, "take_screenshot" to 9)
    private fun supportedGlobals(): Map<String,Int> = if(Build.VERSION.SDK_INT >= 30) {
        val available = systemActions.map { it.id }.toSet()
        globals.filterValues { it in available }
    } else globals.filterValues { it < 8 || Build.VERSION.SDK_INT >= 28 }
    private fun capabilities() = obj("operations" to JSONArray(listOf("discover","displays","observe","apps","launch","act","power","home")),
        "power" to powerLeases.capabilities(),
        "home" to homeCapability(),
        "nodeActions" to JSONArray(actionIds.keys),
        "globalActions" to JSONArray(globals.map { (name, _) -> obj("name" to name,"available" to (name in supportedGlobals()),"scope" to "untargeted", "reason" to "Android performGlobalAction has no display argument") }),
        "displayTargeting" to obj("node" to true,"launch" to (Build.VERSION.SDK_INT>=26),"global" to false,"power" to false,"home" to (Build.VERSION.SDK_INT>=26)),
        "accessibilityEnabled" to true, "gestures" to false, "screenshots" to false,
        "limitations" to JSONArray(listOf("Only Android-exposed accessibility content; canvas pixels are not semantic nodes", "Global actions cannot be isolated to phone display", "All-display window enumeration requires API 30; this API is ${Build.VERSION.SDK_INT}")))

    @Suppress("DEPRECATION")
    private fun capture(publish: Boolean) = captureScoped(publish, null, null)

    @Suppress("DEPRECATION")
    private fun captureScoped(publish: Boolean, selectedDisplay: Int?, selectedWindows: Set<Int>?) = synchronized(captureLock) capture@ {
        val started = System.nanoTime()
        val captureEpoch: String
        val handles: NativeNodeHandles
        val previous: JSONObject
        val startGlobal: Long
        val startWindows: Map<Int, Long>
        val startDisplays: Map<Int, Long>
        synchronized(stateLock) {
            if (!currentRequest()) return@capture
            captureEpoch = epoch; handles = nodeHandles; previous = snapshot
            startGlobal = globalInvalidation; startWindows = windowInvalidations.toMap(); startDisplays = displayInvalidations.toMap()
            captureRunning = true; activeCaptureDisplay = selectedDisplay; activeCaptureWindows = selectedWindows
        }
        val previousTimes = windowTimes
        val nextTimes = previousTimes.toMutableMap()
        val captureQueue = outbound
        val priorContext = requestContext.get()
        requestContext.set(priorContext ?: RequestContext(captureEpoch, captureQueue, "capture-${UUID.randomUUID()}", started))
        val ownedNodes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        val ownedWindows = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<android.view.accessibility.AccessibilityWindowInfo, Boolean>())
        fun own(node: AccessibilityNodeInfo) = node.also { ownedNodes.add(it) }
        fun selected(display: Int, window: Int) = (selectedDisplay == null || selectedDisplay == display) && (selectedWindows == null || window in selectedWindows)
        try {
            val nodes = JSONArray(); val wins = JSONArray()
            val refs = linkedMapOf<String, AccessibilityNodeInfo>()
            val oldIds = synchronized(stateLock) { nodeRefs.keys.toSet() }
            val previousRevision = previous.optLong("revision", 0)
            val previousNodes = previous.optJSONArray("nodes") ?: JSONArray()
            val oldNodes = (0 until previousNodes.length()).associate { val n=previousNodes.getJSONObject(it);n.getString("id") to n.toString() }
            val previousWindows = previous.optJSONArray("windows") ?: JSONArray()
            val oldWindows = (0 until previousWindows.length()).associate { val w=previousWindows.getJSONObject(it);w.getInt("id") to w.toString() }
            val unavailable = JSONArray()
            val windowPairs = mutableListOf<Pair<Int, android.view.accessibility.AccessibilityWindowInfo>>()
            if (Build.VERSION.SDK_INT >= 30) {
                val all = windowsOnAllDisplays
                for (i in 0 until all.size()) all.valueAt(i).forEach { windowPairs.add(all.keyAt(i) to it) }
            } else windows.forEach { windowPairs.add(0 to it) }
            ownedWindows.addAll(windowPairs.map { it.second })
            val liveKeys = windowPairs.map { it.first to it.second.id }.toSet()
            val retained = liveKeys.filter { !selected(it.first, it.second) }.toSet()
            handles.beginCapture(retained)
            for (i in 0 until previousNodes.length()) {
                val n = previousNodes.getJSONObject(i)
                if ((n.getInt("displayId") to n.getInt("windowId")) in retained) {
                    nodes.put(n)
                    synchronized(stateLock) { nodeRefs[n.getString("id")]?.let { refs[n.getString("id")] = own(AccessibilityNodeInfo.obtain(it)) } }
                }
            }
            nextTimes.keys.retainAll(liveKeys)
            windowPairs.sortedWith(compareBy({it.first}, {it.second.id})).forEach { (displayId, window) ->
                if ((displayId to window.id) in retained) {
                    // Preserve unselected content and its time; never replace all-display cache with a filter.
                    val old = (0 until previousWindows.length()).map { previousWindows.getJSONObject(it) }
                        .firstOrNull { it.getInt("id") == window.id && it.getInt("displayId") == displayId }
                    if (old != null) {
                        wins.put(old)
                        if (old.isNull("rootNodeId")) unavailable.put(obj("code" to "window_content_unavailable", "message" to "Window ${window.id} display $displayId exposes no root in resident observation"))
                    } else {
                        val b=Rect();window.getBoundsInScreen(b)
                        wins.put(obj("id" to window.id,"displayId" to displayId,"packageName" to null,"title" to window.title?.toString(),
                            "focused" to window.isFocused,"active" to window.isActive,"rootNodeId" to null,"type" to window.type,"layer" to window.layer,
                            "bounds" to obj("left" to b.left,"top" to b.top,"right" to b.right,"bottom" to b.bottom)))
                        unavailable.put(obj("code" to "window_content_not_captured", "message" to "Window ${window.id} display $displayId is outside the selected capture scope"))
                    }
                    return@forEach
                }
                val windowBounds=Rect();window.getBoundsInScreen(windowBounds)
                val root = window.root?.let { own(it) }
                if(root==null) unavailable.put(obj("code" to "window_content_unavailable","message" to "Window ${window.id} display $displayId exposes no root"))
                val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
                fun id(node: AccessibilityNodeInfo): String = handles.id(displayId, window.id, node)
                val rootId = root?.let { id(it) }
                wins.put(obj("id" to window.id, "displayId" to displayId, "packageName" to root?.packageName?.toString(),
                    "title" to window.title?.toString(), "focused" to window.isFocused, "active" to window.isActive, "rootNodeId" to rootId,
                    "type" to window.type, "layer" to window.layer,"bounds" to obj("left" to windowBounds.left,"top" to windowBounds.top,"right" to windowBounds.right,"bottom" to windowBounds.bottom)))
                if (root != null) queue.add(root to null)
                val visited = HashSet<String>()
                while (queue.isNotEmpty()) {
                    val (node, parent) = queue.removeFirst()
                    val nodeId = id(node)
                    if (!visited.add(nodeId)) { continue }
                    refs[nodeId] = node
                    val children = JSONArray()
                    val unavailableChildren = JSONArray()
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)?.let { own(it) }
                        if(child==null) { unavailableChildren.put(i); continue }
                        children.put(id(child)); queue.add(child to nodeId)
                    }
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    val actions = JSONArray()
                    node.actionList.forEach { action -> actions.put(obj("id" to action.id, "label" to action.label?.toString(), "name" to (actionIds.entries.find { it.value == action.id }?.key ?: "android_action_${action.id}"), "available" to (action.id in actionIds.values))) }
                    nodes.put(obj("id" to nodeId, "parentId" to parent, "childIds" to children, "childCount" to node.childCount, "unavailableChildIndices" to unavailableChildren, "windowId" to window.id, "displayId" to displayId,
                        "packageName" to node.packageName?.toString(), "className" to node.className?.toString(), "text" to node.text?.toString(),
                        "contentDescription" to node.contentDescription?.toString(), "viewIdResourceName" to node.viewIdResourceName,
                        "bounds" to obj("left" to bounds.left,"top" to bounds.top,"right" to bounds.right,"bottom" to bounds.bottom),
                        "enabled" to node.isEnabled,"clickable" to node.isClickable,"longClickable" to node.isLongClickable,"focusable" to node.isFocusable,
                        "focused" to node.isFocused,"accessibilityFocused" to node.isAccessibilityFocused,"editable" to node.isEditable,
                        "checkable" to node.isCheckable,"checked" to node.isChecked,"selected" to node.isSelected,"scrollable" to node.isScrollable,
                        "password" to node.isPassword,"visibleToUser" to node.isVisibleToUser,"actions" to actions))
                }
            }
            val displays = displayList()
            val unavailableChanged = unavailable.toString() != (previous.optJSONArray("unavailable") ?: JSONArray()).toString()
            val nextFingerprint = obj("displays" to displays,"windows" to wins,"nodes" to nodes,"unavailable" to unavailable).toString()
            val observedAt = time()
            liveKeys.filter { it !in retained }.forEach { nextTimes[it] = observedAt }
            var changed: Boolean
            var freshnessChanged = false
            val committed: JSONObject
            synchronized(stateLock) {
                if (!currentRequest() || epoch != captureEpoch || nodeHandles !== handles || captureQueue !== outbound) {
                    return@capture
                }
                changed = nextFingerprint != fingerprint
                handles.commitCapture()
                nodeRefs.values.forEach { it.recycle() }; nodeRefs = refs
                refs.values.forEach { ownedNodes.remove(it) }
                val coveredKeys = (liveKeys + previousTimes.keys).filter { selected(it.first,it.second) }.toMutableSet()
                if(selectedDisplay != null && selectedWindows != null) selectedWindows.forEach { coveredKeys.add(selectedDisplay to it) }
                windowCaptures.keys.retainAll(liveKeys + coveredKeys)
                // A newly enumerated unselected window has not been captured, even if Android
                // reused an ID that was previously observed empty or removed.
                val previousKeys = (0 until previousWindows.length()).map { previousWindows.getJSONObject(it) }
                    .map { it.getInt("displayId") to it.getInt("id") }.toSet()
                retained.filter { it !in previousKeys }.forEach { windowCaptures.remove(it) }
                coveredKeys.forEach { (display, window) ->
                    windowCaptures[display to window] = CaptureStamp(startGlobal,startDisplays[display] ?: 0L,startWindows[window] ?: 0L)
                    if((windowInvalidations[window] ?: 0L) == (startWindows[window] ?: 0L)) dirtyWindows.remove(window)
                }
                if(selectedWindows == null) {
                    val coveredDisplays = if(selectedDisplay != null) setOf(selectedDisplay) else
                        liveKeys.map { it.first }.toSet() + previousTimes.keys.map { it.first } + startDisplays.keys +
                            (0 until displays.length()).map { displays.getJSONObject(it).getInt("displayId") }
                    coveredDisplays.forEach { display ->
                        displayCaptures[display] = CaptureStamp(startGlobal,startDisplays[display] ?: 0L)
                        if((displayInvalidations[display] ?: 0L) == (startDisplays[display] ?: 0L)) dirtyDisplays.remove(display)
                    }
                    if(selectedDisplay == null && globalInvalidation == startGlobal) dirtyAll = false
                }
                val dirty = dirtyAll || dirtyDisplays.isNotEmpty() || dirtyWindows.isNotEmpty()
                val pending = dirty && alive
                val freshness = if(dirty) "stale" else if(retained.isEmpty()) "captured" else "resident"
                freshnessChanged = previous.optString("freshness") != freshness || previous.optBoolean("capturePending") != pending
                changed = changed || freshnessChanged
                if (changed) { revision++; fingerprint = nextFingerprint }
                val coverageTime = if(retained.isEmpty()) observedAt else nextTimes.values.minOrNull() ?: observedAt
                committed = obj("source" to "android_accessibility", "observedAt" to coverageTime, "serviceInstanceId" to captureEpoch, "revision" to revision)
                    .put("displays",displays).put("windows",wins).put("nodes",nodes).put("unavailable",unavailable).put("mode","full")
                    .put("freshness",freshness).put("capturePending",pending)
                snapshot = committed; windowTimes = nextTimes; lastCaptureAt = observedAt
            }
            trace("capture", started)
            if (publish && changed && (previous.length() == 0 || unavailableChanged || freshnessChanged)) {
                // Freshness/pending and unavailable have no typed delta fields: publish a full state on those transitions.
                emitState(committed)
            } else if (publish && changed) {
                val changedNodes=JSONArray();val changedWindows=JSONArray();val windowIds=mutableSetOf<Int>()
                for(i in 0 until nodes.length()) { val n=nodes.getJSONObject(i);if(oldNodes[n.getString("id")]!=n.toString()) changedNodes.put(n) }
                for(i in 0 until wins.length()) { val w=wins.getJSONObject(i);windowIds.add(w.getInt("id"));if(oldWindows[w.getInt("id")]!=w.toString()) changedWindows.put(w) }
                emit(obj("serviceInstanceId" to captureEpoch,"revision" to committed.getLong("revision"),"observedAt" to committed.getString("observedAt"))
                    .put("type","event").put("event","delta").put("baseRevision",previousRevision)
                    .put("delta",obj("displays" to displays,"windowsUpsert" to changedWindows,"windowsRemoved" to JSONArray(oldWindows.keys-windowIds),
                        "nodesUpsert" to changedNodes,"nodesRemoved" to JSONArray(oldIds-refs.keys))))
            }
        } finally {
            ownedNodes.forEach { it.recycle() }; ownedWindows.forEach { it.recycle() }
            handles.abortCapture()
            synchronized(stateLock) { captureRunning = false; activeCaptureDisplay = null; activeCaptureWindows = null }
            if(priorContext == null) requestContext.remove() else requestContext.set(priorContext)
        }
    }

    private class Failure(val code: String, message: String): Exception(message)
    private fun fail(code: String, message: String): Nothing = throw Failure(code,message)
    private fun requiredString(p: JSONObject, key: String): String = (p.opt(key) as? String)?.takeIf { it.isNotEmpty() } ?: fail("invalid_params","$key must be a nonempty string")
    private fun integer(p: JSONObject, key: String, default: Long? = null): Long {
        val v = p.opt(key) ?: return default ?: fail("invalid_params","$key required")
        if (v !is Number || v.toDouble() != v.toLong().toDouble()) fail("invalid_params","$key must be an integer")
        return v.toLong()
    }
    private fun allowed(p: JSONObject, fields: Set<String>) {
        val extra = p.keys().asSequence().filter { it !in fields }.toList()
        if(extra.isNotEmpty()) fail("invalid_params", "Unknown fields: $extra")
    }
    private fun displayId(p: JSONObject): Int {
        val id = integer(p,"displayId",0)
        if(id < 0 || id > Int.MAX_VALUE) fail("invalid_params","displayId outside Android integer range")
        return id.toInt()
    }
    private fun receive(line: String) {
        var requestId = "invalid-frame"
        var method = "unknown"
        var dispatchedMutationFrame: JSONObject? = null
        val receiveStarted = System.nanoTime()
        try {
            val frame = JSONObject(line)
            requestId = (frame.opt("requestId") as? String)?.takeIf { it.isNotEmpty() } ?: requestId
            method = (frame.opt("method") as? String)?.takeIf { it.isNotEmpty() } ?: method
            if(frame.optString("type")=="helloAck" && frame.optString("protocol")!="ai-phone/2") {
                protocolReady=false
                socket?.close()
                fail("protocol_mismatch","Expected ai-phone/2; connection closed")
            }
            val validationStart = System.nanoTime()
            try { wire.validate(frame) } catch(e:IllegalArgumentException) { fail("invalid_frame",e.message ?: "Invalid frame") }
            trace("inbound_validation", validationStart)
            if (frame.optString("type") == "helloAck") {
                protocolReady=true
                return
            }
            if(!protocolReady) fail("handshake_required","ai-phone/2 helloAck required before requests")
            allowed(frame,setOf("type","requestId","method","params"))
            requestId = requiredString(frame,"requestId")
            if (frame.optString("type") != "request") fail("invalid_frame","expected request")
            method = requiredString(frame,"method")
            val params = frame.optJSONObject("params") ?: fail("invalid_params","params must be an object")
            when(method) {
                "discover", "displays" -> allowed(params,emptySet())
                "apps" -> allowed(params,setOf("query","launchableOnly"))
                "observe" -> allowed(params,setOf("displayId","windowId","selector","mode"))
                "act" -> allowed(params,setOf("requestId","serviceInstanceId","revision","displayId","windowId","nodeId","action","args","scope"))
                "launch" -> allowed(params,setOf("requestId","packageName","activity","displayId"))
                "home" -> allowed(params,setOf("requestId","displayId"))
                "power" -> allowed(params,when(params.optString("operation")) {
                    "status" -> setOf("operation")
                    "acquire" -> setOf("operation","requestId","owner","scope","deadlineAfterMs")
                    "release" -> setOf("operation","requestId","leaseId")
                    else -> fail("invalid_params","Unknown power operation")
                })
            }
            val mutation = method == "act" || method == "launch" || method == "home" || (method == "power" && params.optString("operation") != "status")
            if (mutation) {
                if (requiredString(params,"requestId") != requestId) fail("invalid_params","inner and outer requestId differ")
                val lookupStart = System.nanoTime()
                val replay = receipts.lookupSerialized(requestId, frame)
                trace("receipt_lookup", lookupStart)
                if (replay != null) {
                    wire.validate(ReceiptJson.parseObject(replay))
                    // Do not reserialize immutable history through Android's Double parser.
                    sendRaw(replay)
                    return
                }
            }
            val opStart = System.nanoTime()
            val result = when (method) {
                "discover" -> base().put("protocol","ai-phone/2").put("capabilities",capabilities()).put("schema",wire.schema).put("schemaSha256",wire.sha256).put("connection","online").put("displays",displayList())
                "displays" -> base().put("displays",displayList())
                "observe" -> observe(params)
                "apps" -> apps(params)
                "act" -> act(params, requestId, frame)
                "launch" -> launch(params, requestId, frame)
                "home" -> home(params, requestId) {
                    receipts.begin(requestId,frame)
                    dispatchedMutationFrame=frame
                }
                "power" -> {
                    if(params.getString("operation")=="status") obj("operation" to "status","state" to powerLeases.state())
                    else {
                        if(params.getString("operation")=="acquire") powerLeases.validateAcquire(params)
                        receipts.begin(requestId,frame)
                        dispatchedMutationFrame=frame
                        if(params.getString("operation")=="acquire") powerLeases.acquire(requestId,params)
                        else powerLeases.release(requestId,params.getString("leaseId"))
                    }
                }
                else -> fail("unsupported_method","Unknown native method: $method")
            }
            trace("operation", opStart)
            val response = obj("type" to "response","requestId" to requestId,"method" to method,"ok" to true,"result" to result)
            val responseValidationStart = System.nanoTime()
            wire.validate(response); trace("outbound_validation", responseValidationStart)
            if (mutation) {
                val journalStart = System.nanoTime()
                receipts.complete(requestId,frame,response); trace("receipt_complete", journalStart)
            }
            dispatchedMutationFrame=null
            emit(response, true)
        } catch (e: Exception) {
            val cause = e as? Failure
            val response=obj("type" to "response","requestId" to requestId,"method" to method,"ok" to false,"error" to obj("code" to (cause?.code ?: when(e) { is ActionReceipts.Conflict -> e.code; is PowerLeases.Failure -> e.code; else -> "native_error" }),"message" to (e.message ?: e.javaClass.simpleName),"currentScope" to obj("serviceInstanceId" to epoch,"revision" to revision)))
            dispatchedMutationFrame?.let { receipts.complete(requestId,it,response) }
            emit(response)
        } finally { trace("receive_complete", receiveStarted) }
    }
    private fun observe(p: JSONObject): JSONObject {
        val display = displayId(p)
        if (Build.VERSION.SDK_INT < 30 && display != 0) fail("unsupported_display","All-display windows require API 30")
        val mode = p.optString("mode","full")
        if (mode !in listOf("full","summary")) fail("invalid_params","mode must be full or summary")
        val selector = if(p.has("selector")) p.optJSONObject("selector") ?: fail("invalid_params","selector must be an exact-match object") else null
        if (mode == "full") captureScoped(true, display, if(p.has("windowId")) setOf(integer(p,"windowId").toInt()) else null)
        if (!currentRequest()) fail("stale_handle", "Connection changed while observing")
        val resident: JSONObject
        val residentTimes: Map<Pair<Int, Int>, String>
        synchronized(stateLock) { resident = snapshot; residentTimes = windowTimes }
        if (!resident.has("windows")) fail("observation_unavailable", "No resident observation yet")
        val wins = JSONArray(); val nodes = JSONArray()
        val allWins=resident.getJSONArray("windows");val allNodes=resident.getJSONArray("nodes")
        for(i in 0 until allWins.length()) { val w=allWins.getJSONObject(i);if(w.getInt("displayId")==display && (!p.has("windowId") || w.getLong("id")==integer(p,"windowId"))) wins.put(w) }
        for(i in 0 until allNodes.length()) {
            val n=allNodes.getJSONObject(i)
            if(n.getInt("displayId")!=display || (p.has("windowId") && n.getLong("windowId")!=integer(p,"windowId"))) continue
            if(selector!=null && selector.keys().asSequence().any { key -> when(key) {
                "nodeId" -> n.getString("id")!=selector.getString(key)
                "textContains" -> !n.optString("text","").contains(selector.getString(key))
                "action" -> (0 until n.getJSONArray("actions").length()).none { n.getJSONArray("actions").getJSONObject(it).optString("name")==selector.getString(key) }
                else -> !n.has(key) || n.opt(key)!=selector.opt(key)
            } }) continue
            nodes.put(n)
        }
        val pending: Boolean
        val stale: Boolean
        synchronized(stateLock) {
            val window = if(p.has("windowId")) integer(p,"windowId").toInt() else null
            val dirty = scopeDirty(display,window)
            val active = captureTouches(display,window)
            pending = active || (captureScheduled && dirty)
            stale = dirty || (mode == "summary" && active)
        }
        val observedAt = if(mode == "full") lastCaptureAt else (0 until wins.length())
            .mapNotNull { val w=wins.getJSONObject(it); residentTimes[w.getInt("displayId") to w.getInt("id")] }.minOrNull() ?: resident.getString("observedAt")
        return obj("source" to "android_accessibility", "serviceInstanceId" to resident.getString("serviceInstanceId"), "revision" to resident.getLong("revision"), "observedAt" to observedAt)
            .put("displays",resident.getJSONArray("displays")).put("windows",wins).put("nodes",if(mode=="full") nodes else JSONArray()).put("nodeCount",nodes.length()).put("mode",mode)
            .put("unavailable",resident.getJSONArray("unavailable")).put("fullContentAvailable",true)
            .put("freshness",if(stale) "stale" else if(mode=="full") "captured" else "resident").put("capturePending",pending)
    }
    private fun paramsScope(p: JSONObject) = p.optString("scope","")
    private fun act(p: JSONObject, requestId: String, frame: JSONObject): JSONObject {
        val action=requiredString(p,"action");val display=displayId(p)
        val args=if(p.has("args")) p.optJSONObject("args") ?: fail("invalid_params","args must be an object") else JSONObject()
        if(action !in globals && action !in actionIds) fail("unsupported_action",action)
        if(requiredString(p,"serviceInstanceId")!=epoch || integer(p,"revision")!=revision) fail("stale_handle","Re-observe current epoch/revision")
        val before=revision
        var target: AccessibilityNodeInfo?=null
        var bundle: Bundle?=null
        try {
            if(action in globals) {
                if(paramsScope(p)!="device") fail("invalid_params","Global actions require explicit scope=device")
                if(action !in supportedGlobals()) fail("unsupported_action","System does not currently expose $action")
                if(display!=0) fail("unsupported_display","Global Android actions cannot be isolated to a requested display")
                if(args.length()!=0) fail("invalid_params","Global action args must be empty")
            } else {
                val nodeId=requiredString(p,"nodeId");val window=integer(p,"windowId")
                if(!nodeId.startsWith("$display:$window:")) fail("stale_handle","Node display/window mismatch")
                synchronized(stateLock) {
                    if(scopeDirty(display,window.toInt())) fail("stale_handle","Window invalidated; re-observe before acting")
                    target=nodeRefs[nodeId]?.let { AccessibilityNodeInfo.obtain(it) } ?: fail("stale_handle","Node no longer exists")
                }
                // A refreshed child alone does not prove its window/root still exists. Compare actual
                // Android identities, not text, coordinates, or ordinal position; no whole-tree rescan.
                val liveWindows = if(Build.VERSION.SDK_INT>=30) windowsOnAllDisplays.get(display) ?: emptyList() else windows
                try {
                    val liveWindow = liveWindows.firstOrNull { it.id.toLong()==window } ?: fail("stale_handle","Window no longer exists")
                    val liveRoot = liveWindow.root ?: fail("stale_handle","Window root unavailable")
                    try {
                        synchronized(stateLock) {
                            val ws=snapshot.getJSONArray("windows")
                            val oldWindow=(0 until ws.length()).map { ws.getJSONObject(it) }.firstOrNull { it.getInt("displayId")==display && it.getLong("id")==window }
                            val oldRoot=oldWindow?.optString("rootNodeId")?.let { nodeRefs[it] }
                            if(oldRoot==null || oldRoot!=liveRoot) fail("stale_handle","Window native identity changed")
                        }
                    } finally { liveRoot.recycle() }
                } finally { liveWindows.forEach { it.recycle() } }
                if(!target!!.refresh() || target!!.windowId.toLong()!=window) fail("stale_handle","Node cannot be refreshed in its original window")
                if(target!!.actionList.none { it.id==actionIds[action] }) fail("unsupported_action","Node does not expose $action")
                if(action=="set_text") {
                    if(args.length()!=1 || args.opt("text") !is String) fail("invalid_params","set_text requires only args.text string")
                    bundle=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getString("text")) }
                } else if(args.length()!=0) fail("invalid_params","Action args must be empty")
            }
            val accepted = synchronized(stateLock) {
                if(requiredString(p,"serviceInstanceId")!=epoch || integer(p,"revision")!=revision || (action !in globals && scopeDirty(display,integer(p,"windowId").toInt())))
                    fail("stale_handle","Observation changed before dispatch")
                if(action !in globals && nodeRefs[requiredString(p,"nodeId")] != target)
                    fail("stale_handle","Refreshed native identity differs from the selected handle")
                receipts.begin(requestId,frame)
                if(action in globals) performGlobalAction(globals.getValue(action)) else target!!.performAction(actionIds.getValue(action),bundle)
            }
            scheduleCapture(if(action in globals) null else integer(p,"windowId").toInt())
            return base().put("accepted",accepted).put("observedOutcome","unverified").put("beforeRevision",before).put("afterRevision",revision)
                .put("scope",if(action in globals) "untargeted" else "node_display").put("displayId",display).put("requestId",requestId)
        } finally { target?.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun apps(p: JSONObject): JSONObject {
        val list=JSONArray()
        packageManager.getInstalledApplications(0).sortedBy { it.packageName }.forEach { app ->
            val intent=packageManager.getLaunchIntentForPackage(app.packageName)
            val label=packageManager.getApplicationLabel(app).toString()
            if(p.optBoolean("launchableOnly",false) && intent==null) return@forEach
            if(p.has("query") && !app.packageName.contains(p.getString("query"),true) && !label.contains(p.getString("query"),true)) return@forEach
            list.put(obj("packageName" to app.packageName,"label" to packageManager.getApplicationLabel(app).toString(),"enabled" to app.enabled,"launchable" to (intent!=null),"activities" to JSONArray().also { activities ->
                    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName),0).forEach { resolved ->
                        activities.put(obj("name" to resolved.activityInfo.name,"label" to resolved.loadLabel(packageManager).toString(),"launchable" to (resolved.activityInfo.enabled && resolved.activityInfo.exported)))
                    }
                }))
        }
        return base().put("apps",list)
    }
    private fun resolvedHome(): ComponentName? {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved=packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        if(!resolved.enabled || !resolved.exported || !resolved.applicationInfo.enabled) return null
        val component=ComponentName(resolved.packageName,resolved.name)
        // A chooser is not a default HOME launcher. Require an actual matching HOME candidate.
        return component.takeIf { packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY).any {
            it.activityInfo?.let { info -> info.packageName==component.packageName && info.name==component.className } == true
        } }
    }
    private fun homeCapability(): JSONObject {
        val reason=if(Build.VERSION.SDK_INT<26) "Explicit display launch requires API26" else try {
            if(resolvedHome()==null) "No enabled exported default HOME launcher resolved" else null
        } catch(e:Exception) { e.message ?: "HOME resolution unavailable" }
        return obj("available" to (reason==null),"displayTargeting" to true,"route" to "resolved_home_intent")
            .also { if(reason!=null) it.put("reason",reason) }
    }
    private fun home(p: JSONObject, requestId: String, dispatch: () -> Unit): JSONObject {
        val display=displayId(p)
        if(Build.VERSION.SDK_INT<26 || getSystemService(DisplayManager::class.java).getDisplay(display)==null)
            fail("unsupported_display","Target display or explicit launch API unavailable")
        if(display!=0 && !packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
            fail("unsupported_display","Secondary display activity launch unavailable")
        val component=resolvedHome() ?: fail("home_unavailable","No enabled exported default HOME launcher resolved")
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT>=29 && !getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(this,display,intent))
            fail("unsupported_display","Android disallows HOME launch on requested display")
        dispatch()
        startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        return base().put("accepted",true).put("observedOutcome","unverified").put("requestId",requestId)
            .put("packageName",component.packageName).put("componentName",component.flattenToString()).put("displayId",display).put("scope","requested_display")
    }
    private fun launch(p: JSONObject, requestId: String, frame: JSONObject): JSONObject {
        val packageName=requiredString(p,"packageName");val display=displayId(p)
        if(getSystemService(DisplayManager::class.java).getDisplay(display)==null) fail("unsupported_display","Display does not exist")
        if(Build.VERSION.SDK_INT<26) fail("unsupported_display","Explicit launch display requires API 26")
        val intent=packageManager.getLaunchIntentForPackage(packageName) ?: fail("not_launchable",packageName)
        if(p.has("activity")) {
            val activity=requiredString(p,"activity")
            val component=ComponentName(packageName,activity)
            val info=packageManager.getActivityInfo(component,0)
            if(!info.exported || !info.enabled) fail("not_launchable","Activity not exported/enabled")
            intent.component=component
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options=ActivityOptions.makeBasic().setLaunchDisplayId(display)
        val before=revision
        receipts.begin(requestId,frame)
        startActivity(intent,options.toBundle())
        return base().put("accepted",true).put("observedOutcome","unverified").put("beforeRevision",before).put("afterRevision",revision).put("displayId",display).put("scope","requested_display").put("requestId",requestId).put("packageName",packageName)
    }
}
