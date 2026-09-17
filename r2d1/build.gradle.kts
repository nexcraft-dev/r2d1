plugins {
    `java-test-fixtures`
    id("com.vanniktech.maven.publish")
}

description = "Core API with Cloudflare R2 document storage and D1 indexing"

dependencies {
    api("org.jspecify:jspecify:1.0.0")

    api(platform("software.amazon.awssdk:bom:2.54.13"))
    api("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "crt-core")
    }
    implementation("software.amazon.awssdk:netty-nio-client")

    implementation("io.avaje:avaje-jsonb:3.15")
    implementation("dev.failsafe:failsafe:3.3.2")
    annotationProcessor("io.avaje:avaje-jsonb-generator:3.15")

    testFixturesImplementation(platform("org.junit:junit-bom:5.14.4"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testFixturesImplementation("org.assertj:assertj-core:3.27.7")
}
