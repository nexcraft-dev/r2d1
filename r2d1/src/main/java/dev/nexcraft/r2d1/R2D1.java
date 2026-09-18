package dev.nexcraft.r2d1;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Framework-independent entry point for working with R2D1 document collections. */
public final class R2D1 {

  private final CollectionFactory collectionFactory;

  private R2D1(CollectionFactory collectionFactory) {
    this.collectionFactory = collectionFactory;
  }

  /**
   * Creates a builder for an R2D1 client.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Opens a collection for the supplied document type.
   *
   * @param documentType document class carrying the R2D1 metadata annotations
   * @param <T> document type
   * @return the collection provided by the configured adapter
   * @throws NullPointerException if {@code documentType} is {@code null}, or if the adapter returns
   *     {@code null}
   */
  public <T> R2D1Collection<T> collection(Class<T> documentType) {
    Objects.requireNonNull(documentType, "documentType");
    return Objects.requireNonNull(
        collectionFactory.create(documentType),
        () -> "collectionFactory returned null for " + documentType.getName());
  }

  /**
   * Opens a collection with an explicitly selected codec.
   *
   * @param documentType document class carrying the R2D1 metadata annotations
   * @param codec codec bound to this collection
   * @param <T> document type
   * @return the collection provided by the configured adapter
   * @throws NullPointerException if {@code documentType} or {@code codec} is {@code null}, or if
   *     the adapter returns {@code null}
   */
  public <T> R2D1Collection<T> collection(Class<T> documentType, DocumentCodec<T> codec) {
    Objects.requireNonNull(documentType, "documentType");
    Objects.requireNonNull(codec, "codec");
    return Objects.requireNonNull(
        collectionFactory.create(documentType, codec),
        () -> "collectionFactory returned null for " + documentType.getName());
  }

  /** Builds framework-independent R2D1 clients from adapter-provided collection factories. */
  public static final class Builder {

    private @Nullable CollectionFactory collectionFactory;

    private Builder() {}

    /**
     * Configures the factory that creates adapter-backed collections.
     *
     * @param collectionFactory collection factory supplied by an R2D1 adapter
     * @return this builder
     * @throws NullPointerException if {@code collectionFactory} is {@code null}
     */
    public Builder collectionFactory(CollectionFactory collectionFactory) {
      this.collectionFactory = Objects.requireNonNull(collectionFactory, "collectionFactory");
      return this;
    }

    /**
     * Builds the R2D1 client.
     *
     * @return a configured client
     * @throws IllegalStateException if no collection factory has been configured
     */
    public R2D1 build() {
      if (collectionFactory == null) {
        throw new IllegalStateException("collectionFactory must be configured");
      }
      return new R2D1(collectionFactory);
    }
  }

  /** Adapter contract for creating collections without exposing storage implementation details. */
  public interface CollectionFactory {

    /**
     * Creates a collection for a document type.
     *
     * @param documentType document class
     * @param <T> document type
     * @return a non-null collection
     */
    <T> R2D1Collection<T> create(Class<T> documentType);

    /**
     * Creates a collection for a document type and an explicit codec.
     *
     * <p>Factories that do not support collection-specific codecs fail explicitly instead of
     * silently ignoring the supplied codec.
     *
     * @param documentType document class
     * @param codec collection-specific codec
     * @param <T> document type
     * @return a non-null collection
     * @throws UnsupportedOperationException if the factory has no codec-aware implementation
     */
    default <T> R2D1Collection<T> create(Class<T> documentType, DocumentCodec<T> codec) {
      throw new UnsupportedOperationException(
          "collection factory does not support collection-specific document codecs");
    }
  }
}
