package dev.nexcraft.r2d1.spring;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem settings used when Spring Boot creates the optional filesystem DocumentStore.
 *
 * @param rootDirectory the directory containing canonical document files
 * @param executor the optional Spring bean name for blocking filesystem work
 */
@ConfigurationProperties("r2d1.filesystem")
public record R2D1FilesystemProperties(@Nullable Path rootDirectory, @Nullable String executor) {}
