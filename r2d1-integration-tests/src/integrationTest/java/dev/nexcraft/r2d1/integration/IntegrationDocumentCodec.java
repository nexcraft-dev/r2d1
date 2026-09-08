package dev.nexcraft.r2d1.integration;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.D1Document;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.FailureDocument;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.PersistenceDocument;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.RecoveryDocument;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.Value;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class IntegrationDocumentCodec implements DocumentCodec {

  @Override
  public StoredDocument serialize(Object document) {
    if (!(document instanceof Value value)) {
      throw new IllegalArgumentException(
          "Unsupported integration document: " + document.getClass());
    }
    String encoded =
        String.join(
            ".",
            encode(value.id()),
            encode(value.country()),
            value.rank().toString(),
            encode(value.payload()));
    return new StoredDocument(encoded.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public <T> T deserialize(StoredDocument document, Class<T> documentType) {
    String encoded = new String(document.content(), StandardCharsets.UTF_8);
    String[] fields = encoded.split("\\.", -1);
    if (fields.length != 4) {
      throw new IllegalArgumentException("Invalid integration document encoding");
    }
    String id = decode(fields[0]);
    String country = decode(fields[1]);
    Long rank = Long.valueOf(fields[2]);
    String payload = decode(fields[3]);
    Object value;
    if (documentType == D1Document.class) {
      value = new D1Document(id, country, rank, payload);
    } else if (documentType == PersistenceDocument.class) {
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
