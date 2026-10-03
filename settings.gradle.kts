rootProject.name = "wise-lite"

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
    }
}

include("libs:events")
include("libs:outbox")
include("services:transfer-service")
include("services:payout-worker")
include("services:fx-service")
include("libs:rails-api")
include("services:rails-simulator")
include("services:reconciliation-job")
include("tests:system")
