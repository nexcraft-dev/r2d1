package dev.nexcraft.r2d1.integration.support;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.FailureDocument;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.PersistenceDocument;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.RecoveryDocument;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.Value;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class IntegrationDocumentCodec<T extends Value> implements DocumentCodec<T> {

  private final Class<T> documentType;

  public IntegrationDocumentCodec(Class<T> documentType) {
    this.documentType = documentType;
  }

  @Override
  public String id() {
    return "integration-codec-1";
  }

  @Override
  public String format() {
    return "integration";
  }

  @Override
  public byte[] encode(T value) {
    String encoded =
        String.join(
            ".",
            encode(value.id()),
            encode(value.country()),
            value.rank().toString(),
            encode(value.payload()));
    return encoded.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public T decode(byte[] data) {
    String encoded = new String(data, StandardCharsets.UTF_8);
    String[] fields = encoded.split("\\.", -1);
    if (fields.length != 4) {
      throw new IllegalArgumentException("Invalid integration document encoding");
    }
    String id = decode(fields[0]);
    String country = decode(fields[1]);
    Long rank = Long.valueOf(fields[2]);
    String payload = decode(fields[3]);
    Object value;
    if (documentType == PersistenceDocument.class) {
      value = new PersistenceDocument(id, country, rank, payload);
    } else if (documentType == RecoveryDocument.class) {
      value = new RecoveryDocument(id, country, rank, payload);
    } else if (documentType == FailureDocument.class) {
      value = new FailureDocument(id, country, rank, payload);
    } else {
      throw new IllegalArgumentException(
          "Unsupported integration document type: " + documentType.getName());
    }
    return documentType.cast(value);
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }
}
