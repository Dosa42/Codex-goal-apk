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
import org.robolectric.shadows.ShadowPowerManager
import java.net.Socket
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class PowerLeasesTest {
    private lateinit var context: Context
    private lateinit var service: AiPhoneAccessibilityService
    private lateinit var queue: LinkedBlockingQueue<String>
    private lateinit var manager: PowerLeases
    private val events=mutableListOf<JSONObject>()
    private fun attach(): AiPhoneAccessibilityService {
        val s=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        fun set(name:String,value:Any) { s.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(s,value) }
        queue=LinkedBlockingQueue();set("outbound",queue);set("socket",Socket());set("protocolReady",true);set("receipts",ActionReceipts(s));set("epoch","power-test-epoch")
        manager.bindService(s)
        val listener=s.javaClass.getDeclaredField("powerListener").apply{isAccessible=true}.get(s) as (JSONObject)->Unit
        manager.addListener(listener)
        return s
    }
    private fun frame(id:String,p:JSONObject) = JSONObject().put("type","request").put("requestId",id).put("method","power").put("params",p)
    private fun call(f:JSONObject): JSONObject {
        queue.clear()
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply{isAccessible=true}.invoke(service,f.toString())
        val emitted=queue.toList().map{JSONObject(it)}
        events.addAll(emitted.filter{it.optString("event")=="power"})
        emitted.forEach { WireSchema(context).validate(it) }
        return emitted.last{it.optString("type")=="response"}
    }
    private fun acquire(id:String,deadline:Long?=null): JSONObject = frame(id,JSONObject().put("operation","acquire").put("requestId",id).put("owner","test-owner").put("scope","device").also{if(deadline!=null) it.put("deadlineAfterMs",deadline)})
    private fun release(id:String,lease:String) = frame(id,JSONObject().put("operation","release").put("requestId",id).put("leaseId",lease))
    private fun status() = call(frame("status",JSONObject().put("operation","status"))).getJSONObject("result").getJSONObject("state")
    @Before fun setup() {
        // Robolectric replaces the Application between tests; mirror a fresh process singleton.
        PowerLeases::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context=ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WAKE_LOCK)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,true)
        manager=PowerLeases.get(context);service=attach()
    }
    @After fun cleanup() {
        val leases=manager.state().getJSONArray("activeLeases")
        for(i in 0 until leases.length()) manager.release("cleanup-$i",leases.getJSONObject(i).getString("leaseId"))
        service.onDestroy()
    }
    @Test fun defaultHoldSurvivesElapsedTimeAndServiceRebindAndReplaysOnlyHistoricalReceipt() {
        val f=acquire("hold")
        val first=call(f);assertTrue(first.toString(),first.getBoolean("ok"))
        val lock=ShadowPowerManager.getLatestWakeLock();assertTrue(lock.isHeld)
        val process=status().getString("processInstanceId")
        assertTrue(status().getJSONArray("activeLeases").getJSONObject(0).isNull("deadlineAt"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofDays(7))
        assertTrue(lock.isHeld)
        service.onDestroy();service=attach()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(process,status().getString("processInstanceId"));assertTrue(status().getBoolean("backendHeld"))
        val repeated=call(f);assertEquals(first.toString(),repeated.toString())
        assertEquals(1,shadowOf(lock).timesHeld)
        assertEquals("changed",call(release("release","hold")).getJSONObject("result").getString("observedOutcome"))
        assertFalse(status().getBoolean("backendHeld"))
        assertEquals(first.toString(),call(f).toString()) // historical success must not resurrect the lease
        assertFalse(status().getBoolean("backendHeld"))
        assertEquals("unchanged",call(release("release-again","hold")).getJSONObject("result").getString("observedOutcome"))
    }
    @Test fun independentLeasesAndExplicitDeadlineEmitExactProvenance() {
        assertTrue(call(acquire("untimed")).getBoolean("ok"))
        assertTrue(call(acquire("timed",1000)).getBoolean("ok"))
        val timedLock=ShadowPowerManager.getLatestWakeLock()
        assertEquals(2,status().getJSONArray("activeLeases").length())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        val timedEvents=queue.toList().map{JSONObject(it)}.filter{it.optString("event")=="power"};events.addAll(timedEvents)
        assertFalse(timedLock.isHeld)
        val now=status();assertTrue(now.getBoolean("backendHeld"));assertEquals(1,now.getJSONArray("activeLeases").length())
        assertEquals("untimed",now.getJSONArray("activeLeases").getJSONObject(0).getString("leaseId"))
        for(event in events) {
            WireSchema(context).validate(event)
            assertEquals(event.getJSONObject("powerState").getString("observedAt"),event.getString("observedAt"))
            assertEquals("power-test-epoch",event.getString("serviceInstanceId"))
            println("AI_PHONE_POWER_EVENT "+event.toString())
        }
        assertTrue(events.any { it.getJSONObject("powerState").getJSONArray("activeLeases").length()==1 })
    }
    @Test fun invalidTargetDeadlineScopeAndConflictNeverAcquireAnotherLock() {
        val invalid=listOf(acquire("display").also{it.getJSONObject("params").put("displayId",0)},
            acquire("deadline",0),acquire("fraction").also{it.getJSONObject("params").put("deadlineAfterMs",1.5)},
            acquire("scope").also{it.getJSONObject("params").put("scope","display")},
            acquire("mismatch").also{it.getJSONObject("params").put("requestId","other")})
        for(f in invalid) { assertFalse(call(f).getBoolean("ok"));assertFalse(status().getBoolean("backendHeld")) }
        assertTrue(call(acquire("owned")).getBoolean("ok"))
        val lock=ShadowPowerManager.getLatestWakeLock()
        val conflict=acquire("owned").also { it.getJSONObject("params").put("owner","someone-else") }
        assertEquals("request_conflict",call(conflict).getJSONObject("error").getString("code"))
        assertEquals(1,shadowOf(lock).timesHeld)
        assertEquals("test-owner",status().getJSONArray("activeLeases").getJSONObject(0).getString("owner"))
    }
    @Test fun unsupportedBackendIsAdvertisedAndRejectedBeforeJournalDispatch() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,false)
        assertFalse(manager.capabilities().getBoolean("hold"));assertFalse(manager.capabilities().getBoolean("wake"))
        val f=acquire("unsupported");assertEquals("unsupported_power",call(f).getJSONObject("error").getString("code"))
        ActionReceipts(context).use{assertNull(it.lookup("unsupported",f))}
        assertFalse(status().getBoolean("backendHeld"))
    }
    @Test @Config(sdk=[36]) fun android16WakeAppOpIsSeparateFromHoldAndActualInteractiveReadback() {
        val permission="android.permission.TURN_SCREEN_ON"
        shadowOf(context as android.app.Application).denyPermissions(permission)
        val ops=context.getSystemService(android.app.AppOpsManager::class.java)
        val op=android.app.AppOpsManager.permissionToOp(permission)!!
        shadowOf(ops).setMode(op,android.os.Process.myUid(),context.packageName,android.app.AppOpsManager.MODE_ERRORED)
        assertTrue(manager.capabilities().getBoolean("wake"));assertTrue(manager.capabilities().getBoolean("hold"))
        assertTrue(manager.capabilities().getString("reason").contains("metadata not established"))
        shadowOf(ops).setMode(op,android.os.Process.myUid(),context.packageName,android.app.AppOpsManager.MODE_DEFAULT)
        assertTrue(manager.capabilities().getBoolean("wake"))
        assertTrue(manager.capabilities().getString("reason").contains("metadata not established"))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val result=call(acquire("api36"));assertTrue(result.toString(),result.getBoolean("ok"))
        assertFalse(result.getJSONObject("result").getJSONObject("state").getBoolean("deviceInteractive"))
        shadowOf(ops).setMode(op,android.os.Process.myUid(),context.packageName,android.app.AppOpsManager.MODE_ALLOWED)
        assertTrue(manager.capabilities().getBoolean("wake"))
        WireSchema(context).validate(manager.capabilities(),"powerCapability")
    }
    @Test fun heldStateIsNotInventedInteractiveOrUnlockedState() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        val result=call(acquire("locked")).getJSONObject("result")
        assertFalse(result.getJSONObject("state").getBoolean("backendHeld")) // native lock alone is insufficient
        assertFalse(result.getJSONObject("state").getBoolean("deviceInteractive"))
        assertTrue(result.getJSONObject("state").getBoolean("keyguardLocked"))
        assertEquals("changed",result.getString("observedOutcome")) // actual lease transition only
        assertEquals("device",result.getJSONObject("state").getString("scope"))
        assertFalse(result.getJSONObject("state").getBoolean("displayTargeting"))
        shadowOf(Looper.getMainLooper()).idle()
        val attached=status()
        assertTrue(attached.getBoolean("backendHeld"))
        assertFalse(attached.getBoolean("deviceInteractive"))
        assertTrue(attached.getBoolean("keyguardLocked"))
    }
}
