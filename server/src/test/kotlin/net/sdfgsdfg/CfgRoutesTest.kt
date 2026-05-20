package net.sdfgsdfg

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CfgRoutesTest {
    @Test
    fun exampleRouteReturnsStableJson() = testApplication {
        application { cfg() }

        val response = client.get("/example")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("\"status\":\"success\"" in response.bodyAsText())
    }
}
