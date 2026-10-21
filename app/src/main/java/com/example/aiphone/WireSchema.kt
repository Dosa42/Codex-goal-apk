package com.example.aiphone

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** Evaluates the constraints used by the authoritative bundled draft-2020-12 contract. */
class WireSchema(context: Context) {
    private val bytes=context.assets.open("ai-phone.schema.json").use { it.readBytes() }
    val schema=JSONObject(bytes.toString(Charsets.UTF_8))
    val sha256=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun validate(value: Any?, definition:String?=null) {
        val spec=if(definition==null) schema else schema.getJSONObject("\$defs").getJSONObject(definition)
        checkValue(value,spec,"$")
    }
    private fun reject(path:String, reason:String): Nothing = throw IllegalArgumentException("$path: $reason")
    private fun equalValue(a:Any?, b:Any?):Boolean = if(a is Number && b is Number) {
        try { java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString()))==0 }
        catch(_:NumberFormatException) { false }
    } else a==b
    private fun checkValue(value:Any?, spec:JSONObject, path:String) {
        if(spec.has("\$ref")) {
            val ref=spec.getString("\$ref")
            require(ref.startsWith("#/\$defs/")) { "Unsupported schema reference" }
            checkValue(value,schema.getJSONObject("\$defs").getJSONObject(ref.substringAfterLast('/')),path)
        }
        for(kind in listOf("oneOf","anyOf")) if(spec.has(kind)) {
            val choices=spec.getJSONArray(kind)
            val count=(0 until choices.length()).count { try {checkValue(value,choices.getJSONObject(it),path);true} catch(_:IllegalArgumentException){false} }
            if((kind=="oneOf" && count!=1) || (kind=="anyOf" && count==0)) reject(path,"does not satisfy $kind")
        }
        if(spec.has("const") && !equalValue(spec.get("const"),value)) reject(path,"const mismatch")
        if(spec.has("enum")) {
            val choices=spec.getJSONArray("enum")
            if((0 until choices.length()).none { equalValue(choices.get(it),value) }) reject(path,"enum mismatch")
        }
        fun isType(type:String)=when(type) {
            "null" -> value==null || value==JSONObject.NULL
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number && value.toDouble().isFinite()
            "integer" -> value is Number && try { java.math.BigDecimal(value.toString()).stripTrailingZeros().scale()<=0 } catch(_:NumberFormatException) { false }
            else -> throw IllegalStateException("Unsupported schema type $type")
        }
        if(spec.has("type")) {
            val type=spec.get("type")
            val valid=if(type is JSONArray) (0 until type.length()).any{isType(type.getString(it))} else isType(type as String)
            if(!valid) reject(path,"type mismatch")
        }
        if(value is Number && spec.has("minimum")) {
            if(java.math.BigDecimal(value.toString()) < java.math.BigDecimal(spec.get("minimum").toString())) reject(path,"below minimum")
        }
        if(value is String) {
            if(spec.has("minLength") && value.codePointCount(0,value.length)<spec.getInt("minLength")) reject(path,"string too short")
            if(spec.has("pattern") && !Regex(spec.getString("pattern")).containsMatchIn(value)) reject(path,"pattern mismatch")
        }
        if(value is JSONArray) {
            if(spec.has("minItems") && value.length()<spec.getInt("minItems")) reject(path,"array too short")
            if(spec.has("items")) for(i in 0 until value.length()) checkValue(value.get(i),spec.getJSONObject("items"),"$path[$i]")
        }
        if(value is JSONObject) {
            val required=spec.optJSONArray("required") ?: JSONArray()
            for(i in 0 until required.length()) if(!value.has(required.getString(i))) reject(path,"missing ${required.getString(i)}")
            val props=spec.optJSONObject("properties") ?: JSONObject()
            value.keys().forEach { key ->
                if(props.has(key)) checkValue(value.get(key),props.getJSONObject(key),"$path.$key")
                else when(val extra=spec.opt("additionalProperties")) {
                    false -> reject(path,"unknown field $key")
                    is JSONObject -> checkValue(value.get(key),extra,"$path.$key")
                }
            }
        }
    }
}
