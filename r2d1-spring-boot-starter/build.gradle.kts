plugins {
    id("com.vanniktech.maven.publish")
}

description = "Spring Boot starter for R2D1"

dependencies {
    api(project(":r2d1-spring-boot-autoconfigure"))
    api("org.springframework.boot:spring-boot-starter:4.0.6")
}
