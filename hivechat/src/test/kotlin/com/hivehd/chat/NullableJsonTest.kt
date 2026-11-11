package com.hivehd.chat

import com.hivehd.chat.models.CartItem
import com.hivehd.chat.models.MessageContent.Companion.optStringOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server sends `sessionId: null` on every connect. Android's org.json
 * turns that into the four-character string "null" through `optString`; the
 * JVM org.json on this test classpath returns "" instead, so a test of
 * `optString` here would pass against the very bug it is meant to catch.
 * What is pinned instead is the helper HiveChat now uses everywhere: it must
 * answer null for JSONObject.NULL by checking `isNull` first, which behaves
 * the same on both implementations.
 */
class NullableJsonTest {

    @Test
    fun `explicit JSON null session id reads as null`() {
        val payload = JSONObject("""{"sessionId": null, "welcome_message": "Hi"}""")
        assertEquals(JSONObject.NULL, payload.opt("sessionId"))
        assertNull(payload.optStringOrNull("sessionId"))
    }

    @Test
    fun `JSONObject NULL put by hand reads as null`() {
        assertNull(JSONObject().put("sessionId", JSONObject.NULL).optStringOrNull("sessionId"))
    }

    @Test
    fun `missing and empty read as null, real ids survive`() {
        assertNull(JSONObject().optStringOrNull("sessionId"))
        assertNull(JSONObject().put("sessionId", "").optStringOrNull("sessionId"))
        assertEquals("csess_1", JSONObject().put("sessionId", "csess_1").optStringOrNull("sessionId"))
    }

    @Test
    fun `cart items carry both qty and quantity`() {
        val cart = HiveChat.cartPayload(
            listOf(CartItem(title = "Slim Fit Suit", quantity = 2, price = "129.99", variant = "40R")),
            total = "259.98",
            currency = "GBP",
        )
        val item = cart.getJSONArray("items").getJSONObject(0)
        assertEquals(2, item.getInt("qty"))
        assertEquals(2, item.getInt("quantity"))
        assertEquals("40R", item.getString("variant"))
        assertEquals(2, cart.getInt("count"))
    }
}
