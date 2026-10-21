package com.example.aiphone

import android.view.accessibility.AccessibilityNodeInfo
import java.math.BigInteger

/**
 * Handles follow Android node equality (source-node ID + window ID), additionally scoped to display.
 * Identity keys own untouched copies, never refreshed/recycled while indexed. Retired handles are
 * never reassigned within an epoch, including if a native identity reappears after an absent scan.
 * Android/provider reuse of the exact same native identity between observations is not observable.
 */
@Suppress("DEPRECATION")
internal class NativeNodeHandles {
    private data class Identity(val display: Int, val window: Int, val node: AccessibilityNodeInfo)
    private var current = linkedMapOf<Identity, String>()
    private var pending = linkedMapOf<Identity, String>()
    private var next = BigInteger.ZERO

    fun beginCapture() {
        pending.keys.forEach { it.node.recycle() }
        pending.clear()
    }

    fun id(display: Int, window: Int, node: AccessibilityNodeInfo): String {
        val key = Identity(display, window, node)
        pending[key]?.let { return it }
        val handle = current[key] ?: "$display:$window:$next".also { next += BigInteger.ONE }
        pending[Identity(display, window, AccessibilityNodeInfo.obtain(node))] = handle
        return handle
    }

    fun commitCapture() {
        current.keys.forEach { it.node.recycle() }
        current = pending
        pending = linkedMapOf()
    }

    fun resetEpoch() {
        current.keys.forEach { it.node.recycle() }
        pending.keys.forEach { it.node.recycle() }
        current.clear()
        pending.clear()
        next = BigInteger.ZERO
    }
}
