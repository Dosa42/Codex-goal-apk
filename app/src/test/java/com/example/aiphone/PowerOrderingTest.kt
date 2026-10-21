package com.example.aiphone

import android.Manifest
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
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
import java.math.BigInteger
import java.net.Socket
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class PowerOrderingTest {
    private lateinit var context:Context
    private lateinit var manager:PowerLeases
    private lateinit var service:AiPhoneAccessibilityService
    private lateinit var queue:LinkedBlockingQueue<String>
    private var clock="2026-10-21T22:00:00Z"
    private var frames=listOf<JSONObject>()
    private fun setManager(n:String,v:Any) {manager.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(manager,v)}
    private fun attach(epoch:String):AiPhoneAccessibilityService {
        val s=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        fun set(n:String,v:Any) {s.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(s,v)}
        queue=LinkedBlockingQueue();set("outbound",queue);set("socket",Socket());set("receipts",ActionReceipts(s));set("protocolReady",true);set("epoch",epoch)
        manager.bindService(s)
        val listener=s.javaClass.getDeclaredField("powerListener").apply{isAccessible=true}.get(s) as (JSONObject)->Unit
        manager.addListener(listener)
        return s
    }
    @Before fun setup() {
        PowerLeases::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context=ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WAKE_LOCK)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,true)
        manager=PowerLeases.get(context);setManager("observationClock",{clock});service=attach("ordering-epoch1")
    }
    @After fun cleanup() {
        val leases=manager.state().getJSONArray("activeLeases")
        for(i in 0 until leases.length()) manager.release("cleanup-$i",leases.getJSONObject(i).getString("leaseId"))
        service.onDestroy()
    }
    private fun rawCall(id:String,params:JSONObject):List<String> {
        queue.clear()
        val f=JSONObject().put("type","request").put("requestId",id).put("method","power").put("params",params)
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply{isAccessible=true}.invoke(service,f.toString())
        return queue.toList().also { raw -> raw.forEach{println("AI_PHONE_ORDER_FRAME "+it)} }
    }
    private fun call(id:String,params:JSONObject):JSONObject {
        frames=rawCall(id,params).map{JSONObject(it)}
        return frames.last{it.optString("type")=="response"}.also{assertTrue(it.toString(),it.getBoolean("ok"))}.getJSONObject("result")
    }
    private fun status(id:String)=call(id,JSONObject().put("operation","status")).getJSONObject("state")
    private fun acquireParams(id:String,deadline:Long?=null)=JSONObject().put("operation","acquire").put("requestId",id).put("owner","ordering-test").put("scope","device").also { if(deadline!=null) it.put("deadlineAfterMs",deadline) }
    private fun assertMatchingEvent(result:JSONObject) {
        val state=result.getJSONObject("state")
        val event=frames.last{it.optString("event")=="power"}
        assertEquals(state.toString(),event.getJSONObject("powerState").toString())
        assertEquals(state.getString("observedAt"),event.getString("observedAt"))
        assertEquals(0,event.getLong("revision")) // accessibility revision was not repurposed
    }
    @Test fun equalAndBackwardClockCapturesRemainOrderedAndEventResultReuseIsExact() {
        val initial=status("initial");val equal=status("equal")
        assertEquals(1,initial.getLong("powerRevision"));assertEquals(2,equal.getLong("powerRevision"))
        assertEquals(initial.getString("observedAt"),equal.getString("observedAt"))
        clock="2026-10-20T22:00:00Z"
        val acquired=call("held",acquireParams("held"));assertMatchingEvent(acquired)
        assertEquals(3,acquired.getJSONObject("before").getLong("powerRevision"))
        assertEquals(4,acquired.getJSONObject("state").getLong("powerRevision"))
        assertFalse(acquired.getJSONObject("state").getBoolean("backendHeld")) // window attachment is asynchronous
        val delayedOlder=initial.toString() // actual serialized older no-lease observation held for delivery
        val newer=acquired.getJSONObject("state")
        assertTrue(JSONObject(delayedOlder).getLong("powerRevision")<newer.getLong("powerRevision"))
        assertTrue(JSONObject(delayedOlder).getString("observedAt")>newer.getString("observedAt"))
        val process=newer.getString("processInstanceId")
        service.onDestroy();service=attach("ordering-epoch2")
        val rebind=manager.state()
        service.javaClass.getDeclaredMethod("emitPowerState",JSONObject::class.java).apply{isAccessible=true}.invoke(service,rebind)
        val raw=queue.toList().last();println("AI_PHONE_ORDER_FRAME "+raw)
        val event=JSONObject(raw)
        assertEquals("ordering-epoch2",event.getString("serviceInstanceId"))
        assertEquals(process,rebind.getString("processInstanceId"));assertEquals(5,rebind.getLong("powerRevision"))
        assertEquals(rebind.toString(),event.getJSONObject("powerState").toString())
        clock="2026-10-19T22:00:00Z"
        val timed=call("timed",acquireParams("timed",1000));assertMatchingEvent(timed)
        assertEquals(6,timed.getJSONObject("before").getLong("powerRevision"));assertEquals(7,timed.getJSONObject("state").getLong("powerRevision"))
        queue.clear();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        val deadline=queue.toList().map{println("AI_PHONE_ORDER_FRAME "+it);JSONObject(it)}
        assertTrue(deadline.isNotEmpty())
        val expired=deadline.last().getJSONObject("powerState")
        // Attaching the visible hold window is an additional real observation before expiry.
        assertTrue(expired.getLong("powerRevision")>=8);assertEquals(1,expired.getJSONArray("activeLeases").length())
        deadline.forEach {
            val state=it.getJSONObject("powerState")
            assertEquals(state.getString("observedAt"),it.getString("observedAt"))
            WireSchema(context).validate(it)
        }
        val expiredRevision=expired.getLong("powerRevision")
        val released=call("release",JSONObject().put("operation","release").put("requestId","release").put("leaseId","held"));assertMatchingEvent(released)
        assertEquals(expiredRevision+1,released.getJSONObject("before").getLong("powerRevision"));assertEquals(expiredRevision+2,released.getJSONObject("state").getLong("powerRevision"))
        val replay=call("held",acquireParams("held"));assertEquals(acquired.toString(),replay.toString())
        val fresh=status("fresh");assertEquals(expiredRevision+3,fresh.getLong("powerRevision"));assertFalse(fresh.getBoolean("backendHeld"))
        assertTrue(replay.getJSONObject("state").getLong("powerRevision")<fresh.getLong("powerRevision"))
    }
    @Test fun counterAndProductionSerializationHaveNoLongOrDoubleLimit() {
        for(boundary in listOf(BigInteger.valueOf(Long.MAX_VALUE),BigInteger.TEN.pow(400))) {
            setManager("powerRevision",boundary)
            val expected=boundary.add(BigInteger.ONE)
            val raw=rawCall("large-${boundary.bitLength()}",JSONObject().put("operation","status")).single()
            assertTrue(raw.contains("\"powerRevision\":$expected"))
            assertFalse(raw.contains("Infinity"))
            val next=manager.state();assertEquals(expected.add(BigInteger.ONE).toString(),next.get("powerRevision").toString())
            WireSchema(context).validate(next,"powerState")
        }
    }
    @Test fun newManagerIncarnationStartsNewPositiveSequenceWithoutPersistedLeases() {
        val previous=status("old-process")
        service.onDestroy()
        PowerLeases::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        manager=PowerLeases.get(context);setManager("observationClock",{clock});service=attach("new-process-transport")
        val current=status("new-process")
        assertNotEquals(previous.getString("processInstanceId"),current.getString("processInstanceId"))
        assertEquals(1,current.getLong("powerRevision"));assertEquals(0,current.getJSONArray("activeLeases").length())
        // This is a JVM manager-incarnation check, not an Android process-death acceptance claim.
    }
}
