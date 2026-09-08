package dev.nexcraft.r2d1.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One bounded page of authoritative document identities.
 *
 * <p>The optional cursor continues the listing through the same {@link DocumentStore}. Document
 * content is loaded separately through {@link DocumentStore#get(DocumentKey)}.
 *
 * @param documentKeys authoritative document identities in storage listing order
 * @param nextCursor cursor for the next page, when another page is available
 */
public record DocumentPage(List<DocumentKey> documentKeys, Optional<DocumentCursor> nextCursor) {

  /** Creates an immutable page with a defensive copy of its document keys. */
  public DocumentPage {
    documentKeys = List.copyOf(Objects.requireNonNull(documentKeys, "documentKeys"));
    Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
