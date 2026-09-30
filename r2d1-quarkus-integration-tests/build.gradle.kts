plugins { id("io.quarkus") }
description = "Nonpublished Quarkus JVM and native smoke application"
dependencies {
    implementation(platform("io.quarkus:quarkus-bom:3.39.5"))
    implementation(project(":r2d1-quarkus"))
    // Consume adapter JARs as external libraries: reloadable workspace adapters cannot be linked from an extension classloader.
    implementation(files(rootProject.project(":r2d1-filesystem").tasks.named("jar")))
    implementation(files(rootProject.project(":r2d1-jdbc").tasks.named("jar")))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-jdbc-h2")
    implementation("io.quarkus:quarkus-agroal")
    // AWS checksum signatures require the optional CRT classes during native linking.
    implementation("software.amazon.awssdk.crt:aws-crt:0.48.4")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}
tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    maxHeapSize = "1g"
}

tasks.test { exclude("**/*IT.class") }
quarkus { sourceSets { setExtraNativeTest(sourceSets["integrationTest"]) } }
// Keep packaged JVM smoke in ordinary CI; native smoke remains an explicit opt-in task.
tasks.check { dependsOn("quarkusIntTest") }
