plugins {
    id("com.vanniktech.maven.publish")
}

description = "Filesystem DocumentStore adapter for R2D1"

dependencies {
    api(project(":r2d1"))
}
