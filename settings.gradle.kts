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

include("r2d1-core", "r2d1-d1", "r2d1-r2", "r2d1-jdbc", "r2d1-integration-tests")
