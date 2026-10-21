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

/** Android owns lifecycle; the socket is resident and only reconnects after actual disconnect. */
class AiPhoneAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    @Volatile private var socket: Socket? = null
    private var epoch = UUID.randomUUID().toString()
    private var revision = 0L
    private var protocolReady = false
    private var snapshot = JSONObject()
    private var fingerprint = ""
    private var nodeRefs = linkedMapOf<String, AccessibilityNodeInfo>()
    private val nodeHandles = NativeNodeHandles()
    private var outbound = LinkedBlockingQueue<String>()
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { scheduleCapture(); powerLeases.observationChanged() }
        override fun onDisplayRemoved(displayId: Int) { scheduleCapture(); powerLeases.observationChanged() }
        override fun onDisplayChanged(displayId: Int) { scheduleCapture(); powerLeases.observationChanged() }
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
    private fun emit(value: JSONObject) { wire.validate(value); if (socket != null) outbound.offer(value.toString()) }
    private fun eventBase() = base().also { it.remove("source") }
    private fun emitFullState() {
        // This envelope describes an existing observation, not a new clock read.
        emit(obj("type" to "event", "event" to "state", "serviceInstanceId" to snapshot.getString("serviceInstanceId"),
            "revision" to snapshot.getLong("revision"), "observedAt" to snapshot.getString("observedAt"), "state" to snapshot))
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
    private var captureScheduled=false
    private fun scheduleCapture() {
        if(!alive || captureScheduled) return
        captureScheduled=true
        main.post {
            captureScheduled=false
            if(alive) try { capture(true) } catch(e:Exception) {
                emit(base().put("type","event").put("event","status").put("status","unavailable")
                    .put("error",obj("code" to "observation_unavailable","message" to (e.message ?: "Android observation failed"))))
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { scheduleCapture() }
    override fun onInterrupt() { if (alive) emit(base().put("type", "event").put("event", "status").put("status", "unavailable")) }
    override fun onDestroy() {
        alive = false
        powerLeases.removeListener(powerListener)
        powerLeases.unbindService(this)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        socket?.close()
        nodeRefs.values.forEach { it.recycle() }
        nodeHandles.resetEpoch()
        if(::receipts.isInitialized) receipts.close()
        super.onDestroy()
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
                    outbound = queue
                    epoch = UUID.randomUUID().toString()
                    revision = 0
                    protocolReady = false
                    fingerprint = ""
                    nodeHandles.resetEpoch()
                    emit(obj("type" to "hello", "protocol" to "ai-phone/2", "serviceInstanceId" to epoch, "capabilities" to capabilities(), "displays" to displayList()))
                    capture(false)
                    emitFullState()
                    emitPowerState(powerLeases.state())
                }
                writer = Thread({
                    try { connection.getOutputStream().bufferedWriter(Charsets.UTF_8).use { out ->
                        while (!Thread.currentThread().isInterrupted) { out.write(queue.take()); out.newLine(); out.flush() }
                    } } catch (_: Exception) { try { connection.close() } catch (_: Exception) {} }
                }, "ai-phone-output").also { it.start() }
                connection.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    while (alive) {
                        val line = reader.readLine() ?: break
                        onMain { receive(line) }
                    }
                }
            } catch (_: Exception) { /* Peer unavailable: reconnect, never replay requests. */ }
            finally {
                writer?.interrupt()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
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
    private fun capture(publish: Boolean) {
        nodeHandles.beginCapture()
        val nodes = JSONArray(); val wins = JSONArray()
        val refs = linkedMapOf<String, AccessibilityNodeInfo>()
        val oldIds = nodeRefs.keys.toSet()
        val previousRevision = revision
        val previousNodes = snapshot.optJSONArray("nodes") ?: JSONArray()
        val oldNodes = (0 until previousNodes.length()).associate { val n=previousNodes.getJSONObject(it);n.getString("id") to n.toString() }
        val previousWindows = snapshot.optJSONArray("windows") ?: JSONArray()
        val oldWindows = (0 until previousWindows.length()).associate { val w=previousWindows.getJSONObject(it);w.getInt("id") to w.toString() }
        val unavailable = JSONArray()
        val windowPairs = mutableListOf<Pair<Int, android.view.accessibility.AccessibilityWindowInfo>>()
        if (Build.VERSION.SDK_INT >= 30) {
            val all = windowsOnAllDisplays
            for (i in 0 until all.size()) all.valueAt(i).forEach { windowPairs.add(all.keyAt(i) to it) }
        } else windows.forEach { windowPairs.add(0 to it) }
        windowPairs.sortedWith(compareBy({it.first}, {it.second.id})).forEach { (displayId, window) ->
            val windowBounds=Rect();window.getBoundsInScreen(windowBounds)
            val root = window.root
            if(root==null) unavailable.put(obj("code" to "window_content_unavailable","message" to "Window ${window.id} display $displayId exposes no root"))
            val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
            fun id(node: AccessibilityNodeInfo): String = nodeHandles.id(displayId, window.id, node)
            val rootId = root?.let { id(it) }
            wins.put(obj("id" to window.id, "displayId" to displayId, "packageName" to root?.packageName?.toString(),
                "title" to window.title?.toString(), "focused" to window.isFocused, "active" to window.isActive, "rootNodeId" to rootId,
                "type" to window.type, "layer" to window.layer,"bounds" to obj("left" to windowBounds.left,"top" to windowBounds.top,"right" to windowBounds.right,"bottom" to windowBounds.bottom)))
            if (root != null) queue.add(root to null)
            val visited = HashSet<String>()
            while (queue.isNotEmpty()) {
                val (node, parent) = queue.removeFirst()
                val nodeId = id(node)
                if (!visited.add(nodeId)) { node.recycle(); continue }
                refs[nodeId] = node
                val children = JSONArray()
                val unavailableChildren = JSONArray()
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
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
            window.recycle()
        }
        val displays = displayList()
        val unavailableChanged = unavailable.toString() != (snapshot.optJSONArray("unavailable") ?: JSONArray()).toString()
        val nextFingerprint = obj("displays" to displays,"windows" to wins,"nodes" to nodes,"unavailable" to unavailable).toString()
        val changed = nextFingerprint != fingerprint
        nodeHandles.commitCapture()
        nodeRefs.values.forEach { it.recycle() }; nodeRefs = refs
        if (changed) { revision++; fingerprint = nextFingerprint }
        snapshot = base().put("displays",displays).put("windows",wins).put("nodes",nodes).put("unavailable",unavailable).put("mode","full")
        if (publish && changed && unavailableChanged) {
            // unavailable has no typed delta field in ai-phone/2: replace with an authoritative full state.
            emitFullState()
        } else if (publish && changed) {
            val changedNodes=JSONArray();val changedWindows=JSONArray();val windowIds=mutableSetOf<Int>()
            for(i in 0 until nodes.length()) { val n=nodes.getJSONObject(i);if(oldNodes[n.getString("id")]!=n.toString()) changedNodes.put(n) }
            for(i in 0 until wins.length()) { val w=wins.getJSONObject(i);windowIds.add(w.getInt("id"));if(oldWindows[w.getInt("id")]!=w.toString()) changedWindows.put(w) }
            emit(eventBase().put("type","event").put("event","delta").put("baseRevision",previousRevision)
                .put("delta",obj("displays" to displays,"windowsUpsert" to changedWindows,"windowsRemoved" to JSONArray(oldWindows.keys-windowIds),
                    "nodesUpsert" to changedNodes,"nodesRemoved" to JSONArray(oldIds-refs.keys))))
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
        try {
            val frame = JSONObject(line)
            requestId = (frame.opt("requestId") as? String)?.takeIf { it.isNotEmpty() } ?: requestId
            method = (frame.opt("method") as? String)?.takeIf { it.isNotEmpty() } ?: method
            if(frame.optString("type")=="helloAck" && frame.optString("protocol")!="ai-phone/2") {
                protocolReady=false
                socket?.close()
                fail("protocol_mismatch","Expected ai-phone/2; connection closed")
            }
            try { wire.validate(frame) } catch(e:IllegalArgumentException) { fail("invalid_frame",e.message ?: "Invalid frame") }
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
                val replay = receipts.lookupSerialized(requestId, frame)
                if (replay != null) {
                    wire.validate(ReceiptJson.parseObject(replay))
                    // Do not reserialize immutable history through Android's Double parser.
                    if(socket!=null) outbound.offer(replay)
                    return
                }
            }
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
            val response = obj("type" to "response","requestId" to requestId,"method" to method,"ok" to true,"result" to result)
            wire.validate(response)
            if (mutation) receipts.complete(requestId,frame,response)
            dispatchedMutationFrame=null
            emit(response)
        } catch (e: Exception) {
            val cause = e as? Failure
            val response=obj("type" to "response","requestId" to requestId,"method" to method,"ok" to false,"error" to obj("code" to (cause?.code ?: when(e) { is ActionReceipts.Conflict -> e.code; is PowerLeases.Failure -> e.code; else -> "native_error" }),"message" to (e.message ?: e.javaClass.simpleName),"currentScope" to obj("serviceInstanceId" to epoch,"revision" to revision)))
            dispatchedMutationFrame?.let { receipts.complete(requestId,it,response) }
            emit(response)
        }
    }
    private fun observe(p: JSONObject): JSONObject {
        val display = displayId(p)
        if (Build.VERSION.SDK_INT < 30 && display != 0) fail("unsupported_display","All-display windows require API 30")
        val mode = p.optString("mode","full")
        if (mode !in listOf("full","summary")) fail("invalid_params","mode must be full or summary")
        val selector = if(p.has("selector")) p.optJSONObject("selector") ?: fail("invalid_params","selector must be an exact-match object") else null
        capture(true)
        val wins = JSONArray(); val nodes = JSONArray()
        val allWins=snapshot.getJSONArray("windows");val allNodes=snapshot.getJSONArray("nodes")
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
        return base().put("displays",snapshot.getJSONArray("displays")).put("windows",wins).put("nodes",if(mode=="full") nodes else JSONArray()).put("nodeCount",nodes.length()).put("mode",mode).put("unavailable",snapshot.getJSONArray("unavailable")).put("fullContentAvailable",true)
    }
    private fun paramsScope(p: JSONObject) = p.optString("scope","")
    private fun act(p: JSONObject, requestId: String, frame: JSONObject): JSONObject {
        val action=requiredString(p,"action");val display=displayId(p)
        val args=if(p.has("args")) p.optJSONObject("args") ?: fail("invalid_params","args must be an object") else JSONObject()
        if(action !in globals && action !in actionIds) fail("unsupported_action",action)
        capture(true)
        if(requiredString(p,"serviceInstanceId")!=epoch || integer(p,"revision")!=revision) fail("stale_handle","Re-observe current epoch/revision")
        val before=revision
        var target: AccessibilityNodeInfo?=null
        var bundle: Bundle?=null
        if(action in globals) {
            if(paramsScope(p)!="device") fail("invalid_params","Global actions require explicit scope=device")
            if(action !in supportedGlobals()) fail("unsupported_action","System does not currently expose $action")
            if(display!=0) fail("unsupported_display","Global Android actions cannot be isolated to a requested display")
            if(args.length()!=0) fail("invalid_params","Global action args must be empty")
        } else {
            val nodeId=requiredString(p,"nodeId");val window=integer(p,"windowId")
            target=nodeRefs[nodeId] ?: fail("stale_handle","Node no longer exists")
            if(!nodeId.startsWith("$display:$window:")) fail("stale_handle","Node display/window mismatch")
            if(!target.refresh()) fail("stale_handle","Node cannot be refreshed")
            if(target.actionList.none { it.id==actionIds[action] }) fail("unsupported_action","Node does not expose $action")
            if(action=="set_text") {
                if(args.length()!=1 || args.opt("text") !is String) fail("invalid_params","set_text requires only args.text string")
                bundle=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getString("text")) }
            } else if(args.length()!=0) fail("invalid_params","Action args must be empty")
        }
        receipts.begin(requestId,frame)
        val accepted=if(action in globals) performGlobalAction(globals.getValue(action)) else target!!.performAction(actionIds.getValue(action),bundle)
        return base().put("accepted",accepted).put("observedOutcome","unverified").put("beforeRevision",before).put("afterRevision",revision)
            .put("scope",if(action in globals) "untargeted" else "node_display").put("displayId",display).put("requestId",requestId)
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
