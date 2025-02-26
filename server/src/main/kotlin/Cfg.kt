package net.sdfgsdfg

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.Serializable
import kotlin.collections.set
import kotlin.time.Duration.Companion.seconds

// *** *** TODO:  Add  2 sequence within 15 secs    `port-knocking`    authentication
fun Application.cfg() {
    // ! TODO !
    // 1. READ cookie: val session: MySession? = call.sessions.get<MySession>()
    // 2. DEL cookie call.sessions.clear<MySession>()
    install(Sessions) {
        cookie<MySession>("SESSION") {
            cookie.path = "/" // Ensure cookie is accessible throughout the site
            cookie.httpOnly = true // Prevent client-side access via JavaScript
            cookie.secure = true // Use HTTPS
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 7 // 1 week
            cookie.extensions["SameSite"] = "lax"
        }
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

//
// DI  ( Dependency Injection )
//    install(Koin) {
//        slf4jLogger()
//        modules(module {
//            single<HelloService> {
//                HelloService {
//                    println(environment.log.info("Hello, World!"))
//                }
//            }
//        })
//    }
// xx RPC related
//    install(Krpc)
//    routing {
//    //  TODO: RPC
//        rpc("/api") {
//            rpcConfig {}
//            TODO: RPC  ( sample entry below )
//            registerService<SampleService> { ctx -> SampleServiceImpl(ctx) }
//        }
// == == == == == == == == == == == == == == == == == == == == == == == ==
//    }
// xx RPC related
//
//    authentication {
//        oauth("auth-oauth-google") {
//            urlProvider = { "http://localhost:8080/callback" }
//            providerLookup = {
//                OAuthServerSettings.OAuth2ServerSettings(
//                    name = "google",
//                    authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
//                    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
//                    requestMethod = HttpMethod.Post,
//                    clientId = System.getenv("GOOGLE_CLIENT_ID"),
//                    clientSecret = System.getenv("GOOGLE_CLIENT_SECRET"),
//                    defaultScopes = listOf("https://www.googleapis.com/auth/userinfo.profile")
//                )
//            }
//            client = HttpClient(Apache)
//        }
//    }
//    // pls read the jwt property from the config file if you are using EngineMain
//    val jwtAudience = "jwt-audience"
//    val jwtDomain = "https://jwt-provider-domain/"
//    val jwtRealm = "ktor sample app"
//    val jwtSecret = "secret"
//    authentication {
//        jwt {
//            realm = jwtRealm
//            verifier(
//                JWT
//                    .require(Algorithm.HMAC256(jwtSecret))
//                    .withAudience(jwtAudience)
//                    .withIssuer(jwtDomain)
//                    .build()
//            )
//            validate { credential ->
//                if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null
//            }
//        }
//    }
//    authentication {
//        basic(name = "myauth1") {
//            realm = "Ktor Server"
//            validate { credentials ->
//                if (credentials.name == credentials.password) {
//                    UserIdPrincipal(credentials.name)
//                } else {
//                    null
//                }
//            }
//        }
//
//        form(name = "myauth2") {
//            userParamName = "user"
//            passwordParamName = "password"
//            challenge {
//                /**/
//            }
//        }
//    }
//    routing {
//        get("/session/increment") {
//            val session = call.sessions.get<MySession>() ?: MySession()
//            call.sessions.set(session.copy(count = session.count + 1))
//            call.respondText("Counter is ${session.count}. Refresh to increment.")
//        }
//
//        authenticate("auth-oauth-google") {
//            get("login") {
//                call.respondRedirect("/callback")
//            }
//
//            get("/callback") {
//                val principal: OAuthAccessTokenResponse.OAuth2? = call.authentication.principal()
//                call.sessions.set(UserSession(principal?.accessToken.toString()))
//                call.respondRedirect("/hello")
//            }
//        }
//
//        authenticate("myauth1") {
//            get("/protected/route/basic") {
//                val principal = call.principal<UserIdPrincipal>()!!
//                call.respondText("Hello ${principal.name}")
//            }
//        }
//
//        authenticate("myauth2") {
//            get("/protected/route/form") {
//                val principal = call.principal<UserIdPrincipal>()!!
//                call.respondText("Hello ${principal.name}")
//            }
//        }
//
//        route("/callback") {
//            install(DoubleReceive)
////            install(LineWebhook) {
////                channelSecret = System.getenv("CHANNEL_SECRET")
////            }
//            post {
//                call.respond(HttpStatusCode.OK)
//            }
//        }
//    }
//}

@Serializable
data class MySession(val count: Int = 0)

class UserSession(accessToken: String)

