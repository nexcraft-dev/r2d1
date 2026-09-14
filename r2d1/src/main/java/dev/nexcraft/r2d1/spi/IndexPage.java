package dev.nexcraft.r2d1.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of authoritative document identities returned by an index query.
 *
 * <p>Callers resolve returned keys through {@link DocumentStore#get(DocumentKey)}. The optional
 * cursor continues keyset pagination.
 *
 * @param documentKeys matching authoritative document identities in query result order
 * @param nextCursor cursor for the next page, when another page is available
 */
public record IndexPage(List<DocumentKey> documentKeys, Optional<IndexCursor> nextCursor) {

  /** Creates an immutable page with a defensive copy of its document keys. */
  public IndexPage {
    documentKeys = List.copyOf(Objects.requireNonNull(documentKeys, "documentKeys"));
    Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
