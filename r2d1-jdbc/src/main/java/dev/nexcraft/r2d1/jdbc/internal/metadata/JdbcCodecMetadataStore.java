package dev.nexcraft.r2d1.jdbc.internal.metadata;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/** Persists and validates one collection's document codec identity in a JDBC database. */
public final class JdbcCodecMetadataStore {

  private static final String TABLE = "_r2d1_metadata";

  public StoredCodecMetadata initialize(
      Connection connection, String collection, String format, String codec) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    requireText(collection, "collection");
    requireText(format, "format");
    requireText(codec, "codec");
    createTable(connection);
    insertIfAbsent(connection, collection, format, codec);
    StoredCodecMetadata stored = read(connection, collection);
    if (!stored.format().equals(format)) {
      throw new StorageException.CodecMismatch(
          collection, stored.format(), stored.codec(), format, codec);
    }
    return stored;
  }

  private static void createTable(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "CREATE TABLE IF NOT EXISTS \""
                + TABLE
                + "\" (\"collection_name\" VARCHAR(1000000000) PRIMARY KEY NOT NULL, "
                + "\"format\" VARCHAR(1000000000) NOT NULL, "
                + "\"codec\" VARCHAR(1000000000) NOT NULL)")) {
      statement.executeUpdate();
    }
  }

  private static void insertIfAbsent(
      Connection connection, String collection, String format, String codec) throws SQLException {
    String sql =
        "INSERT INTO \""
            + TABLE
            + "\" (\"collection_name\", \"format\", \"codec\") VALUES (?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, collection);
      statement.setString(2, format);
      statement.setString(3, codec);
      statement.executeUpdate();
    } catch (SQLException failure) {
      if (!isConstraintViolation(failure)) {
        throw failure;
      }
    }
  }

  private static StoredCodecMetadata read(Connection connection, String collection)
      throws SQLException {
    String sql =
        "SELECT \"format\", \"codec\" FROM \"" + TABLE + "\" WHERE \"collection_name\" = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, collection);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new StorageException.Operation(
              "JDBC codec metadata was not found for collection " + collection);
        }
        String format = resultSet.getString(1);
        String codec = resultSet.getString(2);
        return new StoredCodecMetadata(
            requireText(format, "stored format"), requireText(codec, "stored codec"));
      }
    }
  }

  private static boolean isConstraintViolation(SQLException failure) {
    for (SQLException current = failure; current != null; current = current.getNextException()) {
      String state = current.getSQLState();
      if (state != null && state.startsWith("23")) {
        return true;
      }
      if (current.getErrorCode() == 19) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("unique")
            || normalized.contains("primary key")
            || normalized.contains("constraint")) {
          return true;
        }
      }
    }
    return false;
  }

  private static String requireText(String value, String description) {
    Objects.requireNonNull(value, description);
    if (value.isBlank()) {
      throw new IllegalArgumentException(description + " must not be blank");
    }
    return value;
  }

  public record StoredCodecMetadata(String format, String codec) {

    public StoredCodecMetadata {
      requireText(format, "format");
      requireText(codec, "codec");
    }
  }
}
