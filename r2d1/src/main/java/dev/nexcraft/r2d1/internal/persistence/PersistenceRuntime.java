package dev.nexcraft.r2d1.internal.persistence;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Internal bridge used by the public persistence factory; not part of the supported API. */
public final class PersistenceRuntime {

  private PersistenceRuntime() {}

  /** Creates one initialized persistence collection for the public factory. */
  public static <T> R2D1Collection<T> create(
      Class<T> documentType,
      DocumentStore documentStore,
      IndexStore indexStore,
      DocumentCodec<T> documentCodec,
      PersistenceCollectionFactory.CollectionInitializer collectionInitializer) {
    Objects.requireNonNull(documentType, "documentType");
    CodecDescriptor descriptor = CodecDescriptor.from(documentCodec);
    DocumentMetadata<T> metadata = DocumentMetadata.inspect(documentType);
    CompletionStage<@Nullable Void> initialization =
        StageSupport.invoke(
            () ->
                collectionInitializer.initialize(
                    documentType, descriptor.format(), descriptor.codec()));
    StageSupport.await(initialization);
    return new PersistentR2D1Collection<>(
        metadata, documentStore, indexStore, documentCodec, descriptor);
  }

  /**
   * Creates one collection after validating document metadata but before resolving a default codec.
   */
  public static <T> R2D1Collection<T> createWithCodecSupplier(
      Class<T> documentType,
      DocumentStore documentStore,
      IndexStore indexStore,
      Supplier<? extends DocumentCodec<T>> documentCodecSupplier,
      PersistenceCollectionFactory.CollectionInitializer collectionInitializer) {
    Objects.requireNonNull(documentType, "documentType");
    DocumentMetadata.inspect(documentType);
    DocumentCodec<T> documentCodec =
        Objects.requireNonNull(
            Objects.requireNonNull(documentCodecSupplier, "documentCodecSupplier").get(),
            "documentCodecSupplier returned null");
    return create(documentType, documentStore, indexStore, documentCodec, collectionInitializer);
  }
}
