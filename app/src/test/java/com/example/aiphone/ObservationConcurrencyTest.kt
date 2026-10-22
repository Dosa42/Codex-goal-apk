package com.example.aiphone

import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Block a real production traversal at Android getChild; assert ordering, not JVM speed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], shadows=[ObservationConcurrencyTest.BlockingNode::class, ObservationConcurrencyTest.CopyingWindows::class])
@Suppress("DEPRECATION")
class ObservationConcurrencyTest {
    @Implements(android.accessibilityservice.AccessibilityService::class)
    class CopyingWindows : org.robolectric.shadows.ShadowAccessibilityService() {
        @Implementation override fun getWindowsOnAllDisplays(): android.util.SparseArray<List<AccessibilityWindowInfo>> {
            val source=super.getWindowsOnAllDisplays()
            return android.util.SparseArray<List<AccessibilityWindowInfo>>().also { out ->
                for(i in 0 until source.size()) out.put(source.keyAt(i),source.valueAt(i).map { AccessibilityWindowInfo.obtain(it) })
            }
        }
    }
    @Implements(AccessibilityNodeInfo::class)
    class BlockingNode : ShadowAccessibilityNodeInfo() {
        @Implementation override fun getChild(index: Int): AccessibilityNodeInfo? {
            if (getText()?.toString() == "BLOCK" && block) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test failed to release actual getChild" }
            }
            return super.getChild(index)
        }
        companion object {
            @Volatile var block=false
            var entered=CountDownLatch(1)
            var release=CountDownLatch(1)
        }
    }
    private lateinit var service: AiPhoneAccessibilityService
    private lateinit var queue: LinkedBlockingQueue<String>
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private var socketReader: java.io.BufferedReader? = null
    private var peerSocket: Socket? = null
    private var writer: Thread? = null
    private fun startProductionWriter() {
        java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { listener ->
            val connection=Socket(java.net.InetAddress.getLoopbackAddress(),listener.localPort)
            val peer=listener.accept();peer.soTimeout=3000;peerSocket=peer
            socketReader=peer.getInputStream().bufferedReader();set("socket",connection)
            writer=Thread({ call("writeLoop",arrayOf(Socket::class.java,LinkedBlockingQueue::class.java),connection,queue) },"test-production-writer").also { it.start() }
        }
    }
    private val ownedWindows=linkedMapOf<Int,AccessibilityWindowInfo>()
    private fun publishWindows() { ownedWindows.forEach { (display, w) -> shadowOf(service).setWindowsOnDisplay(display,listOf(AccessibilityWindowInfo.obtain(w))) } }
    private var clock=1792616400000L
    private fun set(name:String,value:Any) { service.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(service,value) }
    private fun field(name:String):Any = service.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(service)
    private fun state()=field("snapshot") as JSONObject
    private fun call(name:String,types:Array<Class<*>>,vararg args:Any?) = service.javaClass.getDeclaredMethod(name,*types).apply{isAccessible=true}.invoke(service,*args)
    private fun capture() { publishWindows(); call("capture",arrayOf(Boolean::class.javaPrimitiveType!!),false) }
    private fun frame(id:String,method:String,params:JSONObject)=JSONObject().put("type","request").put("requestId",id).put("method",method).put("params",params).toString()
    private fun dispatch(id:String,method:String,params:JSONObject) { if(method=="observe" && params.optString("mode","full")=="full") publishWindows(); call("dispatch",arrayOf(String::class.java),frame(id,method,params));shadowOf(Looper.getMainLooper()).idle() }
    private fun response(id:String):JSONObject {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(System.nanoTime()<deadline) {
            val raw=if(socketReader!=null) socketReader!!.readLine() else queue.poll(100,TimeUnit.MILLISECONDS)
            if(raw==null) continue
            val value=JSONObject(raw)
            if(value.optString("requestId")==id) return value.also { WireSchema(service).validate(it) }
        }
        throw AssertionError("No response for $id")
    }
    private fun drainWorker() { (field("observations") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS) }
    private fun node(text:String,w:AccessibilityWindowInfo)=AccessibilityNodeInfo.obtain(View(service)).also { it.text=text;shadowOf(it).setAccessibilityWindowInfo(w) }
    @Before fun setup() {
        BlockingNode.block=false;BlockingNode.entered=CountDownLatch(1);BlockingNode.release=CountDownLatch(1)
        service=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        queue=LinkedBlockingQueue();set("socket",Socket());set("outbound",queue);set("protocolReady",true);set("receipts",ActionReceipts(service))
        set("observationClock",{clock+=1000;java.time.Instant.ofEpochMilli(clock).toString()})
        window=AccessibilityWindowInfo.obtain();shadowOf(window).setId(7);shadowOf(window).setDisplayId(0)
        root=node("BLOCK",window);shadowOf(root).addChild(node("child",window));shadowOf(window).setRoot(root)
        ownedWindows[0]=window;capture()
    }
    @After fun cleanup() {
        BlockingNode.block=false;BlockingNode.release.countDown();drainWorker();service.onDestroy()
        writer?.interrupt();peerSocket?.close();writer?.join(3000)
    }

    @Test fun powerAndResidentSummaryReplyWhileProductionFullCaptureIsBlocked() {
        val original=state().getString("observedAt")
        startProductionWriter()
        BlockingNode.block=true
        dispatch("bulk","observe",JSONObject().put("displayId",0).put("mode","full"))
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        dispatch("control","power",JSONObject().put("operation","status"))
        assertTrue(response("control").getBoolean("ok"))
        dispatch("resident","observe",JSONObject().put("displayId",0).put("mode","summary"))
        val result=response("resident").getJSONObject("result")
        assertEquals(original,result.getString("observedAt"));assertEquals("stale",result.getString("freshness"));assertTrue(result.getBoolean("capturePending"))
        assertEquals("Full read is still blocked at Android getChild",1L,BlockingNode.release.count)
        BlockingNode.release.countDown()
        val full=response("bulk").getJSONObject("result")
        assertEquals("captured",full.getString("freshness"));assertFalse(full.getBoolean("capturePending"))
        assertNotEquals(original,full.getString("observedAt"));assertEquals(2,full.getJSONArray("nodes").length())
        // The following reply establishes that the preceding flush and its log completed.
        dispatch("write-barrier","power",JSONObject().put("operation","status"));response("write-barrier")
        val logs=org.robolectric.shadows.ShadowLog.getLogsForTag("AiPhoneLatency").map { JSONObject(it.msg) }
        for(id in listOf("control","resident","bulk")) {
            val stages=logs.filter { it.optString("requestId")==id }.map { it.getString("stage") }
            assertTrue("$id queue timing", "write_queue_wait" in stages)
            assertTrue("$id actual write timing", "socket_write" in stages)
        }
        logs.forEach { println("AI_PHONE_LATENCY_TIMING "+it) }
        println("AI_PHONE_LATENCY_FRAME "+JSONObject().put("type","response").put("requestId","bulk").put("method","observe").put("ok",true).put("result",full))
    }

    @Test fun nodeActionUsesLiveIdentityWithoutTraversingChildren() {
        root.addAction(AccessibilityNodeInfo.ACTION_CLICK);shadowOf(root).setRefreshReturnValue(true);capture()
        val observed=state();val nodeId=observed.getJSONArray("nodes").getJSONObject(0).getString("id")
        val oldTime=observed.getString("observedAt")
        BlockingNode.block=true;publishWindows()
        dispatch("click","act",JSONObject().put("requestId","click").put("serviceInstanceId",observed.getString("serviceInstanceId"))
            .put("revision",observed.getLong("revision")).put("displayId",0).put("windowId",7).put("nodeId",nodeId).put("action","click").put("args",JSONObject()))
        val reply=response("click")
        assertTrue(reply.toString(),reply.getBoolean("ok"));assertEquals("unverified",reply.getJSONObject("result").getString("observedOutcome"))
        assertEquals(1L,BlockingNode.entered.count);assertEquals(oldTime,state().getString("observedAt"))
        val journal=field("receipts") as ActionReceipts
        val original=JSONObject(frame("click","act",JSONObject().put("requestId","click").put("serviceInstanceId",observed.getString("serviceInstanceId"))
            .put("revision",observed.getLong("revision")).put("displayId",0).put("windowId",7).put("nodeId",nodeId).put("action","click").put("args",JSONObject())))
        assertNotNull(journal.lookupSerialized("click",original))
    }

    @Test fun invalidationDuringCaptureSurvivesCommitAndRemovedWindowDoesNotLoop() {
        set("alive",true);BlockingNode.block=true;publishWindows()
        val event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        shadowOf(event).setWindowId(7)
        service.onAccessibilityEvent(event)
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        // A later event cannot be cleared by the older in-flight traversal.
        shadowOf(service).setWindowsOnDisplay(0,emptyList())
        service.onAccessibilityEvent(event)
        BlockingNode.release.countDown()
        drainWorker();drainWorker() // First pass may enqueue its one event-triggered follow-up after this barrier.
        assertTrue((field("dirtyWindows") as Set<*>).isEmpty());assertFalse(field("captureScheduled") as Boolean)
        assertEquals(0,state().getJSONArray("nodes").length());assertFalse(state().getBoolean("capturePending"))
        assertEquals("captured",state().getString("freshness"));event.recycle()
    }

    private fun addDex(text:String="DEX"):AccessibilityNodeInfo {
        val w=AccessibilityWindowInfo.obtain();shadowOf(w).setId(18);shadowOf(w).setDisplayId(18)
        val n=node(text,w);shadowOf(n).addChild(node("dex-child",w));shadowOf(w).setRoot(n)
        ownedWindows[18]=w;capture();return n
    }
    private fun invalidate(windowId:Int?) {
        if(windowId==null) service.onAccessibilityEvent(null)
        else {
            val event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            shadowOf(event).setWindowId(windowId);service.onAccessibilityEvent(event);event.recycle()
        }
    }
    private fun clickFrom(result:JSONObject,id:String,actionRevision:Long=result.getLong("revision")):JSONObject {
        publishWindows()
        dispatch(id,"act",JSONObject().put("requestId",id).put("serviceInstanceId",result.getString("serviceInstanceId"))
            .put("revision",actionRevision).put("displayId",0).put("windowId",7)
            .put("nodeId",result.getJSONArray("nodes").getJSONObject(0).getString("id")).put("action","click").put("args",JSONObject()))
        return response(id)
    }

    @Test fun dexEventDuringGlobalCaptureDoesNotPoisonFreshPhoneReadOrAction() {
        root.addAction(AccessibilityNodeInfo.ACTION_CLICK);shadowOf(root).setRefreshReturnValue(true);addDex()
        set("alive",true);BlockingNode.block=true;publishWindows();invalidate(null)
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        dispatch("scoped-phone","observe",JSONObject().put("displayId",0).put("mode","full"))
        val barrierEntered=CountDownLatch(1);val barrierRelease=CountDownLatch(1)
        (field("observations") as ExecutorService).execute { barrierEntered.countDown();check(barrierRelease.await(5,TimeUnit.SECONDS)) }
        try {
            invalidate(18);BlockingNode.block=false;BlockingNode.release.countDown()
            val phone=response("scoped-phone").getJSONObject("result")
            assertTrue(barrierEntered.await(3,TimeUnit.SECONDS))
            println("AI_PHONE_FRESHNESS_CASE global_then_dex "+phone)
            assertEquals("captured",phone.getString("freshness"));assertFalse(phone.getBoolean("capturePending"))
            dispatch("scoped-dex-summary","observe",JSONObject().put("displayId",18).put("mode","summary"))
            val dex=response("scoped-dex-summary").getJSONObject("result")
            assertEquals("stale",dex.getString("freshness"));assertTrue(dex.getBoolean("capturePending"))
            val action=clickFrom(phone,"scoped-click")
            assertTrue(action.toString(),action.getBoolean("ok"));assertEquals("unverified",action.getJSONObject("result").getString("observedOutcome"))
        } finally { set("alive",false);barrierRelease.countDown() }
    }

    @Test fun selectedCaptureAcknowledgesGlobalInvalidationOnlyForItsActualScope() {
        addDex();set("alive",true)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        (field("observations") as ExecutorService).execute { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        try {
            invalidate(null);publishWindows()
            val phone=call("observe",arrayOf(JSONObject::class.java),JSONObject().put("displayId",0).put("mode","full")) as JSONObject
            println("AI_PHONE_FRESHNESS_CASE selected_ack_global "+phone)
            assertEquals("captured",phone.getString("freshness"));assertFalse(phone.getBoolean("capturePending"))
            assertTrue("Global cache must retain outstanding DeX invalidation",field("dirtyAll") as Boolean)
            assertEquals("stale",state().getString("freshness"))
            dispatch("uncaptured-dex","observe",JSONObject().put("displayId",18).put("mode","summary"))
            val dex=response("uncaptured-dex").getJSONObject("result")
            assertEquals("stale",dex.getString("freshness"));assertTrue(dex.getBoolean("capturePending"))
        } finally { set("alive",false);release.countDown() }
    }

    @Test fun actualPhoneInvalidationDuringSelectedCaptureRemainsStale() {
        addDex();set("alive",true);BlockingNode.block=true
        dispatch("phone-changed","observe",JSONObject().put("displayId",0).put("mode","full"))
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        invalidate(7);BlockingNode.block=false;BlockingNode.release.countDown()
        val phone=response("phone-changed").getJSONObject("result")
        println("AI_PHONE_FRESHNESS_CASE phone_changed "+phone)
        assertEquals("stale",phone.getString("freshness"));assertTrue(phone.getBoolean("capturePending"))
        set("alive",false)
    }

    @Test fun dexDisplayCaptureDoesNotMarkResidentPhonePendingOrRejectItsAction() {
        root.text="PHONE";root.addAction(AccessibilityNodeInfo.ACTION_CLICK);shadowOf(root).setRefreshReturnValue(true)
        addDex("BLOCK");val phoneState=state();val originalTime=phoneState.getString("observedAt")
        set("alive",true);BlockingNode.block=true;publishWindows()
        (field("displayListener") as android.hardware.display.DisplayManager.DisplayListener).onDisplayChanged(18)
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        try {
            dispatch("phone-resident","observe",JSONObject().put("displayId",0).put("mode","summary"))
            val phone=response("phone-resident").getJSONObject("result")
            println("AI_PHONE_FRESHNESS_CASE dex_capture_phone_resident "+phone)
            assertEquals("resident",phone.getString("freshness"));assertFalse(phone.getBoolean("capturePending"));assertEquals(originalTime,phone.getString("observedAt"))
            val action=clickFrom(phoneState,"during-dex-click")
            assertTrue(action.toString(),action.getBoolean("ok"));assertEquals("unverified",action.getJSONObject("result").getString("observedOutcome"))
        } finally { set("alive",false);BlockingNode.block=false;BlockingNode.release.countDown() }
    }

    private fun selectedRead(display:Int):JSONObject {
        publishWindows()
        return call("observe",arrayOf(JSONObject::class.java),JSONObject().put("displayId",display).put("mode","full")) as JSONObject
    }
    private fun scopedDelta(display:Int):JSONObject {
        publishWindows();queue.clear()
        call("captureScoped",arrayOf(Boolean::class.javaPrimitiveType!!,Int::class.javaObjectType,Set::class.java),true,display,null)
        val event=JSONObject(queue.poll(3,TimeUnit.SECONDS) ?: throw AssertionError("Missing production delta"))
        assertEquals(event.toString(),"delta",event.getString("event"));WireSchema(service).validate(event)
        return event
    }

    @Test fun coherentDexDeltaAdvancesActionCursorWithoutRewritingCapturedProvenance() {
        root.text="PHONE";root.addAction(AccessibilityNodeInfo.ACTION_CLICK);shadowOf(root).setRefreshReturnValue(true)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        shadowOf(root).setOnPerformActionListener { _, _ -> calls.incrementAndGet();true }
        val dex=addDex();val selected=selectedRead(0);val immutableSelected=selected.toString();val n=selected.getLong("revision")
        dex.text="DEX changed";val delta=scopedDelta(18)
        assertEquals(n,delta.getLong("baseRevision"));assertEquals(n+1,delta.getLong("revision"))
        val changes=delta.getJSONObject("delta");val changedNodes=changes.getJSONArray("nodesUpsert")
        assertTrue(changedNodes.length()>0)
        assertTrue((0 until changedNodes.length()).all { changedNodes.getJSONObject(it).getInt("displayId")==18 })
        assertEquals(0,changes.getJSONArray("nodesRemoved").length());assertEquals(0,changes.getJSONArray("windowsRemoved").length())
        val reply=clickFrom(selected,"cursor-new-intent",delta.getLong("revision"))
        assertTrue(reply.toString(),reply.getBoolean("ok"));assertTrue(reply.getJSONObject("result").getBoolean("accepted"))
        assertEquals("unverified",reply.getJSONObject("result").getString("observedOutcome"));assertEquals(1,calls.get())
        assertEquals(immutableSelected,selected.toString())
        val request=JSONObject(frame("cursor-new-intent","act",JSONObject().put("requestId","cursor-new-intent").put("serviceInstanceId",selected.getString("serviceInstanceId"))
            .put("revision",n+1).put("displayId",0).put("windowId",7).put("nodeId",selected.getJSONArray("nodes").getJSONObject(0).getString("id"))
            .put("action","click").put("args",JSONObject())))
        val journal=field("receipts") as ActionReceipts;val persisted=journal.lookupSerialized("cursor-new-intent",request)
        assertNotNull(persisted)
        call("receive",arrayOf(String::class.java),request.toString());val duplicate=response("cursor-new-intent")
        assertEquals(persisted,duplicate.toString());assertEquals(1,calls.get())
        println("AI_PHONE_CURSOR_CASE unrelated "+JSONObject().put("captured",selected).put("delta",delta).put("request",request).put("response",reply)
            .put("capturedProvenanceUnchanged",selected.toString()==immutableSelected).put("nativeDispatchCount",calls.get()))
    }

    @Test fun targetChangeAndMissingDeltaRejectLastCoherentRevisionBeforeNativeDispatch() {
        root.text="PHONE";root.addAction(AccessibilityNodeInfo.ACTION_CLICK);shadowOf(root).setRefreshReturnValue(true)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        shadowOf(root).setOnPerformActionListener { _, _ -> calls.incrementAndGet();true }
        val dex=addDex();val selected=selectedRead(0);val n=selected.getLong("revision")
        root.text="Changed target";val relevant=scopedDelta(0)
        val changed=relevant.getJSONObject("delta").getJSONArray("nodesUpsert")
        assertTrue((0 until changed.length()).any { changed.getJSONObject(it).getInt("displayId")==0 })
        val targetRejected=clickFrom(selected,"target-old-intent",n)
        assertFalse(targetRejected.getBoolean("ok"));assertEquals("stale_handle",targetRejected.getJSONObject("error").getString("code"))
        val lastCoherent=relevant.getLong("revision")
        dex.text="DEX missed";scopedDelta(18) // Deliberately not delivered to the hypothetical consumer.
        dex.text="DEX next";val afterGap=scopedDelta(18)
        assertNotEquals(lastCoherent,afterGap.getLong("baseRevision"))
        val gapRejected=clickFrom(selected,"gap-old-intent",lastCoherent)
        assertFalse(gapRejected.getBoolean("ok"));assertEquals("stale_handle",gapRejected.getJSONObject("error").getString("code"));assertEquals(0,calls.get())
        println("AI_PHONE_CURSOR_CASE relevant_or_gap "+JSONObject().put("captured",selected).put("relevantDelta",relevant).put("targetOldRevisionResponse",targetRejected)
            .put("lastCoherentRevision",lastCoherent).put("afterGapDelta",afterGap).put("gapOldRevisionResponse",gapRejected).put("nativeDispatchCount",calls.get())
            .put("consumerRequirement","Do not advance cursor or create a new selected-node intent through relevant change or gap; native cannot infer consumer delivery history"))
    }

    @Test fun oldConnectionCaptureCannotPublishOrPopulateNewEpoch() {
        BlockingNode.block=true
        dispatch("old-read","observe",JSONObject().put("mode","full"))
        assertTrue(BlockingNode.entered.await(3,TimeUnit.SECONDS))
        val oldEpoch=field("epoch")
        val newQueue=LinkedBlockingQueue<String>()
        call("resetObservationEpoch",arrayOf(LinkedBlockingQueue::class.java),newQueue)
        assertNotEquals(oldEpoch,field("epoch"))
        BlockingNode.release.countDown();drainWorker()
        assertEquals(0,state().length());assertEquals(0L,field("revision"));assertTrue((field("nodeRefs") as Map<*,*>).isEmpty())
        assertTrue(newQueue.isEmpty());assertTrue(queue.isEmpty())
    }

    @Test fun selectedFullReadRetainsOtherDisplayContentHandlesAndOriginalSummaryTime() {
        val other=AccessibilityWindowInfo.obtain();shadowOf(other).setId(18);shadowOf(other).setDisplayId(18)
        val dex=node("DEX",other);shadowOf(dex).addChild(node("dex-child",other));shadowOf(other).setRoot(dex)
        ownedWindows[18]=other;capture()
        val oldTime=state().getString("observedAt")
        val oldNodes=state().getJSONArray("nodes")
        val oldId=(0 until oldNodes.length()).map{oldNodes.getJSONObject(it)}.first{it.optString("text")=="DEX"}.getString("id")
        dex.text="BLOCK";root.text="updated phone"
        BlockingNode.block=true // Scanning display18 would block: production full0 must not visit it.
        dispatch("phone","observe",JSONObject().put("displayId",0).put("mode","full"))
        val phone=response("phone").getJSONObject("result")
        assertEquals("updated phone",phone.getJSONArray("nodes").getJSONObject(0).getString("text"))
        assertEquals(1L,BlockingNode.entered.count)
        val retained=state().getJSONArray("nodes")
        assertTrue((0 until retained.length()).map{retained.getJSONObject(it)}.any{it.getString("id")==oldId && it.optString("text")=="DEX"})
        dispatch("dex-summary","observe",JSONObject().put("displayId",18).put("mode","summary"))
        val summary=response("dex-summary").getJSONObject("result")
        assertEquals(oldTime,summary.getString("observedAt"));assertEquals("resident",summary.getString("freshness"));assertFalse(summary.getBoolean("capturePending"))
        assertEquals(2,summary.getInt("nodeCount"));assertEquals(0,summary.getJSONArray("nodes").length())
    }
}
