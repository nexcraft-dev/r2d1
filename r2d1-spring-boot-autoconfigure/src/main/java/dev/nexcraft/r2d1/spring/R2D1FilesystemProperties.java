package dev.nexcraft.r2d1.spring;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Filesystem settings used when Spring Boot creates the optional filesystem DocumentStore.
 *
 * @param rootDirectory the directory containing canonical document files
 * @param executor the optional Spring bean name for blocking filesystem work
 * @param backpressure optional active and pending filesystem operation limits
 */
@ConfigurationProperties("r2d1.filesystem")
public record R2D1FilesystemProperties(
    @Nullable Path rootDirectory,
    @Nullable String executor,
    @Nullable R2D1BackpressureProperties backpressure) {

  /** Creates Filesystem adapter properties. */
  @ConstructorBinding
  public R2D1FilesystemProperties(
      @Nullable Path rootDirectory,
      @Nullable String executor,
      @Nullable R2D1BackpressureProperties backpressure) {
    this.rootDirectory = rootDirectory;
    this.executor = executor;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before backpressure properties were added.
   *
   * @param rootDirectory the directory containing canonical document files
   * @param executor the optional Spring bean name for blocking filesystem work
   */
  public R2D1FilesystemProperties(@Nullable Path rootDirectory, @Nullable String executor) {
    this(rootDirectory, executor, null);
  }
}
