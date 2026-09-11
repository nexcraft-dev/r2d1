package dev.nexcraft.r2d1.integration.persistence;

import static dev.nexcraft.r2d1.integration.support.IntegrationDocuments.PERSISTENCE_COLLECTION;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.clearD1Collection;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.clearR2Collection;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.entry;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.sortedCountryQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.Page;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.integration.cloudflare.CloudflareIntegrationConfig;
import dev.nexcraft.r2d1.integration.support.IntegrationDocumentCodec;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.PersistenceDocument;
import dev.nexcraft.r2d1.integration.support.RecordingDocumentStore;
import dev.nexcraft.r2d1.integration.support.RecordingIndexStore;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PersistenceFlowIntegrationTest {

  private static final IntegrationDocumentCodec CODEC = new IntegrationDocumentCodec();

  @Test
  void putsIntoR2BeforeUpsertingD1() {
    withFixture(
        fixture -> {
          PersistenceDocument document = new PersistenceDocument("put", "NZ", 10L, "put-payload");

          fixture.collection().put(document);

          assertThat(fixture.events()).containsExactly("r2.put:put", "d1.upsert:put");
          assertThat(
                  CODEC.deserialize(
                      await(
                          fixture.documents().get(new DocumentKey(PERSISTENCE_COLLECTION, "put"))),
                      PersistenceDocument.class))
              .isEqualTo(document);
          assertThat(
                  await(
                          fixture
                              .indexes()
                              .query(sortedCountryQuery(PERSISTENCE_COLLECTION, "NZ", 10)))
                      .documentKeys())
              .containsExactly(new DocumentKey(PERSISTENCE_COLLECTION, "put"));
        });
  }

  @Test
  void getsOnlyFromR2() {
    withFixture(
        fixture -> {
          PersistenceDocument document =
              new PersistenceDocument("get", "NZ", 20L, "authoritative-payload");
          await(
              fixture
                  .documents()
                  .put(
                      new DocumentKey(PERSISTENCE_COLLECTION, document.id()),
                      CODEC.serialize(document)));
          fixture.events().clear();

          Optional<PersistenceDocument> result = fixture.collection().get(document.id());

          assertThat(result).contains(document);
          assertThat(fixture.events()).containsExactly("r2.get:get");
        });
  }

  @Test
  void queriesD1ThenFansOutToR2InIndexOrder() {
    withFixture(
        fixture -> {
          PersistenceDocument a =
              new PersistenceDocument("query-a", "NZ", 10L, "payload-from-r2-a");
          PersistenceDocument b =
              new PersistenceDocument("query-b", "NZ", 10L, "payload-from-r2-b");
          await(fixture.indexes().upsert(entry(PERSISTENCE_COLLECTION, b)));
          await(fixture.indexes().upsert(entry(PERSISTENCE_COLLECTION, a)));
          await(
              fixture
                  .documents()
                  .put(new DocumentKey(PERSISTENCE_COLLECTION, b.id()), CODEC.serialize(b)));
          await(
              fixture
                  .documents()
                  .put(new DocumentKey(PERSISTENCE_COLLECTION, a.id()), CODEC.serialize(a)));
          fixture.events().clear();

          Page<PersistenceDocument> page =
              fixture
                  .collection()
                  .query()
                  .where("country")
                  .eq("NZ")
                  .sortBy("rank", SortDirection.ASC)
                  .limit(10)
                  .fetch();

          assertThat(page.items()).containsExactly(a, b);
          assertThat(fixture.events())
              .containsExactly("d1.query", "r2.get:query-a", "r2.get:query-b");
        });
  }

  @Test
  void deletesFromR2BeforeDeletingD1() {
    withFixture(
        fixture -> {
          PersistenceDocument document =
              new PersistenceDocument("delete", "NZ", 30L, "delete-payload");
          fixture.collection().put(document);
          fixture.events().clear();

          fixture.collection().delete(document.id());

          assertThat(fixture.events()).containsExactly("r2.delete:delete", "d1.delete:delete");
          assertThatThrownBy(
                  () ->
                      await(
                          fixture
                              .documents()
                              .get(new DocumentKey(PERSISTENCE_COLLECTION, document.id()))))
              .isInstanceOf(DocumentNotFoundException.class);
          IndexPage index =
              await(fixture.indexes().query(sortedCountryQuery(PERSISTENCE_COLLECTION, "NZ", 10)));
          assertThat(index.documentKeys()).isEmpty();
        });
  }

  private static void withFixture(FixtureTest test) {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    try (var documents = config.openR2();
        var indexes = config.openD1()) {
      clearR2Collection(documents, PERSISTENCE_COLLECTION);
      clearD1Collection(indexes, PersistenceDocument.class, PERSISTENCE_COLLECTION);
      try {
        List<String> events = new CopyOnWriteArrayList<>();
        R2D1 client =
            R2D1.builder()
                .collectionFactory(
                    new PersistenceCollectionFactory(
                        new RecordingDocumentStore(documents, events),
                        new RecordingIndexStore(indexes, events),
                        CODEC,
                        indexes::initialize))
                .build();
        R2D1Collection<PersistenceDocument> collection =
            client.collection(PersistenceDocument.class);
        events.clear();
        test.run(new Fixture(documents, indexes, collection, events));
      } finally {
        clearR2Collection(documents, PERSISTENCE_COLLECTION);
        await(indexes.clear(PERSISTENCE_COLLECTION));
      }
    }
  }

  private record Fixture(
      DocumentStore documents,
      IndexStore indexes,
      R2D1Collection<PersistenceDocument> collection,
      List<String> events) {}

  @FunctionalInterface
  private interface FixtureTest {

    void run(Fixture fixture);
  }
}
