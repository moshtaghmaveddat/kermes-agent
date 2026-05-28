plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":kermes-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kaml)
    api(libs.cron.utils)

    testImplementation(libs.junit.jupiter)
}
