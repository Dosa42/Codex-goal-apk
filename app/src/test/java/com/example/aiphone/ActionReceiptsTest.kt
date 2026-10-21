package com.example.aiphone

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ActionReceiptsTest {
    @Test fun duplicateReturnsDurableExactResponseAndConflictingIdFails() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        c.deleteDatabase("ai-phone-actions.sqlite")
        val request=JSONObject("""{"type":"request","requestId":"one","method":"act","params":{"action":"click"}}""")
        val response=JSONObject("""{"type":"response","requestId":"one","ok":true,"result":{"accepted":true,"observedOutcome":"unverified"}}""")
        ActionReceipts(c).use { journal ->
            assertNull(journal.lookup("one",request));journal.begin("one",request)
            try { journal.lookup("one",request);fail("uncertain dispatch replayed") } catch(e:ActionReceipts.Conflict) {assertEquals("uncertain_action",e.code)}
            journal.complete("one",request,response)
        }
        ActionReceipts(c).use { journal ->
            assertEquals(response.toString(),journal.lookup("one",request).toString())
            val changed=JSONObject(request.toString()).put("method","launch")
            try {journal.lookup("one",changed);fail("conflicting content accepted")} catch(e:ActionReceipts.Conflict){assertEquals("request_conflict",e.code)}
            val reordered=JSONObject("""{"params":{"action":"click"},"requestId":"one","method":"act","type":"request"}""")
            assertEquals(response.toString(),journal.lookup("one",reordered).toString())
        }
    }
}
