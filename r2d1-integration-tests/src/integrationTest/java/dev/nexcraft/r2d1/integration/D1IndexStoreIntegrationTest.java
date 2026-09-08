package dev.nexcraft.r2d1.integration;

import static dev.nexcraft.r2d1.integration.IntegrationDocuments.D1_COLLECTION;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.clearD1Collection;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.entry;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.sortedCountryQuery;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.integration.IntegrationDocuments.D1Document;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexPage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class D1IndexStoreIntegrationTest {

  @Test
  void initializesSchemaAndPerformsSortedCursorQueriesAndDelete() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();

    try (var firstStore = config.openD1()) {
      clearD1Collection(firstStore, D1Document.class, D1_COLLECTION);
    }

    try (var store = config.openD1()) {
      await(store.initialize(D1Document.class));
      await(store.clear(D1_COLLECTION));
      try {
        D1Document a = new D1Document("a", "NZ", 10L, "first");
        D1Document b = new D1Document("b", "NZ", 10L, "second");
        D1Document c = new D1Document("c", "NZ", 20L, "third");
        D1Document filtered = new D1Document("filtered", "AU", 1L, "outside-filter");
        await(store.upsert(entry(D1_COLLECTION, c)));
        await(store.upsert(entry(D1_COLLECTION, b)));
        await(store.upsert(entry(D1_COLLECTION, filtered)));
        await(store.upsert(entry(D1_COLLECTION, a)));

        IndexPage first = await(store.query(sortedCountryQuery(D1_COLLECTION, "NZ", 2)));
        assertThat(first.documentKeys())
            .containsExactly(
                new DocumentKey(D1_COLLECTION, "a"), new DocumentKey(D1_COLLECTION, "b"));
        assertThat(first.nextCursor()).isPresent();

        IndexPage second =
            await(
                store.query(
                    sortedCountryQuery(D1_COLLECTION, "NZ", 2, first.nextCursor().orElseThrow())));
        assertThat(second.documentKeys()).containsExactly(new DocumentKey(D1_COLLECTION, "c"));
        assertThat(second.nextCursor()).isEmpty();

        await(store.delete(new DocumentKey(D1_COLLECTION, "b")));
        IndexPage afterDelete = await(store.query(sortedCountryQuery(D1_COLLECTION, "NZ", 10)));
        assertThat(afterDelete.documentKeys())
            .containsExactly(
                new DocumentKey(D1_COLLECTION, "a"), new DocumentKey(D1_COLLECTION, "c"));
      } finally {
        await(store.clear(D1_COLLECTION));
      }
    }
  }
}
