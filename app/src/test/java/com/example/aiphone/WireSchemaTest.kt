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
        assertEquals("f26a351a0c2b0a21a40d3e73a3ecc12b1284b50cd457b72f7edd8f0beafdd321",wire.sha256)
        val fixtures=JSONObject(javaClass.classLoader!!.getResourceAsStream("ai-phone-examples.json")!!.bufferedReader().use {it.readText()}).getJSONArray("cases")
        assertEquals(22,fixtures.length())
        for(i in 0 until fixtures.length()) {
            val case=fixtures.getJSONObject(i)
            val valid=try {wire.validate(case.get("value"),case.getString("definition"));true} catch(_:IllegalArgumentException) {false}
            assertEquals(case.getString("name"),case.getBoolean("valid"),valid)
        }
    }
}
