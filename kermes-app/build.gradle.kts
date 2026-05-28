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
