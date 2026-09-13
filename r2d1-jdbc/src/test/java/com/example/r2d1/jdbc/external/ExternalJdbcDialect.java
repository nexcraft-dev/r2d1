package com.example.r2d1.jdbc.external;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabase;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controlled fake implemented from outside R2D1's internal packages.
 *
 * <p>This class intentionally implements the currently visible {@link JdbcDatabase} bridge rather
 * than using any built-in dialect or shared JDBC implementation. Every operation repeats the raw
 * {@link Connection}, internal {@link CollectionMetadata}, and checked {@link SQLException}
 * plumbing; that repetition records the bridge's boilerplate rather than defining an extension
 * recipe. Its operation surface is deliberately small because the experiment tests visibility and
 * integration, not another database.
 */
public final class ExternalJdbcDialect implements JdbcDatabase {

  private final List<String> operations = new ArrayList<>();

  @Override
  public void initialize(Connection connection, CollectionMetadata metadata) throws SQLException {
    record("initialize", connection, metadata);
  }

  @Override
  public void clear(Connection connection, CollectionMetadata metadata) throws SQLException {
    record("clear", connection, metadata);
  }

  @Override
  public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException {
    record("upsert", connection, metadata);
    Objects.requireNonNull(entry, "entry");
  }

  @Override
  public IndexPage query(Connection connection, CollectionMetadata metadata, IndexQuery query)
      throws SQLException {
    record("query", connection, metadata);
    Objects.requireNonNull(query, "query");
    return new IndexPage(List.of(), java.util.Optional.empty());
  }

  @Override
  public void delete(Connection connection, CollectionMetadata metadata, DocumentKey key)
      throws SQLException {
    record("delete", connection, metadata);
    Objects.requireNonNull(key, "key");
  }

  /** Returns the operations observed by this controlled fake. */
  public List<String> operations() {
    return List.copyOf(operations);
  }

  private void record(String operation, Connection connection, CollectionMetadata metadata) {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(metadata, "metadata");
    operations.add(operation);
  }
}
