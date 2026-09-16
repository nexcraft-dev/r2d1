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

rootProject.name = "r2d1"

include(
    "r2d1",
    "r2d1-filesystem",
    "r2d1-jdbc",
    "r2d1-micronaut",
    "r2d1-spring-boot-autoconfigure",
    "r2d1-spring-boot-starter",
    "r2d1-integration-tests",
)
