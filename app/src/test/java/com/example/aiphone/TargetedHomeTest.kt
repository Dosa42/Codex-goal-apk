package com.example.aiphone

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
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
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],shadows=[TargetedHomeTest.DisplayGate::class])
class TargetedHomeTest {
    @Implements(ActivityManager::class)
    class DisplayGate {
        companion object { var allowed=true }
        @Implementation fun isActivityStartAllowedOnDisplay(context:Context,displayId:Int,intent:Intent)=allowed
    }
    private lateinit var service:AiPhoneAccessibilityService
    private lateinit var queue:LinkedBlockingQueue<String>
    private lateinit var journal:ActionReceipts
    private val component=ComponentName("test.launcher","test.launcher.Home")
    @Before fun setup() {
        DisplayGate.allowed=true
        service=Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get()
        journal=ActionReceipts(service);queue=LinkedBlockingQueue()
        fun set(n:String,v:Any) {service.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(service,v)}
        set("receipts",journal);set("socket",Socket());set("outbound",queue);set("protocolReady",true)
    }
    @After fun cleanup() {service.onDestroy()}
    private fun registerHome() {
        val info=ResolveInfo().apply {
            isDefault=true
            activityInfo=ActivityInfo().apply {
                packageName=component.packageName;name=component.className;enabled=true;exported=true
                applicationInfo=ApplicationInfo().apply { packageName=component.packageName;enabled=true }
            }
        }
        shadowOf(service.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),info)
    }
    private fun frame(id:String,display:Int=0)=JSONObject().put("type","request").put("method","home").put("requestId",id)
        .put("params",JSONObject().put("requestId",id).put("displayId",display))
    private fun call(f:JSONObject):JSONObject {
        queue.clear()
        service.javaClass.getDeclaredMethod("receive",String::class.java).apply{isAccessible=true}.invoke(service,f.toString())
        return queue.toList().map{JSONObject(it)}.last{it.optString("type")=="response"}.also{WireSchema(service).validate(it)}
    }
    @Test fun launchesResolvedHomeOnExactRequestedDisplayWithoutGlobalFallbackAndDedupes() {
        registerHome()
        val f=frame("home");val reply=call(f);assertTrue(reply.toString(),reply.getBoolean("ok"))
        val started=shadowOf(service).nextStartedActivityForResult
        assertEquals(component,started.intent.component)
        assertEquals(Intent.ACTION_MAIN,started.intent.action);assertTrue(started.intent.hasCategory(Intent.CATEGORY_HOME))
        val options=ActivityOptions::class.java.getDeclaredConstructor(android.os.Bundle::class.java).apply { isAccessible=true }.newInstance(started.options)
        assertEquals(0,options.launchDisplayId)
        val result=reply.getJSONObject("result")
        assertEquals(component.flattenToString(),result.getString("componentName"))
        assertEquals("requested_display",result.getString("scope"));assertEquals("unverified",result.getString("observedOutcome"))
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertEquals(reply.toString(),call(f).toString());assertNull(shadowOf(service).nextStartedActivityForResult)
        println("AI_PHONE_HOME_RESPONSE "+reply)
    }
    @Test fun unresolvedOrDeniedHomeNeverFallsBackOrBeginsReceipt() {
        val missing=frame("missing");assertFalse(call(missing).getBoolean("ok"));assertNull(journal.lookup("missing",missing))
        registerHome();DisplayGate.allowed=false
        val denied=frame("denied");assertEquals("unsupported_display",call(denied).getJSONObject("error").getString("code"));assertNull(journal.lookup("denied",denied))
        assertNull(shadowOf(service).nextStartedActivity);assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
    @Test fun globalActionsRequireExplicitDeviceScopeBeforeDispatch() {
        val f=JSONObject("""{"type":"request","method":"act","requestId":"global","params":{"requestId":"global","serviceInstanceId":"unused","revision":0,"displayId":0,"action":"home","args":{}}}""")
        assertEquals("invalid_frame",call(f).getJSONObject("error").getString("code"))
        assertNull(journal.lookup("global",f));assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
}
