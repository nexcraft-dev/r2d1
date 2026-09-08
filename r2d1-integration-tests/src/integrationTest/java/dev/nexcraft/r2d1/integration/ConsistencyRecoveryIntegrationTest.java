package dev.nexcraft.r2d1.integration;

import static dev.nexcraft.r2d1.integration.IntegrationDocuments.RECOVERY_COLLECTION;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.clearD1Collection;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.clearR2Collection;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.entry;
import static dev.nexcraft.r2d1.integration.IntegrationSupport.sortedCountryQuery;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.RecoveryDocument;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexPage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class ConsistencyRecoveryIntegrationTest {

  @Test
  void restoresMissingRowsAndRemovesStaleRows() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    try (var documents = config.openR2();
        var indexes = config.openD1()) {
      clearR2Collection(documents, RECOVERY_COLLECTION);
      clearD1Collection(indexes, RecoveryDocument.class, RECOVERY_COLLECTION);
      try {
        R2D1Collection<RecoveryDocument> collection =
            R2D1.builder()
                .collectionFactory(
                    new PersistenceCollectionFactory(
                        documents, indexes, new IntegrationDocumentCodec(), indexes::initialize))
                .build()
                .collection(RecoveryDocument.class);
        RecoveryDocument restored = new RecoveryDocument("restored", "NZ", 10L, "restore-me");
        RecoveryDocument retained = new RecoveryDocument("retained", "NZ", 20L, "keep-me");
        collection.put(restored);
        collection.put(retained);

        await(indexes.delete(new DocumentKey(RECOVERY_COLLECTION, restored.id())));
        assertThat(nzKeys(indexes))
            .containsExactly(new DocumentKey(RECOVERY_COLLECTION, retained.id()));

        collection.rebuildIndex();

        assertThat(nzKeys(indexes))
            .containsExactly(
                new DocumentKey(RECOVERY_COLLECTION, restored.id()),
                new DocumentKey(RECOVERY_COLLECTION, retained.id()));

        RecoveryDocument stale = new RecoveryDocument("stale", "NZ", 30L, "remove-me");
        await(indexes.upsert(entry(RECOVERY_COLLECTION, stale)));
        assertThat(nzKeys(indexes))
            .containsExactly(
                new DocumentKey(RECOVERY_COLLECTION, restored.id()),
                new DocumentKey(RECOVERY_COLLECTION, retained.id()),
                new DocumentKey(RECOVERY_COLLECTION, stale.id()));

        collection.rebuildIndex();

        assertThat(nzKeys(indexes))
            .containsExactly(
                new DocumentKey(RECOVERY_COLLECTION, restored.id()),
                new DocumentKey(RECOVERY_COLLECTION, retained.id()));
        assertThat(collection.get(restored.id())).contains(restored);
        assertThat(collection.get(retained.id())).contains(retained);
      } finally {
        clearR2Collection(documents, RECOVERY_COLLECTION);
        await(indexes.clear(RECOVERY_COLLECTION));
      }
    }
  }

  private static List<DocumentKey> nzKeys(D1IndexStore indexes) {
    IndexPage page = await(indexes.query(sortedCountryQuery(RECOVERY_COLLECTION, "NZ", 10)));
    return page.documentKeys();
  }
}
