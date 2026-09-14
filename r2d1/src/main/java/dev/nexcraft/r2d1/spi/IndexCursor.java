package dev.nexcraft.r2d1.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured keyset cursor for continuing an index query.
 *
 * <p>The sort value is present only when the corresponding query is sorted. For sorted queries it
 * identifies the primary position and the document key provides a deterministic tie-breaker. An
 * unsorted query continues by document identity alone. Cursor encoding for the public API is
 * outside this SPI.
 *
 * @param lastDocumentKey identity of the last document in the preceding page
 * @param sortValue value of the query's sort field on that document, when sorted
 */
public record IndexCursor(DocumentKey lastDocumentKey, Optional<IndexValue> sortValue) {

  /** Creates a validated structured cursor. */
  public IndexCursor {
    Objects.requireNonNull(lastDocumentKey, "lastDocumentKey");
    Objects.requireNonNull(sortValue, "sortValue");
  }
}
