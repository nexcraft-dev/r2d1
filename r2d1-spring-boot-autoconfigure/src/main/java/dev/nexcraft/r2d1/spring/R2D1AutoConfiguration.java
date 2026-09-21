package dev.nexcraft.r2d1.spring;

import dev.nexcraft.r2d1.spring.internal.D1Configuration;
import dev.nexcraft.r2d1.spring.internal.FilesystemConfiguration;
import dev.nexcraft.r2d1.spring.internal.JdbcConfiguration;
import dev.nexcraft.r2d1.spring.internal.R2Configuration;
import dev.nexcraft.r2d1.spring.internal.R2D1CoreConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Auto-configures R2D1 from application-owned Spring beans and explicit backend properties.
 *
 * <p>The integration is opt-in through {@code r2d1.enabled=true}. It does not create a Spring
 * DataSource, connection pool, executor, database server, or HTTP client for an application bean.
 */
@AutoConfiguration
@ConditionalOnClass(dev.nexcraft.r2d1.R2D1.class)
@ConditionalOnProperty(prefix = "r2d1", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
  R2D1Properties.class,
  R2D1DocumentProperties.class,
  R2D1FilesystemProperties.class,
  R2D1R2Properties.class,
  R2D1IndexProperties.class,
  R2D1D1Properties.class,
  R2D1JdbcProperties.class
})
@Import({
  R2D1CoreConfiguration.class,
  R2Configuration.class,
  D1Configuration.class,
  FilesystemConfiguration.class,
  JdbcConfiguration.class
})
public class R2D1AutoConfiguration {

  /** Creates the marker auto-configuration class. */
  public R2D1AutoConfiguration() {}
}
