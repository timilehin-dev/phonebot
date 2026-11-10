rootProject.name = "multibot-android"

include(":app")
include(":agent-core")
include(":termux-bridge")
include(":scheduling")
include(":credentials")
include(":wizard")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
