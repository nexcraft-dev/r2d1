plugins {
    id("com.vanniktech.maven.publish")
}

description = "Cloudflare D1 integration"

dependencies {
    api(project(":r2d1-core"))
    api("org.jspecify:jspecify:1.0.0")
    implementation("io.avaje:avaje-jsonb:3.15")
    annotationProcessor("io.avaje:avaje-jsonb-generator:3.15")
}
