package dev.nexcraft.r2d1.d1.internal.schema;

import dev.nexcraft.r2d1.d1.internal.metadata.D1Metadata;
import dev.nexcraft.r2d1.d1.internal.sql.D1Parameter;
import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.sql.D1Statement;
import dev.nexcraft.r2d1.d1.internal.transport.D1Transport;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/** Persists and validates one collection's document codec identity in D1. */
public final class D1CodecMetadataStore {

  private static final String TABLE = "_r2d1_metadata";

  private final D1Transport transport;

  public D1CodecMetadataStore(D1Transport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  public CompletionStage<StoredCodecMetadata> initialize(
      String collection, String format, String codec) {
    requireText(collection, "collection");
    requireText(format, "format");
    requireText(codec, "codec");
    return execute(
            D1Statement.of(
                "CREATE TABLE IF NOT EXISTS "
                    + D1Metadata.quoteIdentifier(TABLE)
                    + " ("
                    + D1Metadata.quoteIdentifier("collection_name")
                    + " TEXT PRIMARY KEY NOT NULL, "
                    + D1Metadata.quoteIdentifier("format")
                    + " TEXT NOT NULL, "
                    + D1Metadata.quoteIdentifier("codec")
                    + " TEXT NOT NULL)"))
        .thenCompose(
            ignored ->
                execute(
                    new D1Statement(
                        "INSERT OR IGNORE INTO "
                            + D1Metadata.quoteIdentifier(TABLE)
                            + " ("
                            + D1Metadata.quoteIdentifier("collection_name")
                            + ", "
                            + D1Metadata.quoteIdentifier("format")
                            + ", "
                            + D1Metadata.quoteIdentifier("codec")
                            + ") VALUES (?, ?, ?)",
                        List.of(
                            new D1Parameter.TextParameter(collection),
                            new D1Parameter.TextParameter(format),
                            new D1Parameter.TextParameter(codec)))))
        .thenCompose(ignored -> read(collection))
        .thenApply(
            stored -> {
              if (!stored.format().equals(format)) {
                throw new StorageException.CodecMismatch(
                    collection, stored.format(), stored.codec(), format, codec);
              }
              return stored;
            });
  }

  private CompletionStage<StoredCodecMetadata> read(String collection) {
    return execute(
            new D1Statement(
                "SELECT "
                    + D1Metadata.quoteIdentifier("format")
                    + ", "
                    + D1Metadata.quoteIdentifier("codec")
                    + " FROM "
                    + D1Metadata.quoteIdentifier(TABLE)
                    + " WHERE "
                    + D1Metadata.quoteIdentifier("collection_name")
                    + " = ?",
                List.of(new D1Parameter.TextParameter(collection))))
        .thenApply(
            result -> {
              if (result.rows().size() != 1) {
                throw new StorageException.Operation(
                    "D1 codec metadata was not found for collection " + collection);
              }
              Map<String, @Nullable Object> row = result.rows().getFirst();
              return new StoredCodecMetadata(
                  requireString(row, "format"), requireString(row, "codec"));
            });
  }

  private CompletionStage<D1Result> execute(D1Statement statement) {
    try {
      return Objects.requireNonNull(
          transport.execute(statement), "D1 transport returned a null stage");
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(
          failure instanceof StorageException
              ? failure
              : new StorageException.Operation("D1 codec metadata operation failed", failure));
    }
  }

  private static String requireString(Map<String, @Nullable Object> row, String field) {
    Object value = row.get(field);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new StorageException.Operation("D1 codec metadata returned an invalid " + field);
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
