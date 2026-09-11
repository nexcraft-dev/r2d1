package dev.nexcraft.r2d1.jdbc.internal.metadata;

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
import java.util.regex.Pattern;

/** Converts core annotations to database-neutral metadata used inside the JDBC adapter. */
public final class JdbcMetadata {

  public static final String DOCUMENT_ID = "document_id";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private JdbcMetadata() {}

  public static CollectionMetadata inspect(Class<?> documentType) {
    Objects.requireNonNull(documentType, "documentType");
    Document document = documentType.getAnnotation(Document.class);
    if (document == null) {
      throw new IllegalArgumentException(
          "document type must be annotated with @Document: " + documentType.getName());
    }
    String collection = requireIdentifier(document.value(), "document collection");

    Map<String, IndexedField> indexedFields = new LinkedHashMap<>();
    for (Class<?> current : hierarchy(documentType)) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isSynthetic() || !field.isAnnotationPresent(Index.class)) {
          continue;
        }
        String name = requireIdentifier(field.getName(), "indexed field");
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
    return new CollectionMetadata(documentType, collection, ordered);
  }

  public static String requireIdentifier(String value, String description) {
    Objects.requireNonNull(value, description);
    if (!IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
          description + " must match " + IDENTIFIER.pattern() + ": " + value);
    }
    return value;
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
  public record CollectionMetadata(
      Class<?> documentType, String collection, List<IndexedField> indexedFields) {

    public CollectionMetadata {
      Objects.requireNonNull(documentType, "documentType");
      Objects.requireNonNull(collection, "collection");
      indexedFields = List.copyOf(Objects.requireNonNull(indexedFields, "indexedFields"));
    }

    public boolean hasSameSchema(CollectionMetadata other) {
      return collection.equals(other.collection) && indexedFields.equals(other.indexedFields);
    }

    public IndexedField requireIndexedField(String name) {
      for (IndexedField field : indexedFields) {
        if (field.name().equals(name)) {
          return field;
        }
      }
      throw new IllegalArgumentException("field is not indexed: " + name);
    }
  }

  /** Logical type of one indexed Java field. */
  public enum ValueType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN,
    TIMESTAMP;

    public static ValueType fromJavaType(String fieldName, Class<?> javaType) {
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
  public record IndexedField(String name, ValueType type) {

    public IndexedField {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(type, "type");
    }
  }
}
