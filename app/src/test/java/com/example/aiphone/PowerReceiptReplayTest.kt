package com.example.aiphone

import android.Manifest
import android.content.Context
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
import java.math.BigInteger
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class PowerReceiptReplayTest {
    private lateinit var context:Context
    private lateinit var manager:PowerLeases
    private lateinit var service:AiPhoneAccessibilityService
    private lateinit var journal:ActionReceipts
    private lateinit var queue:LinkedBlockingQueue<String>
    private fun attach():AiPhoneAccessibilityService {
        val s=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        fun set(n:String,v:Any) {s.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(s,v)}
        journal=ActionReceipts(s);queue=LinkedBlockingQueue()
        set("receipts",journal);set("outbound",queue);set("socket",Socket());set("protocolReady",true)
        manager.bindService(s)
        return s
    }
    private fun counter()=manager.javaClass.getDeclaredField("powerRevision").apply{isAccessible=true}.get(manager) as BigInteger
    private fun seed(n:BigInteger) {manager.javaClass.getDeclaredField("powerRevision").apply{isAccessible=true}.set(manager,n)}
    @Before fun setup() {
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
    private fun acquire(id:String)=JSONObject().put("type","request").put("method","power").put("requestId",id)
        .put("params",JSONObject().put("operation","acquire").put("requestId",id).put("scope","device")
            .put("owner","owner \"powerRevision\":9223372036854775809 and Unicode λ"))
    private fun release(id:String,lease:String)=JSONObject().put("type","request").put("method","power").put("requestId",id)
        .put("params",JSONObject().put("operation","release").put("requestId",id).put("leaseId",lease))
    private fun call(frame:JSONObject):String {
        queue.clear()
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply{isAccessible=true}.invoke(service,frame.toString())
        return queue.toList().single().also { println("AI_PHONE_RECEIPT_FRAME "+it) }
    }
    private fun persisted(id:String):String = journal.readableDatabase.query("receipts",arrayOf("response"),"id=?",arrayOf(id),null,null,null).use {
        assertTrue(it.moveToFirst());it.getString(0)
    }
    private fun assertRevisions(raw:String,before:BigInteger,after:BigInteger) {
        assertTrue(raw.contains("\"powerRevision\":$before"));assertTrue(raw.contains("\"powerRevision\":$after"))
        assertFalse(raw.contains("Infinity"))
        val response=ReceiptJson.parseObject(raw);assertTrue(raw,response.getBoolean("ok"));WireSchema(context).validate(response)
        val result=response.getJSONObject("result")
        assertEquals(before.toString(),result.getJSONObject("before").get("powerRevision").toString())
        assertEquals(after.toString(),result.getJSONObject("state").get("powerRevision").toString())
    }
    @Test fun persistedAcquireAndReleaseDuplicatesPreserveExactHugeIntegersWithoutRedispatch() {
        val boundaries=listOf(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE),BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TWO),BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE),BigInteger.TEN.pow(400).add(BigInteger.ONE))
        for((i,boundary) in boundaries.withIndex()) {
            seed(boundary.subtract(BigInteger.ONE))
            val acquire=acquire("acquire-$i");val first=call(acquire)
            assertRevisions(first,boundary,boundary.add(BigInteger.ONE))
            assertEquals(first,persisted("acquire-$i"))
            val lock=ShadowPowerManager.getLatestWakeLock();assertTrue(lock.isHeld);assertEquals(1,shadowOf(lock).timesHeld)
            val beforeReplay=counter()
            // Reopen the actual SQLite journal via a new service; the process-owned lock remains held.
            service.onDestroy();service=attach()
            assertEquals(first,call(acquire));assertEquals(beforeReplay,counter())
            assertEquals(first,persisted("acquire-$i"));assertTrue(lock.isHeld);assertEquals(1,shadowOf(lock).timesHeld)
            assertEquals(boundary.add(BigInteger.ONE).toString(),journal.lookup("acquire-$i",acquire)!!.getJSONObject("result").getJSONObject("state").get("powerRevision").toString())
            val release=release("release-$i","acquire-$i");val released=call(release)
            assertRevisions(released,boundary.add(BigInteger.TWO),boundary.add(BigInteger.valueOf(3)))
            assertFalse(lock.isHeld);assertEquals(released,persisted("release-$i"))
            val afterRelease=counter()
            service.onDestroy();service=attach()
            assertEquals(released,call(release));assertEquals(afterRelease,counter())
            assertEquals(released,persisted("release-$i"));assertFalse(lock.isHeld)
            assertEquals(first,call(acquire));assertEquals(afterRelease,counter())
            assertEquals(1,shadowOf(lock).timesHeld);assertFalse(lock.isHeld)
            assertEquals(0,manager.state().getJSONArray("activeLeases").length())
            println("AI_PHONE_RECEIPT_BOUNDARY "+boundary+" exactAcquireReleaseReplay=true sqliteReopened=true noRedispatch=true")
        }
    }
    @Test fun storedReplayIsValidatedBeforeSendingWithoutRedispatch() {
        val request=acquire("invalid-history");val first=call(request);assertTrue(ReceiptJson.parseObject(first).getBoolean("ok"))
        val lock=ShadowPowerManager.getLatestWakeLock();val before=counter()
        val invalid=first.replace(Regex("\"powerRevision\":\\d+"),"\"powerRevision\":0")
        for(corrupt in listOf(invalid,first+" false")) {
            journal.writableDatabase.execSQL("UPDATE receipts SET response=? WHERE id=?",arrayOf(corrupt,"invalid-history"))
            val reply=ReceiptJson.parseObject(call(request));assertFalse(reply.getBoolean("ok"))
            assertEquals(before,counter());assertEquals(1,shadowOf(lock).timesHeld);assertTrue(lock.isHeld)
            assertEquals(corrupt,persisted("invalid-history"))
        }
    }
}
