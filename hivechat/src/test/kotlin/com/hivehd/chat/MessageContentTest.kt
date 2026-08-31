package com.hivehd.chat

import com.hivehd.chat.models.Attachment
import com.hivehd.chat.models.ChatForm
import com.hivehd.chat.models.ChatMessage
import com.hivehd.chat.models.MessageContent
import com.hivehd.chat.models.resolveRelativeUrls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The sentinel format is a contract with a server this SDK cannot change and
 * a shipped app cannot re-release quickly, so every shape below is copied
 * from what the live server actually emits.
 */
class MessageContentTest {

    @Test
    fun `plain text parses as text`() {
        assertEquals(MessageContent.Text("Where is my order?"), MessageContent.parse("Where is my order?"))
    }

    @Test
    fun `product card parses`() {
        val body = """__PRODUCT_CARD__{"title":"Slim Fit Suit","image_url":"https://cdn.example/s.jpg","buy_url":"https://shop.example/s","price":129.99,"agent_name":"Buzz"}"""
        val content = MessageContent.parse(body)
        assertTrue(content is MessageContent.Product)
        val card = (content as MessageContent.Product).card
        assertEquals("Slim Fit Suit", card.title)
        assertEquals(BigDecimal("129.99"), card.price)
        assertEquals("Buzz", card.agentName)
    }

    /** The agent picker sends string prices; the bot sends numbers. */
    @Test
    fun `product card accepts string prices and falls back to cheapest variant`() {
        val body = """__PRODUCT_CARD__{"title":"Tie","variants":[{"title":"Navy","price":"19.50"},{"title":"Red","price":"25.00"}]}"""
        val card = (MessageContent.parse(body) as MessageContent.Product).card
        assertEquals(BigDecimal("19.50"), card.price)
        assertEquals(2, card.variants.size)
    }

    @Test
    fun `chat form parses with the END terminator`() {
        val body = """__CHAT_FORM__{"form_id":"f_1","name":"Return request","fields":[{"key":"order","label":"Order number","type":"text","required":true},{"key":"why","label":"Why?","type":"textarea"}]}__END__"""
        val form = (MessageContent.parse(body) as MessageContent.Form).form
        assertEquals("Return request", form.name)
        assertEquals(2, form.fields.size)
        assertTrue(form.fields[0].required)
        assertEquals(ChatForm.FieldType.TEXTAREA, form.fields[1].type)
    }

    /** A field type invented after this SDK shipped must degrade, not break. */
    @Test
    fun `unknown form field type falls back to text`() {
        val body = """__CHAT_FORM__{"name":"F","fields":[{"key":"k","label":"L","type":"colour-picker"}]}__END__"""
        val form = (MessageContent.parse(body) as MessageContent.Form).form
        assertEquals(ChatForm.FieldType.TEXT, form.fields[0].type)
    }

    @Test
    fun `visitor file parses as an image attachment`() {
        val body = """__VISITOR_FILE__{"url":"https://hivehd.app/uploads/visitor/a.jpg","contentType":"image/jpeg","name":"a.jpg"}"""
        val attachment = (MessageContent.parse(body) as MessageContent.File).attachment
        assertEquals(Attachment.Kind.IMAGE, attachment.kind)
        assertEquals("a.jpg", attachment.name)
    }

    /** Agent files arrive host-relative — the shape the web widget can't render. */
    @Test
    fun `agent attachment resolves a relative url against the host`() {
        val body = """__META_ATTACHMENT__{"type":"image","url":"/uploads/livechat/x.png","name":"x.png"}__END__"""
        val content = MessageContent.parse(body)!!.resolveRelativeUrls("https://hivehd.app")
        val attachment = (content as MessageContent.File).attachment
        assertEquals("https://hivehd.app/uploads/livechat/x.png", attachment.url)
    }

    @Test
    fun `suppressed bookkeeping never reaches the customer`() {
        assertNull(MessageContent.parse("OFFLINE_EMAIL_SENT::sent to a@b.com"))
    }

    @Test
    fun `stripped prefix keeps the body`() {
        assertEquals(
            MessageContent.Text("We'll email you back."),
            MessageContent.parse("OFFLINE_HANDOFF::We'll email you back."),
        )
    }

    @Test
    fun `unknown sentinel is not rendered as prose`() {
        assertTrue(MessageContent.parse("""__ORDER_TIMELINE__{"id":1}""") is MessageContent.Unsupported)
    }

    @Test
    fun `underscores typed by a customer stay text`() {
        assertTrue(MessageContent.parse("__hello__ there") is MessageContent.Text)
    }

    @Test
    fun `malformed sentinel json degrades to text`() {
        assertTrue(MessageContent.parse("__PRODUCT_CARD__{not json") is MessageContent.Text)
    }

    /** The bot must be indistinguishable from an agent in the thread. */
    @Test
    fun `bot sender is presented as agent`() {
        val wire = JSONObject("""{"id":"m1","sender_type":"bot","body":"Hi","created_at":"2026-08-31T10:00:00.000Z"}""")
        assertEquals(ChatMessage.Sender.AGENT, ChatMessage.from(wire, "https://hivehd.app")!!.sender)
    }

    /**
     * The socket path sends fractional seconds; the restore path does not.
     * Handling one silently stamps half the thread 1970 and sorts it inside
     * out.
     */
    @Test
    fun `both timestamp shapes parse`() {
        assertEquals(1788170400123L, ChatMessage.parseDate("2026-08-31T10:00:00.123Z").time)
        assertEquals(1788170400000L, ChatMessage.parseDate("2026-08-31T10:00:00Z").time)
    }
}
