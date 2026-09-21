# R2D1 Spring Boot starter

`r2d1-spring-boot-starter` is the convenience dependency for the upcoming R2D1 Spring Boot
4.0.6 integration. It brings in `r2d1-spring-boot-autoconfigure` and the standard Spring Boot
starter, while leaving the document and index adapters selected by the application.

The starter targets the `1.7.0` release and is not available from Maven Central until the explicit
`v1.7.0` release completes.

## Dependency

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-spring-boot-starter:1.7.0")
    implementation("dev.nexcraft:r2d1-filesystem:1.7.0") // only for filesystem documents
    implementation("dev.nexcraft:r2d1-jdbc:1.7.0") // only for JDBC indexes
}
```

The starter does not include the filesystem or JDBC adapter. Add the adapter that matches the
configured backend; JDBC drivers remain application-provided. For custom starter composition, use
`dev.nexcraft:r2d1-spring-boot-autoconfigure` directly.

See the [auto-configuration guide](../r2d1-spring-boot-autoconfigure/README.md) for configuration,
backend selection, bean precedence, resource ownership, and the supported scope.
