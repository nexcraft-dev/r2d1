package dev.nexcraft.r2d1.quarkus;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/** Runtime settings; credentials and resource locations are never consumed during augmentation. */
@ConfigMapping(prefix = "quarkus.r2d1")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface R2D1RuntimeConfig {
  /** Selects the document backend when no application DocumentStore exists. */
  DocumentConfig document();

  /** Selects the index backend when no application IndexStore exists. */
  IndexConfig index();

  /** Configures the Cloudflare R2 document adapter. */
  R2Config r2();

  /** Configures the Cloudflare D1 index adapter. */
  D1Config d1();

  /** Configures the optional filesystem document adapter. */
  FilesystemConfig filesystem();

  /** Configures the optional JDBC index adapter. */
  JdbcConfig jdbc();

  /** Supported authoritative document backends. */
  enum DocumentType {
    /** Cloudflare R2. */
    R2,
    /** Caller-owned filesystem directory. */
    FILESYSTEM
  }

  /** Supported derived index backends. */
  enum IndexType {
    /** Cloudflare D1. */
    D1,
    /** Caller-owned JDBC DataSource. */
    JDBC
  }

  /** Explicit managed JDBC thread model. */
  enum ExecutionMode {
    /** Bounded platform-thread execution. */
    PLATFORM_THREAD,
    /** Bounded virtual-thread execution. */
    VIRTUAL_THREAD
  }

  /** Document backend selection. */
  @ConfigGroup
  interface DocumentConfig {
    /** Document backend; unnecessary with an application DocumentStore. */
    Optional<DocumentType> type();
  }

  /** Index backend selection. */
  @ConfigGroup
  interface IndexConfig {
    /** Index backend; unnecessary with an application IndexStore. */
    Optional<IndexType> type();
  }

  /** R2 client settings. */
  @ConfigGroup
  interface R2Config {
    /** R2 S3 endpoint, required when creating an S3 client. */
    Optional<URI> endpoint();

    /** Access key ID, required when creating an S3 client. */
    Optional<String> accessKeyId();

    /** Secret access key, required when creating an S3 client. */
    Optional<String> secretAccessKey();

    /** Bucket name, required for an integration-created R2 store. */
    Optional<String> bucketName();

    /** S3 signing region. */
    @WithDefault("auto")
    String region();
  }

  /** D1 client settings. */
  @ConfigGroup
  interface D1Config {
    /** Cloudflare account ID, required for an integration-created D1 store. */
    Optional<String> accountId();

    /** D1 database ID, required for an integration-created D1 store. */
    Optional<String> databaseId();

    /** Cloudflare API token, required for an integration-created D1 store. */
    Optional<String> apiToken();
  }

  /** Filesystem settings; the application owns its executor. */
  @ConfigGroup
  interface FilesystemConfig {
    /** Root directory for canonical document files. */
    Optional<Path> rootDirectory();

    /** Exact CDI name of the application executor; otherwise select the default executor. */
    Optional<String> executor();
  }

  /** JDBC settings; the application owns its DataSource. */
  @ConfigGroup
  interface JdbcConfig {
    /** Exact Quarkus datasource or CDI bean name; otherwise select the default datasource. */
    Optional<String> datasource();

    /** Exact CDI name of a caller-owned executor. */
    Optional<String> executor();

    /**
     * Managed thread model; defaults to platform-thread and conflicts with an explicit executor.
     */
    Optional<ExecutionMode> executionMode();

    /** Positive maximum number of running JDBC operations. */
    @WithDefault("4")
    int maxConcurrency();

    /** Nonnegative maximum number of queued JDBC operations. */
    @WithDefault("64")
    int maxPending();
  }
}
