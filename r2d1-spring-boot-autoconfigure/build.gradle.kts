plugins {
    id("com.vanniktech.maven.publish")
}

description = "Spring Boot 4 auto-configuration for R2D1"

dependencies {
    api(project(":r2d1"))
    api("org.jspecify:jspecify:1.0.0")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:4.0.6")

    compileOnly(project(":r2d1-filesystem"))
    compileOnly(project(":r2d1-jdbc"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.6")

    testImplementation(project(":r2d1-filesystem"))
    testImplementation(project(":r2d1-jdbc"))
    testImplementation("com.h2database:h2:2.5.250")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:4.0.6")
    testImplementation("org.springframework.boot:spring-boot-test:4.0.6")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure:4.0.6")
}
