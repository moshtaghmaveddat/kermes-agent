plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":kermes-core"))
    api(libs.mordant)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
}
