package net.sdfgsdfg


// region Disabled / Archive
//
// 1)
//@Suppress("unused")
//fun Application.modules_disabled() {
//    configureMonitoring()       // xx Metrics
//    configureSerialization()    // gson-ktor examples ?
//    configureRouting() // low priority, static page stuff
//    configureTemplating() // low priority, static page stuff
//}
//
//
// 2)
///**
// * Verifies X-Hub-Signature-256. Compare computed HMAC (sha256) of [body] with [signatureHeader].
// */
//private fun verifyGitHubSignature(signatureHeader: String?, secret: String, body: ByteArray): Boolean {
//    if (signatureHeader.isNullOrBlank()) return false
//    // Usually the header is in format: "sha256=..."
//    val expectedPrefix = "sha256="
//    if (!signatureHeader.startsWith(expectedPrefix)) return false
//
//    val signature = signatureHeader.removePrefix(expectedPrefix)
//
//    // Calculate HMAC-SHA256 on the body using 'secret'
//    val hmacSha256 = Mac.getInstance("HmacSHA256").apply {
//        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
//    }
//    val computed = hmacSha256.doFinal(body).toHexString()
//
//    return MessageDigest.isEqual(signature.toByteArray(), computed.toByteArray())
//}
//
///** Handy extension to convert ByteArray -> Hex String */
//private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
//
//
// 3) for github webhooks etc...  todo  GITHUB_SECRETS impl     (security yay)
//    post("/webhook/github") {
//        // 1) Read the raw body
//        val bodyBytes = call.receiveStream().readBytes()
//
//        // 2) Optional: verify signature if you have a webhook secret
//        val secret = System.getenv("GITHUB_WEBHOOK_SECRET") ?: ""
//        if (secret.isNotBlank()) {
//            val signature = call.request.headers["X-Hub-Signature-256"]
//            if (!verifyGitHubSignature(signature, secret, bodyBytes)) {
//                call.respond("Invalid signature", typeInfo = TypeInfo(String::class))
//                return@post
//            }
//        }
//
//        // 3) Optionally parse the JSON to see which branch was pushed, etc.
//        //    e.g. val payload = Json.decodeFromString<PushPayload>(bodyBytes.decodeToString())
//
//        // 4) Run your `deploy.main.kts` script in the background so it doesn't block Ktor
//        val output = withContext(Dispatchers.IO) {
//            // This example calls your script in the same directory or adjust path as needed:
//            runCommand("./0_scripts/deploy.main.kts deploy")
//        }
//
//        // 5) Respond with whatever you like
//        call.respondText("Deployment triggered.\n\n$output")
//    }
// endregion

// region Disabled / Archive - 2
//    allows you to get       Eazy peazy JSON ser
//    :
//routing {
//    get("/json/complex") {
//        call.respond(
//            mapOf(
//                "status" to "success",
//                "meta" to mapOf(
//                    "timestamp" to System.currentTimeMillis(),
//                    "requestId" to "req-${(1000..9999).random()}"
//                ),
//                "data" to listOf(
//                    mapOf(
//                        "id" to 1,
//                        "name" to "John Doe",
//                        "contacts" to listOf(
//                            mapOf("type" to "email", "value" to "john.doe@example.com"),
//                            mapOf("type" to "phone", "value" to "+1234567890")
//                        ),
//                        "preferences" to mapOf(
//                            "notifications" to true,
//                            "theme" to "dark"
//                        )
//                    ),
//                    mapOf(
//                        "id" to 2,
//                        "name" to "Jane Smith",
//                        "contacts" to listOf(
//                            mapOf("type" to "email", "value" to "jane.smith@example.com"),
//                            mapOf("type" to "phone", "value" to "+9876543210")
//                        ),
//                        "preferences" to mapOf(
//                            "notifications" to false,
//                            "theme" to "light"
//                        ),
//                        "history" to listOf(
//                            mapOf("action" to "login", "timestamp" to System.currentTimeMillis() - 3600000),
//                            mapOf("action" to "purchase", "timestamp" to System.currentTimeMillis() - 7200000)
//                        )
//                    )
//                )
//            )
//        )
//    }
//}
// endregion

// region Disabled / Archive - 3
// ! TODO !
// 1. READ cookie: val session: MySession? = call.sessions.get<MySession>()
// 2. DEL cookie call.sessions.clear<MySession>()
//    install(Sessions) {
//        cookie<MySession>("SESSION") {
//            cookie.path = "/" // Ensure cookie is accessible throughout the site
//            cookie.httpOnly = true // Prevent client-side access via JavaScript
//            cookie.secure = true // xx Enforces https
//            cookie.maxAgeInSeconds = 60 * 60 * 24 * 7 // 1 week
//            cookie.extensions["SameSite"] = "lax"
//        }
//    }
//    install(WebSockets) {
//        pingPeriod = 15.seconds
//        timeout = 15.seconds
//        maxFrameSize = Long.MAX_VALUE
//        masking = false
//    }


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
//
//@Serializable
//data class MySession(val count: Int = 0)
//
//class UserSession(accessToken: String)
//
//fun Application.configureRouting() {
//    install(StatusPages) {
//        exception<Throwable> { call, cause ->
//            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
//        }
//    }
//    install(SSE)
//    install(DoubleReceive)
//    routing {
//        get("/") {
//            call.respondText("Hello World!")
//        }
//        // Static plugin. Try to access `/static/index.html`
//        staticResources("/static", "static")
//        sse("/hello") {
//            send(ServerSentEvent("world"))
//        }
//        post("/double-receive") {
//            val first = call.receiveText()
//            val theSame = call.receiveText()
//            call.respondText(first + " " + theSame)
//        }
//    }
//}
// TODO: Ktor template gen left this here, find out wtf this is
//object TlsRawSocket {
//    @JvmStatic
//    fun main(args: Array<String>) {
//        runBlocking {
//            val selectorManager = ActorSelectorManager(Dispatchers.IO)
//            val socket = aSocket(selectorManager).tcp().connect("www.google.com", port = 443).tls(coroutineContext = coroutineContext)
//            val write = socket.openWriteChannel()
//            val EOL = "\r\n"
//            write.writeStringUtf8("GET / HTTP/1.1${EOL}Host: www.google.com${EOL}Connection: close${EOL}${EOL}")
//            write.flush()
//            println(socket.openReadChannel().readRemaining().readBytes().toString(Charsets.UTF_8))
//        }
//    }
//}

// TODO: Quest: Can't we reuse 1 symdef in kts and codebase ?
//fun String.shell(logFile: File): String = runCatching {
//    val process = ProcessBuilder("bash", "-c", this).apply {
//        redirectErrorStream(true)
//    }.start()
//
//    val reader = process.inputStream.bufferedReader()
//    val outputBuffer = StringBuilder()
//
//    reader.useLines { lines ->
//        lines.forEach { line ->
//            logFile.appendText("🔸 $line\n")  // Log to file
//        }
//    }
//
//    val exitCode = process.waitFor()
//    logFile.appendText("🔎 Command: $this | Exit code: $exitCode\n")
//
//    return outputBuffer.toString()
//}.getOrElse {
//    logFile.appendText("❌ ERROR running '$this': ${it.localizedMessage}\n")
//    return ""
//}
//
//// xx HSTS etc would only be required if Ktor was serving static content
//fun Application.configureHTTP() {
//    routing {
//        swaggerUI(path = "openapi")
//    }
//    install(PartialContent) {
//        // Maximum number of ranges that will be accepted from a HTTP request.
//        // If the HTTP request specifies more ranges, they will all be merged into a single range.
//        maxRangeCount = 10
//    }
//    routing {
//        openAPI(path = "openapi")
//    }
//
//    //
//    install(HttpsRedirect) {
//        // The port to redirect to. By default 443, the default HTTPS port.
//        sslPort = 443
//        // 301 Moved Permanently, or 302 Found redirect.
//        permanentRedirect = true
//    }
//
//    // Force HTTPS in Browsers
//    install(HSTS) {
//        maxAgeInSeconds = 60 * 60 * 24 * 365 // 1 year
//        includeSubDomains = true
//        // todo: Submit domains to  https://hstspreload.org  for inclusion in browser HSTS lists
//        preload = true
//    }
//    install(DefaultHeaders) {
//        header("X-Engine", "Ktor") // will send this header with each response
//        header("X-Engine", "Arcana")
//    }
//
//    // TODO: Probably only good for static site content - comment out / move to an Archive section
//    install(CachingHeaders) {
//        options { _, outgoingContent ->
//            when (outgoingContent.contentType?.withoutParameters()) {
//                ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
//                else -> null
//            }
//        }
//    }
//// xx  ================================================================================================
////      Only to be enabled for  Next subdomains' reverse proxies ==
////     install(ForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
////     install(XForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
//// xx  ================================================================================================
//}


// endregion