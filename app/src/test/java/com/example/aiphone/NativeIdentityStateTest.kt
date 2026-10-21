package com.example.aiphone

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
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
import org.robolectric.util.ReflectionHelpers
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/** Executes production capture/act against Robolectric Android nodes, not a serialization-only model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@Suppress("DEPRECATION")
class NativeIdentityStateTest {
    private lateinit var service: AiPhoneAccessibilityService
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var queue: LinkedBlockingQueue<String>
    private lateinit var receipts: ActionReceipts
    private fun set(name:String,value:Any) { service.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(service,value) }
    private fun state() = service.javaClass.getDeclaredField("snapshot").apply{isAccessible=true}.get(service) as JSONObject
    private fun publishWindows() { shadowOf(service).setWindowsOnDisplay(0,listOf(AccessibilityWindowInfo.obtain(window))) }
    private fun capture(publish:Boolean=true) { publishWindows();service.javaClass.getDeclaredMethod("capture",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(service,publish) }
    private fun node(text:String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(View(service)).also {
        it.text=text;it.isEnabled=true;it.isClickable=true;it.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        shadowOf(it).setAccessibilityWindowInfo(window)
    }
    private fun root(node:AccessibilityNodeInfo?) { shadowOf(window).setRoot(node) }
    private fun events(): List<JSONObject> = mutableListOf<JSONObject>().also { out -> while(true) {val s=queue.poll() ?: break;out.add(JSONObject(s))} }
    private fun nodeId(text:String):String {
        val nodes=state().getJSONArray("nodes")
        return (0 until nodes.length()).map{nodes.getJSONObject(it)}.first{it.optString("text")==text}.getString("id")
    }
    private fun act(id:String, revision:Long):JSONObject {
        val requestId="regression-"+java.util.UUID.randomUUID()
        val params=JSONObject().put("requestId",requestId).put("serviceInstanceId","regression-epoch").put("revision",revision)
            .put("displayId",0).put("windowId",7).put("nodeId",id).put("action","click").put("args",JSONObject())
        val frame=JSONObject().put("type","request").put("requestId",requestId).put("method","act").put("params",params)
        publishWindows()
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply{isAccessible=true}.invoke(service,frame.toString())
        val result=events().last{it.optString("type")=="response"}
        assertNull("Rejected stale handle must not begin mutation journal",receipts.lookup(requestId,frame))
        return result
    }
    @Before fun setup() {
        service=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        receipts=ActionReceipts(service);queue=LinkedBlockingQueue()
        set("socket",Socket());set("outbound",queue);set("receipts",receipts);set("epoch","regression-epoch")
        window=AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(7);shadowOf(window).setDisplayId(0)
        publishWindows()
    }
    @After fun cleanup() { service.onDestroy() }

    @Test fun equalLookingDistinctNativeReplacementInvalidatesOldHandleBeforeDispatch() {
        val a=node("Open");val b=node("Open")
        assertNotEquals("Must be distinct Android node identities despite equal visible content",a,b)
        assertEquals("Copies retain Android identity",a,AccessibilityNodeInfo.obtain(a))
        root(a);capture(false)
        val oldId=nodeId("Open");val oldRevision=state().getLong("revision")
        root(b)
        val result=act(oldId,oldRevision) // Production act captures replacement before its stale check.
        assertFalse(result.getBoolean("ok"));assertEquals("stale_handle",result.getJSONObject("error").getString("code"))
        assertNotEquals(oldId,nodeId("Open"));assertTrue(state().getLong("revision")>oldRevision)
        assertTrue("Replacement must not receive old request",shadowOf(b).performedActions.isEmpty())
        val currentRevision=state().getLong("revision")
        assertEquals("stale_handle",act(oldId,currentRevision).getJSONObject("error").getString("code"))
        assertTrue(shadowOf(b).performedActions.isEmpty())
    }

    @Test fun sameNativeNodesKeepSelectionOnReorderButRemovedHandleIsNeverReassigned() {
        val container=node("Container");val a=node("A");val b=node("B")
        shadowOf(container).addChild(a);shadowOf(container).addChild(b);root(container);capture(false)
        val aId=nodeId("A");val bId=nodeId("B")
        // Change actual shadow Android child ordering while retaining each native identity.
        ReflectionHelpers.setField(shadowOf(container),"children",mutableListOf(b,a))
        capture()
        assertEquals(aId,nodeId("A"));assertEquals(bId,nodeId("B"))
        val children=state().getJSONArray("nodes").getJSONObject(0).getJSONArray("childIds")
        assertEquals(bId,children.getString(0));assertEquals(aId,children.getString(1))
        ReflectionHelpers.setField(shadowOf(container),"children",mutableListOf(b));capture()
        val removed=events().last{it.optString("event")=="delta"}.getJSONObject("delta").getJSONArray("nodesRemoved")
        assertTrue((0 until removed.length()).any{removed.getString(it)==aId})
        assertEquals("stale_handle",act(aId,state().getLong("revision")).getJSONObject("error").getString("code"))
        ReflectionHelpers.setField(shadowOf(container),"children",mutableListOf(b,a));capture()
        assertNotEquals("Reappearing retired identity gets a new handle",aId,nodeId("A"));assertEquals(bId,nodeId("B"))
        val replacement=node("A")
        ReflectionHelpers.setField(shadowOf(container),"children",mutableListOf(b,replacement));capture()
        assertNotEquals(aId,nodeId("A"));assertEquals(bId,nodeId("B"))
    }

    @Test fun rootUnavailableAndRecoveryPublishAuthoritativeFullStatesThenDeltasReconstruct() {
        val a=node("Ready");root(a);capture(false)
        var cache=JSONObject(state().toString())
        assertEquals(0,cache.getJSONArray("unavailable").length())
        fun applyEvent(event:JSONObject) {
            if(event.getString("event")=="state") cache=JSONObject(event.getJSONObject("state").toString())
            else {
                assertEquals(cache.getLong("revision"),event.getLong("baseRevision"))
                val delta=event.getJSONObject("delta")
                for((field,upsert,removed) in listOf(Triple("nodes","nodesUpsert","nodesRemoved"),Triple("windows","windowsUpsert","windowsRemoved"))) {
                    val old=cache.getJSONArray(field);val map=linkedMapOf<String,JSONObject>()
                    for(i in 0 until old.length()) {val row=old.getJSONObject(i);map[row.get("id").toString()]=row}
                    val gone=delta.getJSONArray(removed);for(i in 0 until gone.length()) map.remove(gone.get(i).toString())
                    val added=delta.getJSONArray(upsert);for(i in 0 until added.length()) {val row=added.getJSONObject(i);map[row.get("id").toString()]=row}
                    cache.put(field,JSONArray(map.values.toList()))
                }
                cache.put("displays",delta.getJSONArray("displays")).put("revision",event.getLong("revision")).put("observedAt",event.getString("observedAt"))
            }
        }
        root(null);capture()
        val loss=events().single();assertEquals("state",loss.getString("event"));applyEvent(loss)
        assertTrue(cache.getJSONArray("unavailable").length()>0)
        assertEquals("window_content_unavailable",cache.getJSONArray("unavailable").getJSONObject(0).getString("code"))
        assertEquals(state().toString(),cache.toString())
        val lostRevision=cache.getLong("revision")
        root(a);capture()
        val recovery=events().single();assertEquals("state",recovery.getString("event"));applyEvent(recovery)
        assertTrue(cache.getLong("revision")>lostRevision);assertEquals(0,cache.getJSONArray("unavailable").length())
        assertEquals(state().toString(),cache.toString())
        a.text="Changed";capture()
        val update=events().single();assertEquals("delta",update.getString("event"));applyEvent(update)
        assertEquals(state().getJSONArray("unavailable").toString(),cache.getJSONArray("unavailable").toString())
        assertEquals(state().getJSONArray("nodes").toString(),cache.getJSONArray("nodes").toString())
        assertEquals(state().getJSONArray("windows").toString(),cache.getJSONArray("windows").toString())
        assertEquals(state().getLong("revision"),cache.getLong("revision"))
    }
}
