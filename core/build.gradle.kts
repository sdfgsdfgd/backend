
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
//    alias(libs.plugins.kotlinx.rpc.plugin)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
//            api(libs.kotlinx.rpc.krpc.serialization.json)
//            api(libs.kotlinx.rpc.core)
        }
    }
}
