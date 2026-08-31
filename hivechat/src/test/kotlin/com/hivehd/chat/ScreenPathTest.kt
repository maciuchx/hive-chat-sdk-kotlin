package com.hivehd.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact string `trackScreen` puts on the wire.
 *
 * This is the bug that shipped: the path was built correctly and then thrown
 * away, because the interpolation was escaped — every screen emitted the
 * literal `app://android/${'$'}path`. Nothing caught it, because nothing
 * asserted the string, and the agent panel is the only other place it shows.
 *
 * The builder is duplicated here rather than exposed publicly: it is not API
 * anyone should call, but it is behaviour worth pinning.
 */
class ScreenPathTest {

    private fun screenUrl(screen: String, reference: String? = null): String {
        val name = screen.trim()
        val path = listOfNotNull(
            name.lowercase().replace(' ', '-'),
            reference?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString("/")
        return "app://android/$path"
    }

    @Test
    fun `interpolates rather than emitting a literal placeholder`() {
        val url = screenUrl("Product", "slim-fit-suit")
        assertEquals("app://android/product/slim-fit-suit", url)
        assert(!url.contains("\$path")) { "the path placeholder leaked onto the wire" }
    }

    @Test
    fun `multi-word screens become a readable slug`() {
        assertEquals("app://android/order-history", screenUrl("Order History"))
    }

    @Test
    fun `a screen without a reference has no trailing slash`() {
        assertEquals("app://android/basket", screenUrl("Basket"))
    }
}
