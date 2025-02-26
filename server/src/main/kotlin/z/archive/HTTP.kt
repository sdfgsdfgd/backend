package net.sdfgsdfg.z.archive

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
