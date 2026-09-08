package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class R2D1Test {

  @Test
  void buildsWithACollectionFactory() {
    R2D1 client = R2D1.builder().collectionFactory(new TrackingFactory()).build();

    assertThat(client).isNotNull();
  }

  @Test
  void buildRequiresACollectionFactory() {
    assertThatThrownBy(() -> R2D1.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("collectionFactory must be configured");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsANullCollectionFactory() {
    assertThatNullPointerException()
        .isThrownBy(() -> R2D1.builder().collectionFactory(null))
        .withMessage("collectionFactory");
  }

  @Test
  void createsACollectionForTheRequestedDocumentType() {
    TrackingFactory factory = new TrackingFactory();
    R2D1 client = R2D1.builder().collectionFactory(factory).build();

    R2D1Collection<TestDocument> collection = client.collection(TestDocument.class);

    assertThat(collection).isInstanceOf(StubCollection.class);
    assertThat(factory.requestedType).isEqualTo(TestDocument.class);
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsANullDocumentType() {
    R2D1 client = R2D1.builder().collectionFactory(new TrackingFactory()).build();

    assertThatNullPointerException()
        .isThrownBy(() -> client.collection(null))
        .withMessage("documentType");
  }

  @Test
  void rejectsANullCollectionFromTheAdapter() {
    R2D1.CollectionFactory factory =
        new R2D1.CollectionFactory() {
          @Override
          @SuppressWarnings("DataFlowIssue")
          public <T> R2D1Collection<T> create(Class<T> documentType) {
            return null;
          }
        };
    R2D1 client = R2D1.builder().collectionFactory(factory).build();

    assertThatNullPointerException()
        .isThrownBy(() -> client.collection(TestDocument.class))
        .withMessage("collectionFactory returned null for " + TestDocument.class.getName());
  }

  private static final class TrackingFactory implements R2D1.CollectionFactory {

    private @Nullable Class<?> requestedType;

    @Override
    public <T> R2D1Collection<T> create(Class<T> documentType) {
      requestedType = documentType;
      return new StubCollection<>();
    }
  }

  private static final class StubCollection<T> implements R2D1Collection<T> {

    @Override
    public void put(T document) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<T> get(String id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Query<T> query() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class TestDocument {}
}
