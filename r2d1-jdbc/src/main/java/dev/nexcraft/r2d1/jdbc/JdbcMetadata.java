package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts core annotations to database-neutral metadata used inside the JDBC adapter. */
final class JdbcMetadata {

  static final String DOCUMENT_ID = "document_id";

  private JdbcMetadata() {}

  static CollectionMetadata inspect(Class<?> documentType) {
    Objects.requireNonNull(documentType, "documentType");
    Document document = documentType.getAnnotation(Document.class);
    if (document == null) {
      throw new IllegalArgumentException(
          "document type must be annotated with @Document: " + documentType.getName());
    }
    if (document.value().isBlank()) {
      throw new IllegalArgumentException(
          "document collection must not be blank: " + documentType.getName());
    }

    Map<String, IndexedField> indexedFields = new LinkedHashMap<>();
    for (Class<?> current : hierarchy(documentType)) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isSynthetic() || !field.isAnnotationPresent(Index.class)) {
          continue;
        }
        String name = field.getName();
        if (DOCUMENT_ID.equals(name)) {
          throw new IllegalArgumentException(
              "indexed field name is reserved by R2D1: " + DOCUMENT_ID);
        }
        IndexedField metadata =
            new IndexedField(name, ValueType.fromJavaType(name, field.getType()));
        if (indexedFields.putIfAbsent(name, metadata) != null) {
          throw new IllegalArgumentException("duplicate indexed field: " + name);
        }
      }
    }
    List<IndexedField> ordered =
        indexedFields.values().stream().sorted(Comparator.comparing(IndexedField::name)).toList();
    return new CollectionMetadata(documentType, document.value(), ordered);
  }

  private static List<Class<?>> hierarchy(Class<?> documentType) {
    List<Class<?>> hierarchy = new ArrayList<>();
    Class<?> current = documentType;
    while (current != null && current != Object.class) {
      hierarchy.add(current);
      current = current.getSuperclass();
    }
    Collections.reverse(hierarchy);
    return hierarchy;
  }

  /** Metadata required by a JDBC dialect to initialize and operate on one collection. */
  record CollectionMetadata(
      Class<?> documentType, String collection, List<IndexedField> indexedFields) {

    CollectionMetadata {
      Objects.requireNonNull(documentType, "documentType");
      Objects.requireNonNull(collection, "collection");
      indexedFields = List.copyOf(Objects.requireNonNull(indexedFields, "indexedFields"));
    }

    boolean hasSameSchema(CollectionMetadata other) {
      return collection.equals(other.collection) && indexedFields.equals(other.indexedFields);
    }
  }

  /** Logical type of one indexed Java field. */
  enum ValueType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN,
    TIMESTAMP;

    static ValueType fromJavaType(String fieldName, Class<?> javaType) {
      if (javaType == String.class) {
        return STRING;
      }
      if (javaType == long.class || javaType == Long.class) {
        return LONG;
      }
      if (javaType == double.class || javaType == Double.class) {
        return DOUBLE;
      }
      if (javaType == boolean.class || javaType == Boolean.class) {
        return BOOLEAN;
      }
      if (javaType == Instant.class) {
        return TIMESTAMP;
      }
      throw new IllegalArgumentException(
          "indexed field '" + fieldName + "' uses unsupported type: " + javaType.getTypeName());
    }
  }

  /** Name and logical value type of one indexed field. */
  record IndexedField(String name, ValueType type) {

    IndexedField {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(type, "type");
    }
  }
}
