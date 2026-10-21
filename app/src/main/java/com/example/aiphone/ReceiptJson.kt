package com.example.aiphone

import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigInteger

/** Android's default JSON parser converts integers beyond Long to lossy Double values. */
internal object ReceiptJson {
    private class IntegerValue(text: String): BigInteger(text) {
        override fun toByte(): Byte = toInt().toByte()
        override fun toShort(): Short = toInt().toShort()
        // JSONObject.put checks this conversion for finiteness even for integer Numbers.
        override fun toDouble(): Double = super.toDouble().let { if(it.isInfinite()) {
            if(signum()<0) -Double.MAX_VALUE else Double.MAX_VALUE
        } else it }
    }
    fun parseObject(raw: String): JSONObject {
        val tokens=object: JSONTokener(raw) {
        override fun nextValue(): Any {
            val first=nextClean()
            back()
            if(first=='-' || first in '0'..'9') {
                val token=nextTo(",]} \t\r\n")
                if(Regex("-?(0|[1-9][0-9]*)").matches(token)) return IntegerValue(token)
                // Other JSON values retain Android's existing parser semantics. Strings are
                // handled by super.nextValue(), so numeric-looking text is never rewritten.
                return JSONTokener(token).nextValue()
            }
            return super.nextValue()
        }
        }
        val value=JSONObject(tokens)
        require(tokens.nextClean()=='\u0000') { "Trailing data after stored receipt JSON" }
        return value
    }
}
