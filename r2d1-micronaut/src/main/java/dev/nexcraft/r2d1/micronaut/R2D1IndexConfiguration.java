package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import org.jspecify.annotations.Nullable;

/**
 * Selects the automatically configured R2D1 index backend.
 *
 * @param type JDBC or D1 when no application IndexStore bean is present
 */
@ConfigurationProperties("r2d1.index")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1IndexConfiguration(@Nullable IndexBackend type) {}
