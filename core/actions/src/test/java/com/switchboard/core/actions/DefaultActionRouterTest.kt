package com.switchboard.core.actions

import com.switchboard.providers.api.StructuredAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultActionRouterTest {
    @Test
    fun `routes supported action and reports unknown action`() = runTest {
        val router = DefaultActionRouter(listOf(MockActionHandler()))

        val results = router.route(
            listOf(
                StructuredAction("mock.echo", mapOf("value" to "hello")),
                StructuredAction("maps.navigate"),
            ),
        )

        assertEquals(ActionResult.Status.HANDLED, results[0].status)
        assertEquals(ActionResult.Status.UNSUPPORTED, results[1].status)
    }
}

