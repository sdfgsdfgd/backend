
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
//    alias(libs.plugins.kotlinx.rpc.plugin)
}

application {
    mainClass = "net.sdfgsdfg.AppKt"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.network.tls)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)
    implementation(libs.kotlin.css)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.server.metrics)
    implementation(libs.khealth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.http.redirect)
    implementation(libs.ktor.server.hsts)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.caching.headers)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.line.webhook.plugin)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.logback.classic)

    // added by Kaanself
    implementation(libs.coroutines)


    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
