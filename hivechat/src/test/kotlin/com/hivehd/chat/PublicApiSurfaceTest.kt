package com.hivehd.chat

import com.hivehd.chat.models.CartItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Compile-time proof that the public API the README documents actually exists,
 * and that the strings it builds are real.
 *
 * The equivalent Swift methods were documented, released and absent from the
 * source for a whole version — an edit silently failed to apply, nothing in
 * the package called them, and the build stayed green to a tag. Calling them
 * here is the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicApiSurfaceTest {

    /* HiveChat binds its scope to Dispatchers.Main, because everything it
       publishes is read by a UI. A JVM unit test has no main looper, so one is
       substituted here — the same thing any consumer testing a ViewModel that
       holds a HiveChat will need to do. */
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun chat() = HiveChat(
        HiveChatConfiguration(widgetKey = "hv_test", tokenStore = VisitorTokenStore.ephemeral())
    )

    @Test
    fun `context methods exist and are safe without a connection`() {
        /* Every one is a no-op until a socket exists, which is the point — a
           host app calls them on navigation, long before anyone opens a chat. */
        val chat = chat()
        chat.trackScreen("Product", title = "Slim Fit Suit", reference = "slim-fit-suit")
        chat.trackScreen("Basket")
        chat.updateCart(
            items = listOf(CartItem(title = "Slim Fit Suit", quantity = 1, price = "129.99", variant = "40R")),
            total = "129.99",
            currency = "GBP",
        )
        chat.close()
    }

    @Test
    fun `conversation methods exist`() {
        val chat = chat()
        chat.identify(name = "Alex", email = "alex@example.com")
        chat.send("hello")
        chat.setTyping(true)
        chat.markRead()
        chat.requestHuman()
        chat.toggleReaction("👍", "cmsg_1")
        chat.submitCsat(5)
        chat.provideEmail("alex@example.com")
        chat.endChat()
        chat.recordConsent("I agree")
        chat.redeemHandoffCode("ABC123")
        chat.close()
    }
}
