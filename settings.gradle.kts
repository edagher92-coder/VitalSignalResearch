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

rootProject.name = "VitalSignalResearch"

include(":core:model")
include(":core:analytics")
include(":core:transport")
include(":core:storage")
include(":core:audit")
include(":core:reasoning")
include(":core:governance")
include(":core:monitoring")
include(":phone")
include(":wear")
