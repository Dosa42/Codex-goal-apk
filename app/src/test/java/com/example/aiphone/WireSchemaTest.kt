package com.example.aiphone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class WireSchemaTest {
    @Test fun authoritativeFixturesAndExactHash() {
        val wire=WireSchema(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("d87943ccc6013f57547b4cca2c02296ea2b40460e2785a1e9a122e9d823b2f11",wire.sha256)
        try { wire.validate(JSONObject("""{"type":"helloAck","protocol":"ai-phone/1","brokerInstanceId":"old"}"""));fail("Old protocol accepted") } catch(_:IllegalArgumentException) { }
        val fixtures=JSONObject(javaClass.classLoader!!.getResourceAsStream("ai-phone-examples.json")!!.bufferedReader().use {it.readText()}).getJSONArray("cases")
        assertEquals(70,fixtures.length())
        for(i in 0 until fixtures.length()) {
            val case=fixtures.getJSONObject(i)
            val valid=try {wire.validate(case.get("value"),case.getString("definition"));true} catch(_:IllegalArgumentException) {false}
            assertEquals(case.getString("name"),case.getBoolean("valid"),valid)
        }
    }
}
