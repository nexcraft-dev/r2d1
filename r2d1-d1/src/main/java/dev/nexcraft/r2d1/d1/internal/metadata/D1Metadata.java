package dev.nexcraft.r2d1.d1.internal.metadata;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Converts core annotations to D1 collection metadata and validates schema constraints. */
public final class D1Metadata {

  public static final String DOCUMENT_ID = "document_id";
  private static final int MAX_INDEXED_FIELDS = 5;
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private D1Metadata() {}

  public static D1CollectionMetadata inspect(Class<?> documentType) {
    Objects.requireNonNull(documentType, "documentType");
    Document document = documentType.getAnnotation(Document.class);
    if (document == null) {
      throw new IllegalArgumentException(
          "document type must be annotated with @Document: " + documentType.getName());
    }
    String collection = requireIdentifier(document.value(), "document collection");

    Map<String, D1IndexedField> indexedFields = new LinkedHashMap<>();
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
        D1IndexedField metadata =
            new D1IndexedField(name, D1ValueType.fromJavaType(name, field.getType()));
        if (indexedFields.putIfAbsent(name, metadata) != null) {
          throw new IllegalArgumentException("duplicate indexed field: " + name);
        }
      }
    }
    if (indexedFields.size() > MAX_INDEXED_FIELDS) {
      throw new IllegalArgumentException(
          "document type may declare at most "
              + MAX_INDEXED_FIELDS
              + " indexed fields: "
              + documentType.getName());
    }
    List<D1IndexedField> ordered =
        indexedFields.values().stream().sorted(Comparator.comparing(D1IndexedField::name)).toList();
    return new D1CollectionMetadata(documentType, collection, ordered);
  }

  public static String quoteIdentifier(String identifier) {
    return "\"" + requireIdentifier(identifier, "identifier") + "\"";
  }

  public static String physicalIndexName(String collection, String field) {
    return requireIdentifier("idx_" + collection + "_" + field, "index name");
  }

  private static String requireIdentifier(String value, String description) {
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
    java.util.Collections.reverse(hierarchy);
    return hierarchy;
  }
}
