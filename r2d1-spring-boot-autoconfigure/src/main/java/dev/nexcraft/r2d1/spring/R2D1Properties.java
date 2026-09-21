package dev.nexcraft.r2d1.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level switch for the optional R2D1 Spring Boot integration.
 *
 * @param enabled whether the integration should create R2D1 beans
 */
@ConfigurationProperties("r2d1")
public record R2D1Properties(@DefaultValue("false") boolean enabled) {}
