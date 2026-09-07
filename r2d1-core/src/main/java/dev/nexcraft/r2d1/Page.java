package dev.nexcraft.r2d1;

import java.util.List;
import java.util.Objects;

/**
 * One immutable page of query results.
 *
 * @param items documents in this page
 * @param nextCursor opaque cursor for the next page, or {@code null} when this is the final page
 * @param <T> document type
 */
public record Page<T>(List<T> items, String nextCursor) {

  /** Creates a page and defensively copies its items. */
  public Page {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (nextCursor != null && nextCursor.isBlank()) {
      throw new IllegalArgumentException("nextCursor must not be blank");
    }
  }

  /**
   * Reports whether another page is available.
   *
   * @return {@code true} when {@link #nextCursor()} contains a cursor
   */
  public boolean hasNextPage() {
    return nextCursor != null;
  }
}
