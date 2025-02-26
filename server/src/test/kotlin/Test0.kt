package net.sdfgsdfg

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class Test0 {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

//    @Test
//    fun testRpc() = testApplication {
//        application {
//            configureFrameworks()
//        }
//        val ktorClient = createClient {
//            install(WebSockets)
//            install(Krpc)
//        }
//        val rpcClient = ktorClient.rpc("/api") {
//            rpcConfig {
//                serialization {
//                    json()
//                }
//            }
//        }
//        val service = rpcClient.withService<SampleService>()
//        val response = service.hello(Data("client"))
//        assertEquals("Server: client", response)
//    }
//

}
