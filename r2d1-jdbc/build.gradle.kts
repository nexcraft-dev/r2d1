description = "JDBC IndexStore with built-in H2 and HSQLDB support"

dependencies {
    api(project(":r2d1-core"))
    api("org.jspecify:jspecify:1.0.0")

    testImplementation(testFixtures(project(":r2d1-core")))
    testRuntimeOnly("com.h2database:h2:2.5.250")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.4")
}
