package com.example.aiphone

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.math.BigInteger

/** Process-owned screen locks. Service/transport lifetimes never own or restore these leases. */
@Suppress("DEPRECATION")
internal class PowerLeases private constructor(private val context: Context) {
    companion object {
        private const val BACKEND="android_screen_wake_lock_with_accessibility_overlay"
        @Volatile private var instance: PowerLeases? = null
        fun get(context: Context): PowerLeases = instance ?: synchronized(this) {
            instance ?: PowerLeases(context.applicationContext).also { instance = it }
        }
    }
    class Failure(val code: String, message: String): Exception(message)
    private data class Lease(val id: String, val owner: String, val acquiredAt: String,
        val deadlineAt: String?, val deadlineElapsed: Long?, val lock: PowerManager.WakeLock,
        var expiry: Runnable? = null)
    val processInstanceId: String = UUID.randomUUID().toString()
    private var powerRevision = BigInteger.ZERO
    private val observationClock: () -> String = { timestamp() }
    // Android JSONObject checks doubleValue for finiteness before writing Number.toString().
    // Retain the unbounded integer's decimal text even beyond Double's finite range.
    private class WireRevision(value: BigInteger): BigInteger(value.toByteArray()) {
        override fun toByte(): Byte = toInt().toByte()
        override fun toShort(): Short = toInt().toShort()
        override fun toDouble(): Double = super.toDouble().let { if(it.isInfinite()) Double.MAX_VALUE else it }
    }
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val leases = linkedMapOf<String, Lease>()
    private val listeners = linkedSetOf<(JSONObject) -> Unit>()
    private var backendFailure: String? = null
    private val screenHold=ScreenHoldOverlay { if(listeners.isNotEmpty()) observationChanged() }
    fun bindService(service: AccessibilityService) {
        mainThread()
        screenHold.bind(service)
        if(leases.isNotEmpty()) {
            try { screenHold.ensure();backendFailure=null }
            catch(e:Exception) { backendFailure="Screen hold window unavailable: ${e.message}" }
            if(listeners.isNotEmpty()) observationChanged()
        }
    }
    fun unbindService(service: AccessibilityService) {
        mainThread()
        screenHold.unbind(service)
        // Native leases remain process-owned; the window's service-bound gap is observable.
        if(leases.isNotEmpty() && listeners.isNotEmpty()) observationChanged()
    }
    init {
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { observationChanged() }
        }
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT)
        }
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver,filter)
    }
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { j -> pairs.forEach { j.put(it.first,it.second ?: JSONObject.NULL) } }
    private fun timestamp(millis: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date(millis))
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Power leases require main-thread serialization" }
    private fun canHold() = context.checkSelfPermission(Manifest.permission.WAKE_LOCK)==PackageManager.PERMISSION_GRANTED && power.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)
    private fun wakePermissionKnown(): Boolean {
        if(Build.VERSION.SDK_INT < 34) return true
        val permission="android.permission.TURN_SCREEN_ON"
        val granted=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
        val op=AppOpsManager.permissionToOp(permission) ?: return granted
        val mode=context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op,Process.myUid(),context.packageName)
        return mode==AppOpsManager.MODE_ALLOWED || (mode==AppOpsManager.MODE_DEFAULT && granted)
    }
    fun capabilities(): JSONObject {
        mainThread()
        val hold=canHold() && screenHold.available && backendFailure==null
        // The installed phone already proved this public wake route despite inconclusive app-op
        // metadata. Advertise attemptability; only actual backend failure blocks the route.
        val wake=hold
        val wakeMetadataKnown=wakePermissionKnown()
        return obj("wake" to wake,"hold" to hold,"release" to (context.checkSelfPermission(Manifest.permission.WAKE_LOCK)==PackageManager.PERMISSION_GRANTED),
            "scope" to "device","displayTargeting" to false,"backend" to BACKEND,"holdDisplayId" to 0,
            "reason" to (backendFailure ?: if(!hold) "Screen wake lock support and connected accessibility service/display 0 required" else if(!wakeMetadataKnown) "Public wake attempt available; permission/app-op metadata not established and compatibility may apply. Wake may affect both displays; hold window is display 0. Verify actual display/UI readback" else "Native wake may affect both displays; visible accessibility window holds display 0. Route availability and attached resources do not guarantee wake or UI readability"))
    }
    fun addListener(listener: (JSONObject) -> Unit) { mainThread();listeners.add(listener) }
    fun removeListener(listener: (JSONObject) -> Unit) { mainThread();listeners.remove(listener) }
    private fun notifyState(state: JSONObject) { listeners.toList().forEach { it(state) } }
    private fun releaseLease(lease: Lease) {
        // Remove ownership only after release succeeds; exceptions retain truthful state for inspection.
        if(leases.size==1) screenHold.remove()
        if(lease.lock.isHeld) lease.lock.release()
        lease.expiry?.let { handler.removeCallbacks(it) }
        leases.remove(lease.id)
    }
    private fun expireDue(): Boolean {
        val due=leases.values.filter { it.deadlineElapsed?.let { end -> end<=SystemClock.elapsedRealtime() } == true }
        due.forEach { releaseLease(it) }
        return due.isNotEmpty()
    }
    fun state(): JSONObject {
        mainThread()
        val expired=expireDue()
        val state=readState()
        if(expired) notifyState(state)
        return state
    }
    @Synchronized private fun readState(): JSONObject {
        mainThread()
        powerRevision=powerRevision.add(BigInteger.ONE)
        val displays=JSONArray()
        context.getSystemService(DisplayManager::class.java).displays.forEach { d ->
            val size=android.graphics.Point();d.getRealSize(size)
            displays.put(obj("id" to d.displayId,"displayId" to d.displayId,"name" to d.name,"width" to size.x,"height" to size.y,"state" to d.state.toString()))
        }
        return obj("source" to "android_power","observedAt" to observationClock(),"processInstanceId" to processInstanceId,"powerRevision" to WireRevision(powerRevision),
            "scope" to "device","displayTargeting" to false,"backend" to BACKEND,"holdDisplayId" to 0,
            "backendHeld" to (screenHold.attached && leases.values.any { it.lock.isHeld }),"deviceInteractive" to power.isInteractive,
            "keyguardLocked" to context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,"displays" to displays,
            "activeLeases" to JSONArray().also { a -> leases.values.forEach { l -> a.put(obj("leaseId" to l.id,"owner" to l.owner,
                "acquiredAt" to l.acquiredAt,"deadlineAt" to l.deadlineAt,"held" to (l.lock.isHeld && screenHold.attached),"scope" to "device")) } })
    }
    fun observationChanged() { mainThread();notifyState(state()) }
    /** Check representability before recording dispatch; there is no application duration cap. */
    fun validateAcquire(params: JSONObject) {
        mainThread()
        if(!canHold()) throw Failure("unsupported_power","WAKE_LOCK permission or screen-bright lock support unavailable")
        if(!screenHold.available) throw Failure("unsupported_power","Connected accessibility service and phone display 0 required for continuous screen hold")
        if(params.has("deadlineAfterMs")) {
            try {
                val duration=java.math.BigDecimal(params.get("deadlineAfterMs").toString()).longValueExact()
                require(duration>0)
                Math.addExact(SystemClock.elapsedRealtime(),duration)
                Math.addExact(System.currentTimeMillis(),duration)
            } catch(e:Exception) { throw Failure("invalid_params","deadlineAfterMs must be a positive integer representable by Android's millisecond clock") }
        }
    }
    fun acquire(requestId: String, params: JSONObject): JSONObject {
        mainThread()
        if(leases.containsKey(requestId)) throw Failure("request_conflict","Lease ID is already owned; use immutable receipt/status")
        val before=state()
        val duration=if(params.has("deadlineAfterMs")) java.math.BigDecimal(params.get("deadlineAfterMs").toString()).longValueExact() else null
        val now=System.currentTimeMillis()
        val deadline=duration?.let { Math.addExact(SystemClock.elapsedRealtime(),it) }
        val lock=power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,"ai-phone:lease:$requestId")
        lock.setReferenceCounted(false)
        try {
            screenHold.ensure()
            // No implicit TTL. Every acquisition requests wake, including additional concurrent leases.
            if(duration==null) lock.acquire() else lock.acquire(duration)
            if(!lock.isHeld) throw Failure("power_not_held","Android did not retain the requested screen wake lock")
        } catch(e:Exception) {
            if(lock.isHeld) lock.release()
            if(leases.isEmpty()) screenHold.remove()
            backendFailure=e.message ?: e.javaClass.simpleName
            throw Failure("power_backend_failed",backendFailure!!)
        }
        backendFailure=null
        val lease=Lease(requestId,params.getString("owner"),timestamp(now),duration?.let { timestamp(Math.addExact(now,it)) },deadline,lock)
        leases[requestId]=lease
        if(duration!=null) {
            val expiry=Runnable { observationChanged() }
            lease.expiry=expiry
            handler.postDelayed(expiry,duration)
        }
        val after=state()
        notifyState(after)
        return obj("operation" to "acquire","requestId" to requestId,"accepted" to true,"observedOutcome" to if(leases[requestId]?.lock?.isHeld==true) "changed" else "unverified",
            "leaseId" to requestId,"before" to before,"state" to after)
    }
    fun release(requestId: String, leaseId: String): JSONObject {
        mainThread()
        val before=state()
        val lease=leases[leaseId]
        if(lease!=null) releaseLease(lease)
        val after=state()
        if(lease!=null) notifyState(after)
        return obj("operation" to "release","requestId" to requestId,"accepted" to true,
            "observedOutcome" to if(lease!=null) "changed" else "unchanged","leaseId" to leaseId,"before" to before,"state" to after)
    }
}
