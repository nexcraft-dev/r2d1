package dev.nexcraft.r2d1.d1.internal.metadata;

import java.util.List;
import java.util.Objects;

/** Metadata required to manage a D1 schema and queries for one document type. */
public record D1CollectionMetadata(
    Class<?> documentType, String collection, List<D1IndexedField> indexedFields) {

  public D1CollectionMetadata {
    Objects.requireNonNull(documentType, "documentType");
    Objects.requireNonNull(collection, "collection");
    indexedFields = List.copyOf(Objects.requireNonNull(indexedFields, "indexedFields"));
  }

  public D1IndexedField requireIndexedField(String name) {
    for (D1IndexedField field : indexedFields) {
      if (field.name().equals(name)) {
        return field;
      }
    }
    throw new IllegalArgumentException("field is not indexed: " + name);
  }

  public boolean hasSameSchema(D1CollectionMetadata other) {
    return collection.equals(other.collection) && indexedFields.equals(other.indexedFields);
  }
}
