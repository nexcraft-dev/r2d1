package dev.nexcraft.r2d1.quarkus;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Build-time activation of the optional R2D1 CDI integration. */
@ConfigMapping(prefix = "quarkus.r2d1")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface R2D1BuildTimeConfig {
  /** Enables default R2D1 bean registration; changing this setting requires rebuilding. */
  @WithDefault("false")
  boolean enabled();
}
