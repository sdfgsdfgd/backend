import com.google.protobuf.gradle.id
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.protobuf)
    jacoco
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
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.server.metrics)
    implementation(libs.khealth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.partial.content)
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
    implementation(libs.ktor.client.apache5)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.atomicfu) // atomic operations
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)

    // [ gRPC ]
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub) // suspending stubs
    implementation(libs.grpc.netty.shaded)   // HTTP/2 transport + UDS (Unix Domain Sockets - for the FASTEST IPC possible on earth, better than UDP&TPC, no network layer )
    implementation(libs.protobuf.kotlin)    // proto runtime

    runtimeOnly("io.netty:netty-transport-native-epoll:4.1.109.Final:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-kqueue:4.1.109.Final:osx-aarch_64")

    //
    // [ Tests ]
    //
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)

    //
    //
    //
    //
    // [ Archived ]
    //
    //    implementation(libs.kotlin.css)
    //
    //
    //
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.version.get()}" }

    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.java.version.get()}" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpc.kotlin.version.get()}:jdk8@jar" }
    }

    generateProtoTasks {
        all().forEach {
            it.builtins { id("kotlin") }
            it.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
    }
}

// A running JVM must never lazily load half of a classpath rebuilt by another Gradle task.
tasks.named<JavaExec>("run") {
    doFirst {
        val root = rootProject.projectDir.toPath().normalize()
        val snapshot = layout.buildDirectory.dir("run-snapshots/${System.nanoTime()}").get().asFile.apply { mkdirs() }
        classpath = files(classpath.files.mapIndexed { index, source ->
            if (!source.exists() || !source.toPath().normalize().startsWith(root)) source
            else snapshot.resolve("$index-${source.name}").also { target ->
                if (source.isDirectory) source.copyRecursively(target) else source.copyTo(target)
            }
        })
    }
}
