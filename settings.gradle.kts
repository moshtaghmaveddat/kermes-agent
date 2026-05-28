rootProject.name = "kermes"

include(
    "kermes-core",
    "kermes-tools",
    "kermes-schedule",
    "kermes-tui",
    "kermes-app",
)

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/koog/maven")
    }
}
