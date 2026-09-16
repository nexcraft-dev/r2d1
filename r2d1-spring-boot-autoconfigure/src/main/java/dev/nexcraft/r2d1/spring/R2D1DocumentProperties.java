package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selects the automatically configured DocumentStore backend.
 *
 * @param type the selected document backend, or {@code null} when none is selected
 */
@ConfigurationProperties("r2d1.document")
public record R2D1DocumentProperties(@Nullable DocumentBackend type) {}
