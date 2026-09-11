package dev.nexcraft.r2d1.internal.persistence;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/** Internal bridge used by the public persistence factory; not part of the supported API. */
public final class PersistenceRuntime {

  private PersistenceRuntime() {}

  /** Creates one initialized persistence collection for the public factory. */
  public static <T> R2D1Collection<T> create(
      Class<T> documentType,
      DocumentStore documentStore,
      IndexStore indexStore,
      DocumentCodec documentCodec,
      PersistenceCollectionFactory.CollectionInitializer collectionInitializer) {
    Objects.requireNonNull(documentType, "documentType");
    DocumentMetadata<T> metadata = DocumentMetadata.inspect(documentType);
    CompletionStage<@Nullable Void> initialization =
        StageSupport.invoke(() -> collectionInitializer.initialize(documentType));
    StageSupport.await(initialization);
    return new PersistentR2D1Collection<>(metadata, documentStore, indexStore, documentCodec);
  }
}
