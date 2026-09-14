package dev.nexcraft.r2d1.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Derived index data for one authoritative document.
 *
 * <p>The document's physical location is deliberately derived by adapters from its logical key, so
 * this entry cannot contain a stale competing storage reference.
 *
 * @param documentKey authoritative document identity
 * @param values indexed fields; missing fields are omitted
 */
public record IndexEntry(DocumentKey documentKey, Map<String, IndexValue> values) {

  /** Creates a validated entry with a defensive copy of the indexed values. */
  public IndexEntry {
    Objects.requireNonNull(documentKey, "documentKey");
    Objects.requireNonNull(values, "values");
    values.forEach(
        (field, value) -> {
          SpiValidation.requireText(field, "indexedField");
          Objects.requireNonNull(value, "index value for " + field);
        });
    values = Map.copyOf(values);
  }
}
