package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabase;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDialects;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDialects.Resolver;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcCodecMetadataStore;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcCodecMetadataStore.StoredCodecMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous {@link IndexStore} foundation for blocking JDBC databases.
 *
 * <p>This store owns neither its {@link DataSource} nor its {@link JdbcExecution}. Every JDBC call
 * is submitted to the supplied bounded execution resource, and every connection is closed after one
 * operation. The caller owns endpoint configuration, credentials, TLS, pooling, database-server
 * lifecycle, and execution-resource closure. Execution mode does not select or alter deployment
 * topology.
 *
 * <p>{@link #initialize(Class)} must complete before a collection is used. Database detection runs
 * through {@link DatabaseMetaData} before a dialect can mutate schema. H2 and HSQLDB are supported
 * through embedded and remote/server connections; SQLite is supported as a local embedded file
 * database. Other databases fail safely before schema initialization. All JDBC storage remains a
 * derived, rebuildable index projection rather than authoritative document storage.
 */
public final class JdbcIndexStore implements IndexStore {

  private final DataSource dataSource;
  private final JdbcExecution execution;
  private final Resolver dialectResolver;
  private final JdbcCodecMetadataStore codecMetadataStore = new JdbcCodecMetadataStore();
  private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();

  /**
   * Creates a JDBC index store over caller-owned data and execution resources.
   *
   * @param dataSource caller-owned source of JDBC connections
   * @param execution caller-owned bounded execution resource for blocking JDBC work
   * @throws NullPointerException if either argument is {@code null}
   */
  public JdbcIndexStore(DataSource dataSource, JdbcExecution execution) {
    this(dataSource, execution, JdbcDialects.resolver());
  }

  JdbcIndexStore(DataSource dataSource, JdbcExecution execution, Resolver dialectResolver) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.execution = Objects.requireNonNull(execution, "execution");
    this.dialectResolver = Objects.requireNonNull(dialectResolver, "dialectResolver");
  }

  /**
   * Validates document annotations, detects the database, and prepares its collection schema.
   *
   * <p>Concurrent first initialization calls for the same collection share one stage. A failed
   * initialization remains cached and is not retried automatically.
   *
   * @param documentType document class annotated with {@code @Document}
   * @return non-null stage that completes when the collection schema is ready
   * @throws NullPointerException if {@code documentType} is {@code null}
   * @throws IllegalArgumentException if document metadata is unsupported or conflicts with an
   *     existing registration
   */
  public CompletionStage<@Nullable Void> initialize(Class<?> documentType) {
    return initialize(documentType, "json", "avaje-jsonb-3");
  }

  /**
   * Validates document annotations and codec metadata before preparing the collection schema.
   *
   * @param documentType document class annotated with {@code @Document}
   * @param format current codec storage format
   * @param codec current codec identity
   * @return non-null stage that completes when metadata and schema are ready
   * @throws NullPointerException if any argument is {@code null}
   */
  public CompletionStage<@Nullable Void> initialize(
      Class<?> documentType, String format, String codec) {
    requireText(format, "format");
    requireText(codec, "codec");
    CollectionMetadata metadata = JdbcMetadata.inspect(documentType);
    Registration candidate = new Registration(metadata, format, codec, new CompletableFuture<>());
    Registration registration = registrations.putIfAbsent(metadata.collection(), candidate);
    if (registration != null) {
      if (!registration.metadata().hasSameSchema(metadata)) {
        throw new IllegalArgumentException(
            "collection is already initialized with different metadata: " + metadata.collection());
      }
      if (registration.format().equals(format)) {
        return registration.initialization();
      }
      return registration
          .initialization()
          .thenRun(
              () ->
                  requireCompatible(
                      metadata.collection(),
                      Objects.requireNonNull(
                          registration.codecMetadata(),
                          "initialized JDBC registration has no codec metadata"),
                      format,
                      codec));
    }

    CompletionStage<@Nullable Void> initialization = executeInitialization(candidate);
    initialization.whenComplete(
        (ignored, failure) -> {
          if (failure == null) {
            candidate.initialization().complete(null);
          } else {
            candidate.initialization().completeExceptionally(failure);
          }
        });
    return candidate.initialization();
  }

  @Override
  public CompletionStage<@Nullable Void> clear(String collection) {
    String validatedCollection = requireCollection(collection);
    Registration registration = registrations.get(validatedCollection);
    if (registration == null) {
      return notInitialized(validatedCollection);
    }
    return afterInitialization(
        registration,
        "clear",
        (connection, dialect) -> {
          dialect.clear(connection, registration.metadata());
          return null;
        });
  }

  @Override
  public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
    Objects.requireNonNull(entry, "entry");
    Registration registration = registrations.get(entry.documentKey().collection());
    if (registration == null) {
      return notInitialized(entry.documentKey().collection());
    }
    return afterInitialization(
        registration,
        "upsert",
        (connection, dialect) -> {
          dialect.upsert(connection, registration.metadata(), entry);
          return null;
        });
  }

  @Override
  public CompletionStage<IndexPage> query(IndexQuery query) {
    Objects.requireNonNull(query, "query");
    Registration registration = registrations.get(query.collection());
    if (registration == null) {
      return notInitialized(query.collection());
    }
    return afterInitialization(
        registration,
        "query",
        (connection, dialect) ->
            Objects.requireNonNull(
                dialect.query(connection, registration.metadata(), query),
                "JDBC dialect returned a null page"));
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    Registration registration = registrations.get(key.collection());
    if (registration == null) {
      return notInitialized(key.collection());
    }
    return afterInitialization(
        registration,
        "delete",
        (connection, dialect) -> {
          dialect.delete(connection, registration.metadata(), key);
          return null;
        });
  }

  private CompletionStage<@Nullable Void> executeInitialization(Registration registration) {
    return execute(
        "schema initialization",
        null,
        connection -> {
          JdbcDatabase dialect =
              Objects.requireNonNull(
                  dialectResolver.detect(connection.getMetaData()),
                  "JDBC dialect resolver returned null");
          StoredCodecMetadata stored =
              codecMetadataStore.initialize(
                  connection,
                  registration.metadata().collection(),
                  registration.format(),
                  registration.codec());
          registration.codecMetadata(stored);
          try {
            dialect.initialize(connection, registration.metadata());
          } catch (SQLException failure) {
            throw translate("schema initialization", dialect, failure);
          }
          registration.dialect(dialect);
          return null;
        });
  }

  private <T extends @Nullable Object> CompletionStage<T> afterInitialization(
      Registration registration, String operation, ConnectedDialectOperation<T> invocation) {
    return registration
        .initialization()
        .thenCompose(
            ignored -> {
              JdbcDatabase dialect =
                  Objects.requireNonNull(
                      registration.dialect(), "initialized JDBC registration has no dialect");
              return execute(
                  operation, dialect, connection -> invocation.execute(connection, dialect));
            });
  }

  private <T extends @Nullable Object> CompletionStage<T> execute(
      String operation, @Nullable JdbcDatabase dialect, ConnectedOperation<T> invocation) {
    return execution.execute(
        () -> {
          try (Connection connection =
              Objects.requireNonNull(
                  dataSource.getConnection(), "DataSource returned a null connection")) {
            return invocation.execute(connection);
          } catch (StorageException failure) {
            throw failure;
          } catch (SQLException failure) {
            throw translate(operation, dialect, failure);
          } catch (RuntimeException failure) {
            throw new StorageException.Operation("JDBC " + operation + " failed", failure);
          }
        });
  }

  private static StorageException translate(
      String operation, @Nullable JdbcDatabase dialect, SQLException failure) {
    if (dialect == null) {
      return JdbcDialects.translateBeforeDetection(operation, failure);
    }
    try {
      StorageException translated =
          Objects.requireNonNull(
              dialect.translate(operation, failure), "JDBC dialect returned a null exception");
      return preserveCause(translated, failure);
    } catch (StorageException translationFailure) {
      return preserveCause(translationFailure, failure);
    } catch (RuntimeException translationFailure) {
      StorageException.Operation translated =
          new StorageException.Operation(
              "JDBC " + operation + " failure could not be translated", failure);
      translated.addSuppressed(translationFailure);
      return translated;
    }
  }

  private static StorageException preserveCause(
      StorageException translated, SQLException originalFailure) {
    Throwable current = translated;
    while (current != null) {
      if (current == originalFailure) {
        return translated;
      }
      current = current.getCause();
    }
    if (translated.getCause() == null) {
      translated.initCause(originalFailure);
    } else {
      translated.addSuppressed(originalFailure);
    }
    return translated;
  }

  private static <T extends @Nullable Object> CompletionStage<T> notInitialized(String collection) {
    return CompletableFuture.failedFuture(
        new StorageException.Operation("JDBC collection is not initialized: " + collection));
  }

  private static String requireCollection(String collection) {
    Objects.requireNonNull(collection, "collection");
    if (collection.isBlank()) {
      throw new IllegalArgumentException("collection must not be blank");
    }
    return collection;
  }

  private static void requireCompatible(
      String collection, StoredCodecMetadata stored, String format, String codec) {
    if (!stored.format().equals(format)) {
      throw new StorageException.CodecMismatch(
          collection, stored.format(), stored.codec(), format, codec);
    }
  }

  private static String requireText(String value, String description) {
    Objects.requireNonNull(value, description);
    if (value.isBlank()) {
      throw new IllegalArgumentException(description + " must not be blank");
    }
    return value;
  }

  private static final class Registration {

    private final CollectionMetadata metadata;
    private final String format;
    private final String codec;
    private final CompletableFuture<@Nullable Void> initialization;
    private volatile @Nullable StoredCodecMetadata codecMetadata;
    private volatile @Nullable JdbcDatabase dialect;

    private Registration(
        CollectionMetadata metadata,
        String format,
        String codec,
        CompletableFuture<@Nullable Void> initialization) {
      this.metadata = Objects.requireNonNull(metadata, "metadata");
      this.format = Objects.requireNonNull(format, "format");
      this.codec = Objects.requireNonNull(codec, "codec");
      this.initialization = Objects.requireNonNull(initialization, "initialization");
    }

    private CollectionMetadata metadata() {
      return metadata;
    }

    private CompletableFuture<@Nullable Void> initialization() {
      return initialization;
    }

    private String format() {
      return format;
    }

    private String codec() {
      return codec;
    }

    private @Nullable StoredCodecMetadata codecMetadata() {
      return codecMetadata;
    }

    private void codecMetadata(StoredCodecMetadata value) {
      codecMetadata = Objects.requireNonNull(value, "value");
    }

    private @Nullable JdbcDatabase dialect() {
      return dialect;
    }

    private void dialect(JdbcDatabase value) {
      dialect = Objects.requireNonNull(value, "value");
    }
  }

  @FunctionalInterface
  private interface ConnectedOperation<T extends @Nullable Object> {

    T execute(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface ConnectedDialectOperation<T extends @Nullable Object> {

    T execute(Connection connection, JdbcDatabase dialect) throws SQLException;
  }
}
