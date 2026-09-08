import java.time.Duration
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test

description = "Opt-in integration tests against dedicated Cloudflare R2 and D1 resources"

val integrationTest = sourceSets.create("integrationTest")

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)

dependencies {
    add(integrationTest.implementationConfigurationName, project(":r2d1-core"))
    add(integrationTest.implementationConfigurationName, project(":r2d1-d1"))
    add(integrationTest.implementationConfigurationName, project(":r2d1-r2"))
}

val requiredEnvironmentVariables = listOf(
    "R2D1_IT_R2_ENDPOINT",
    "R2D1_IT_R2_ACCESS_KEY_ID",
    "R2D1_IT_R2_SECRET_ACCESS_KEY",
    "R2D1_IT_R2_BUCKET_NAME",
    "R2D1_IT_D1_ACCOUNT_ID",
    "R2D1_IT_D1_DATABASE_ID",
    "R2D1_IT_D1_API_TOKEN",
    "R2D1_IT_CONFIRM_DEDICATED_RESOURCES",
)

tasks.register<Test>("integrationTest") {
    description = "Runs tests against dedicated Cloudflare R2 and D1 resources."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    maxParallelForks = 1
    timeout.set(Duration.ofMinutes(15))
    doNotTrackState("Integration tests depend on live Cloudflare resources")

    doFirst {
        val missing = requiredEnvironmentVariables.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing required integration test environment variables: ${missing.joinToString(", ")}"
            )
        }
        if (System.getenv("R2D1_IT_CONFIRM_DEDICATED_RESOURCES") != "true") {
            throw GradleException(
                "R2D1_IT_CONFIRM_DEDICATED_RESOURCES must be exactly true"
            )
        }
    }
}
