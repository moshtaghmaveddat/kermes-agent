plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Koog core + memory features
    api(libs.koog.agents)
    api(libs.koog.features.memory)

    // Async + serialization (used by SKILL.md frontmatter, schedules, config)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kaml)

    // Logging API (impl lives in kermes-app)
    api(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
