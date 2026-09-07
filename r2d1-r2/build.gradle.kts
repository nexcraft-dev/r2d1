description = "Cloudflare R2 integration"

dependencies {
    api(project(":r2d1-core"))
    api("org.jspecify:jspecify:1.0.0")
    implementation(platform("software.amazon.awssdk:bom:2.54.13"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "crt-core")
    }
    implementation("software.amazon.awssdk:netty-nio-client")
}
