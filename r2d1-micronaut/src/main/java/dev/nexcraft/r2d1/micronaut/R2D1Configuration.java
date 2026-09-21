package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * Top-level switch for the optional R2D1 Micronaut integration.
 *
 * @param enabled whether Micronaut should create R2D1 integration beans
 */
@ConfigurationProperties("r2d1")
public record R2D1Configuration(@Bindable(defaultValue = "false") boolean enabled) {}
