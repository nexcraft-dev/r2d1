package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Creates synchronous collections backed by asynchronous document and index stores.
 *
 * <p>Each {@link #create(Class)} call inspects document metadata, starts the supplied collection
 * initializer, and waits for that complete initialization stage before returning a collection. The
 * factory does not cache collection instances or take ownership of storage lifecycle.
 */
public final class PersistenceCollectionFactory implements R2D1.CollectionFactory {

  private final DocumentStore documentStore;
  private final IndexStore indexStore;
  private final DocumentCodec documentCodec;
  private final CollectionInitializer collectionInitializer;

  /**
   * Creates a persistence collection factory.
   *
   * @param documentStore authoritative serialized document storage
   * @param indexStore derived query index
   * @param documentCodec domain document serialization boundary
   * @param collectionInitializer asynchronous collection schema initializer
   * @throws NullPointerException if any argument is {@code null}
   */
  public PersistenceCollectionFactory(
      DocumentStore documentStore,
      IndexStore indexStore,
      DocumentCodec documentCodec,
      CollectionInitializer collectionInitializer) {
    this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
    this.indexStore = Objects.requireNonNull(indexStore, "indexStore");
    this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
    this.collectionInitializer =
        Objects.requireNonNull(collectionInitializer, "collectionInitializer");
  }

  @Override
  public <T> R2D1Collection<T> create(Class<T> documentType) {
    Objects.requireNonNull(documentType, "documentType");
    DocumentMetadata<T> metadata = DocumentMetadata.inspect(documentType);
    CompletionStage<@Nullable Void> initialization =
        StageSupport.invoke(() -> collectionInitializer.initialize(documentType));
    StageSupport.await(initialization);
    return new PersistentR2D1Collection<>(metadata, documentStore, indexStore, documentCodec);
  }

  /** Asynchronously initializes or validates storage metadata for one document collection. */
  @FunctionalInterface
  public interface CollectionInitializer {

    /**
     * Initializes or validates storage for a document type.
     *
     * @param documentType annotated document class
     * @return stage that completes when the collection is ready
     * @throws NullPointerException if {@code documentType} is {@code null}
     */
    CompletionStage<@Nullable Void> initialize(Class<?> documentType);
  }
}
