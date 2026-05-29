plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":kermes-core"))
    implementation(project(":kermes-tools"))
    implementation(project(":kermes-schedule"))
    implementation(project(":kermes-tui"))

    implementation(libs.kotlinx.coroutines.core)

    // Provider clients: Chat-Completions for OpenRouter/custom, native for Ollama.
    // (koog-agents bundles the OpenAI + Ollama clients; OpenRouter is separate.)
    implementation("ai.koog:prompt-executor-openrouter-client:1.0.0")

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("ai.kermes.app.MainKt")
    applicationName = "kermes"
}

// `gradle run` otherwise sets the JVM working dir to this module dir, so the
// bundled ./skills (and ./.kermes/skills) roots wouldn't resolve. Anchor it to
// the repo root for dev. Packaged installs should set KERMES_BUNDLED_SKILLS.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
