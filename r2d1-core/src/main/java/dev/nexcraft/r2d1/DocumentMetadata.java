package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.Query.Request;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Runtime access to the storage-neutral metadata needed by persistence orchestration. */
final class DocumentMetadata<T> {

  private final Class<T> documentType;
  private final String collection;
  private final Field idField;
  private final List<Field> indexedFields;

  private DocumentMetadata(
      Class<T> documentType, String collection, Field idField, List<Field> indexedFields) {
    this.documentType = documentType;
    this.collection = collection;
    this.idField = idField;
    this.indexedFields = indexedFields;
  }

  static <T> DocumentMetadata<T> inspect(Class<T> documentType) {
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

    List<Field> identifiers = new ArrayList<>();
    List<Field> indexedFields = new ArrayList<>();
    for (Class<?> current : hierarchy(documentType)) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isSynthetic()) {
          continue;
        }
        if (field.isAnnotationPresent(Id.class)) {
          identifiers.add(field);
        }
        if (field.isAnnotationPresent(Index.class)) {
          indexedFields.add(field);
        }
      }
    }
    if (identifiers.size() != 1) {
      throw new IllegalArgumentException(
          "document type must declare exactly one @Id field: " + documentType.getName());
    }
    Field idField = identifiers.getFirst();
    if (Modifier.isStatic(idField.getModifiers())) {
      throw new IllegalArgumentException("@Id field must not be static: " + idField.getName());
    }
    if (idField.getType() != String.class) {
      throw new IllegalArgumentException(
          "@Id field must use java.lang.String: " + idField.getName());
    }

    makeAccessible(idField);
    indexedFields.forEach(DocumentMetadata::makeAccessible);
    return new DocumentMetadata<>(
        documentType, document.value(), idField, List.copyOf(indexedFields));
  }

  DocumentKey key(String id) {
    return new DocumentKey(collection, id);
  }

  DocumentKey key(T document) {
    documentType.cast(Objects.requireNonNull(document, "document"));
    var value = read(idField, document);
    if (value == null) {
      throw new IllegalArgumentException("document id must not be null: " + idField.getName());
    }
    String id = (String) value;
    if (id.isBlank()) {
      throw new IllegalArgumentException("document id must not be blank: " + idField.getName());
    }
    return new DocumentKey(collection, id);
  }

  IndexEntry indexEntry(T document, DocumentKey key) {
    Map<String, IndexValue> values = new LinkedHashMap<>();
    for (Field field : indexedFields) {
      values.put(field.getName(), indexValue(field.getName(), read(field, document)));
    }
    return new IndexEntry(key, values);
  }

  IndexQuery indexQuery(Request request) {
    List<IndexQuery.Filter> filters =
        request.filters().stream()
            .map(
                filter ->
                    new IndexQuery.Filter(
                        filter.indexedField(),
                        filter.operator(),
                        indexValue(filter.indexedField(), filter.value())))
            .toList();
    Optional<IndexQuery.Sort> sort =
        request.sort().map(value -> new IndexQuery.Sort(value.indexedField(), value.direction()));
    return new IndexQuery(
        collection, filters, sort, request.limit(), request.cursor().map(CursorCodec::decode));
  }

  Class<T> documentType() {
    return documentType;
  }

  private static IndexValue indexValue(String fieldName, @Nullable Object value) {
    if (value instanceof String stringValue) {
      return new IndexValue.StringValue(stringValue);
    }
    if (value instanceof Long longValue) {
      return new IndexValue.LongValue(longValue);
    }
    if (value instanceof Double doubleValue && Double.isFinite(doubleValue)) {
      return new IndexValue.DoubleValue(doubleValue);
    }
    if (value instanceof Boolean booleanValue) {
      return new IndexValue.BooleanValue(booleanValue);
    }
    if (value == null) {
      throw new IllegalArgumentException("indexed field value must not be null: " + fieldName);
    }
    throw new IllegalArgumentException(
        "indexed field '"
            + fieldName
            + "' uses an unsupported value: "
            + value.getClass().getTypeName());
  }

  private static @Nullable Object read(Field field, Object document) {
    try {
      return field.get(document);
    } catch (IllegalAccessException failure) {
      throw new IllegalStateException(
          "document field is not accessible: " + field.getName(), failure);
    }
  }

  private static void makeAccessible(Field field) {
    if (!field.trySetAccessible()) {
      throw new IllegalArgumentException("document field is not accessible: " + field.getName());
    }
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
}
