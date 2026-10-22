package com.example.aiphone

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.accessibilityservice.AccessibilityService
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
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
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ScreenHoldOverlayTest {
    class DeniedOverlayService: AccessibilityService() {
        override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
        override fun onInterrupt() {}
        override fun createDisplayContext(display:Display):Context {
            val base=super.createDisplayContext(display)
            return object:ContextWrapper(base) {
                override fun getSystemService(name:String):Any? {
                    if(name!=Context.WINDOW_SERVICE) return super.getSystemService(name)
                    val actual=base.getSystemService(WindowManager::class.java)
                    return java.lang.reflect.Proxy.newProxyInstance(WindowManager::class.java.classLoader,
                        arrayOf(WindowManager::class.java)) { _, method, args ->
                        if(method.name=="addView") throw SecurityException("test actual WindowManager.addView denial")
                        method.invoke(actual,*(args ?: emptyArray()))
                    }
                }
            }
        }
    }
    private lateinit var context:Context
    private lateinit var manager:PowerLeases
    private lateinit var service:AiPhoneAccessibilityService
    private fun overlay()=manager.javaClass.getDeclaredField("screenHold").apply{isAccessible=true}.get(manager) as ScreenHoldOverlay
    private fun window()=overlay().javaClass.getDeclaredField("view").apply{isAccessible=true}.get(overlay()) as View?
    private fun connect():AiPhoneAccessibilityService = Robolectric.buildService(AiPhoneAccessibilityService::class.java).create().get().also{manager.bindService(it)}
    private fun acquire(id:String,deadline:Long?=null):JSONObject {
        val p=JSONObject().put("operation","acquire").put("requestId",id).put("owner","overlay-test").put("scope","device")
        if(deadline!=null) p.put("deadlineAfterMs",deadline)
        manager.validateAcquire(p)
        return manager.acquire(id,p)
    }
    @Before fun setup() {
        PowerLeases::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context=ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.WAKE_LOCK)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,true)
        manager=PowerLeases.get(context);service=connect()
    }
    @After fun cleanup() {
        val leases=manager.state().getJSONArray("activeLeases")
        for(i in 0 until leases.length()) manager.release("cleanup-$i",leases.getJSONObject(i).getString("leaseId"))
        service.onDestroy()
    }
    @Test fun phoneWindowPassesInputAndPersistsBeyondTimeoutUntilLastRelease() {
        val external=ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        assertTrue(external>0)
        acquire("first");val firstLock=ShadowPowerManager.getLatestWakeLock()
        shadowOf(Looper.getMainLooper()).idle()
        val v=window()!!
        val p=v.layoutParams as WindowManager.LayoutParams
        assertEquals(0,v.display.displayId)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,p.type)
        val required=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        assertEquals(required,p.flags and required)
        assertEquals(0,p.flags and (WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON))
        assertEquals(View.VISIBLE,v.visibility);assertTrue(v.isAttachedToWindow)
        assertFalse(v.isFocusable);assertFalse(v.isClickable);assertFalse(v.hasWindowFocus())
        assertTrue(p.width>0 && p.height>0);assertEquals(1f,p.alpha)
        acquire("second");val secondLock=ShadowPowerManager.getLatestWakeLock()
        assertSame(v,window())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
        assertTrue(v.isAttachedToWindow);assertTrue(firstLock.isHeld);assertTrue(secondLock.isHeld)
        assertEquals(1,shadowOf(firstLock).timesHeld);assertEquals(1,shadowOf(secondLock).timesHeld)
        manager.release("release-first","first")
        assertSame(v,window());assertTrue(v.isAttachedToWindow);assertFalse(firstLock.isHeld)
        manager.release("release-last","second")
        assertNull(window());assertFalse(v.isAttachedToWindow);assertFalse(secondLock.isHeld)
        println("AI_PHONE_OVERLAY_CASE phone0=true inputTransparent=true beyond45s=true sharedWindow=true lastReleaseRemoved=true")
    }
    @Test fun serviceTeardownRemovesWindowAndRebindRestoresExistingLeaseWithoutWakeReacquire() {
        acquire("retained");val lock=ShadowPowerManager.getLatestWakeLock()
        shadowOf(Looper.getMainLooper()).idle()
        val original=window()!!;val process=manager.processInstanceId
        service.onDestroy()
        assertNull(window());assertFalse(original.isAttachedToWindow);assertTrue(lock.isHeld)
        assertFalse(manager.state().getBoolean("backendHeld"))
        assertFalse(manager.state().getJSONArray("activeLeases").getJSONObject(0).getBoolean("held"))
        assertFalse(manager.capabilities().getBoolean("hold"))
        service=connect();shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(original,window());assertTrue(window()!!.isAttachedToWindow)
        assertTrue(manager.state().getBoolean("backendHeld"))
        assertEquals(process,manager.processInstanceId)
        assertEquals(1,shadowOf(lock).timesHeld);assertSame(lock,ShadowPowerManager.getLatestWakeLock())
        assertEquals("retained",manager.state().getJSONArray("activeLeases").getJSONObject(0).getString("leaseId"))
        println("AI_PHONE_OVERLAY_CASE teardownRemoved=true rebindSameLease=true noWakeReacquire=true")
    }
    @Test fun explicitDeadlineRemovesWindowAndMissingServiceRejectsWithoutLease() {
        acquire("deadline",1000)
        shadowOf(Looper.getMainLooper()).idle()
        val v=window()!!;val lock=ShadowPowerManager.getLatestWakeLock()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        assertNull(window());assertFalse(v.isAttachedToWindow);assertFalse(lock.isHeld)
        service.onDestroy()
        try { acquire("disconnected");fail("Expected unavailable overlay backend") }
        catch(e:PowerLeases.Failure) { assertEquals("unsupported_power",e.code) }
        assertEquals(0,manager.state().getJSONArray("activeLeases").length())
        println("AI_PHONE_OVERLAY_CASE deadlineRemoved=true disconnectedRejected=true")
    }
    @Test fun windowManagerFailureRollsBackWithoutAdvertisingHeldResources() {
        service.onDestroy()
        val denied=Robolectric.buildService(DeniedOverlayService::class.java).create().get()
        manager.bindService(denied)
        try { acquire("denied");fail("Expected WindowManager failure") }
        catch(e:PowerLeases.Failure) { assertEquals("power_backend_failed",e.code) }
        assertNull(window());assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        val state=manager.state()
        assertFalse(state.getBoolean("backendHeld"));assertEquals(0,state.getJSONArray("activeLeases").length())
        assertFalse(manager.capabilities().getBoolean("hold"))
        assertTrue(manager.capabilities().getString("reason").contains("denial"))
        assertFalse(manager.capabilities().getBoolean("wake"))
        manager.unbindService(denied);denied.onDestroy()
        println("AI_PHONE_OVERLAY_CASE addViewFailureRolledBack=true capabilityFailure=true noHeldClaim=true")
    }
}
