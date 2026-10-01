plugins {
    id("io.quarkus.extension")
    id("com.vanniktech.maven.publish")
}

description = "Quarkus runtime integration for R2D1"

quarkusExtension {
    deploymentModule = ":r2d1-quarkus-deployment"
}

dependencies {
    api(platform("io.quarkus:quarkus-bom:3.39.5"))
    api(project(":r2d1"))
    api("io.quarkus:quarkus-arc")
    api("org.jspecify:jspecify:1.0.0")
    compileOnly(project(":r2d1-jdbc"))
    compileOnly(project(":r2d1-filesystem"))
}

tasks.named("validateExtension") {
    dependsOn(project(":r2d1").tasks.named("jar"))
}
