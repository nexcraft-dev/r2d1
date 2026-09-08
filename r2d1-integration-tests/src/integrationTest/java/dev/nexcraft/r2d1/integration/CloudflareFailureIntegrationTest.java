package dev.nexcraft.r2d1.integration;

import static dev.nexcraft.r2d1.integration.IntegrationDocuments.FAILURE_COLLECTION;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.clearD1Collection;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.clearR2Collection;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.PersistenceException;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.FailureDocument;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CloudflareFailureIntegrationTest {

  @Test
  void rejectsAnInvalidD1TokenWithoutExposingCredentials() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    var invalidConfig = config.invalidD1Token();

    try (var store = new D1IndexStore(invalidConfig)) {
      Throwable failure = catchThrowable(() -> await(store.initialize(FailureDocument.class)));

      assertThat(failure).isInstanceOf(StorageException.class);
      assertThat(failure.getMessage())
          .doesNotContain(config.d1().apiToken())
          .doesNotContain(invalidConfig.apiToken());
      assertThat(invalidConfig.toString())
          .doesNotContain(config.d1().apiToken())
          .doesNotContain(invalidConfig.apiToken());
    }
  }

  @Test
  void rejectsInvalidR2CredentialsWithoutExposingCredentials() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    var invalidConfig = config.invalidR2Credentials();

    try (var store = new R2DocumentStore(invalidConfig)) {
      Throwable failure = catchThrowable(() -> await(store.list(FAILURE_COLLECTION, null, 1)));

      assertThat(failure).isInstanceOf(StorageException.class);
      assertThat(failure.getMessage())
          .doesNotContain(config.r2().accessKeyId())
          .doesNotContain(config.r2().secretAccessKey())
          .doesNotContain(invalidConfig.accessKeyId())
          .doesNotContain(invalidConfig.secretAccessKey());
      assertThat(invalidConfig.toString())
          .doesNotContain(config.r2().accessKeyId())
          .doesNotContain(config.r2().secretAccessKey())
          .doesNotContain(invalidConfig.accessKeyId())
          .doesNotContain(invalidConfig.secretAccessKey());
    }
  }

  @Test
  void failsAQueryForAnExistingD1RowWithoutAnR2Document() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    try (var documents = config.openR2();
        var indexes = config.openD1()) {
      clearR2Collection(documents, FAILURE_COLLECTION);
      clearD1Collection(indexes, FailureDocument.class, FAILURE_COLLECTION);
      try {
        FailureDocument stale = new FailureDocument("missing-r2", "NZ", 10L, "stale-index-row");
        await(indexes.upsert(entry(FAILURE_COLLECTION, stale)));
        R2D1Collection<FailureDocument> collection =
            R2D1.builder()
                .collectionFactory(
                    new PersistenceCollectionFactory(
                        documents, indexes, new IntegrationDocumentCodec(), indexes::initialize))
                .build()
                .collection(FailureDocument.class);

        Throwable failure =
            catchThrowable(() -> collection.query().where("country").eq("NZ").limit(10).fetch());

        assertThat(failure)
            .isInstanceOfSatisfying(
                PersistenceException.InconsistentState.class,
                inconsistent ->
                    assertThat(inconsistent.documentKey())
                        .isEqualTo(new DocumentKey(FAILURE_COLLECTION, stale.id())));
      } finally {
        clearR2Collection(documents, FAILURE_COLLECTION);
        await(indexes.clear(FAILURE_COLLECTION));
      }
    }
  }
}
