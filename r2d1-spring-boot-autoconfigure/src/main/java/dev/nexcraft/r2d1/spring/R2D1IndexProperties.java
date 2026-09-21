package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selects the automatically configured IndexStore backend.
 *
 * @param type the selected index backend, or {@code null} when none is selected
 */
@ConfigurationProperties("r2d1.index")
public record R2D1IndexProperties(@Nullable IndexBackend type) {}
