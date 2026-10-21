package com.example.aiphone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class AiPhoneValidationTest {
    private fun request(method:String, params:JSONObject, ready:Boolean=true): JSONObject {
        val service=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        val journal=ActionReceipts(service)
        fun set(name:String,value:Any) { service.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(service,value) }
        val queue=LinkedBlockingQueue<String>()
        set("outbound",queue);set("socket",Socket());set("protocolReady",ready);set("receipts",journal)
        val frame=JSONObject().put("type","request").put("requestId","validation-"+java.util.UUID.randomUUID()).put("method",method)
        if((method=="act" || method=="launch") && !params.has("requestId")) params.put("requestId",frame.getString("requestId"))
        frame.put("params",params)
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply {isAccessible=true}.invoke(service,frame.toString())
        val response=queue.toList().map{JSONObject(it)}.last{it.optString("type")=="response"}
        assertNull(journal.lookup(frame.getString("requestId"),frame)) // no dispatch record means mutation was never begun
        journal.close()
        return response
    }
    @Test fun requestsRequireVersionTwoHandshake() {
        val r=request("discover",JSONObject(),false)
        assertFalse(r.getBoolean("ok"));assertEquals("handshake_required",r.getJSONObject("error").getString("code"))
    }
    @Test fun rejectsUnknownActionBeforeDispatch() {
        val r=request("act",JSONObject().put("action","fake_success"))
        assertFalse(r.getBoolean("ok"));assertEquals("invalid_frame",r.getJSONObject("error").getString("code"))
    }
    @Test fun rejectsUnknownMutationFieldsBeforeDispatch() {
        val r=request("launch",JSONObject().put("packageName","android").put("unexpected",true))
        assertFalse(r.getBoolean("ok"));assertEquals("invalid_frame",r.getJSONObject("error").getString("code"))
    }
    @Test fun rejectsFractionalDisplayBeforeDispatch() {
        val r=request("act",JSONObject().put("action","home").put("scope","device").put("displayId",0.5))
        assertFalse(r.getBoolean("ok"));assertEquals("invalid_frame",r.getJSONObject("error").getString("code"))
    }
    @Test fun discoverReturnsTheFullBundledSchema() {
        val r=request("discover",JSONObject())
        assertTrue(r.getBoolean("ok"))
        assertEquals("discover",r.getString("method"))
        val result=r.getJSONObject("result")
        assertEquals("97f9ffd140da36aeec750f897cfe31687bc17cf48df260b1ad9e0b48bca6eafa",result.getString("schemaSha256"))
        assertTrue(result.getJSONObject("schema").getJSONObject("\$defs").has("fileParams"))
    }
    @Test fun mismatchedRequestIdsNeverDispatch() {
        val r=request("act",JSONObject().put("requestId","different").put("action","home").put("scope","device").put("serviceInstanceId","old").put("revision",0).put("displayId",0).put("args",JSONObject()))
        assertFalse(r.getBoolean("ok"));assertEquals("invalid_params",r.getJSONObject("error").getString("code"))
    }
    @Test fun staleEpochNeverDispatches() {
        val r=request("act",JSONObject().put("action","home").put("scope","device").put("serviceInstanceId","old").put("revision",0).put("displayId",0).put("args",JSONObject()))
        assertFalse(r.getBoolean("ok"));assertEquals("stale_handle",r.getJSONObject("error").getString("code"))
    }
}
