package dev.nexcraft.r2d1.integration.persistence;

import static dev.nexcraft.r2d1.integration.support.IntegrationDocuments.PERSISTENCE_COLLECTION;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.clearD1Collection;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.clearR2Collection;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.Page;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;
import dev.nexcraft.r2d1.integration.cloudflare.CloudflareIntegrationConfig;
import dev.nexcraft.r2d1.integration.support.IntegrationDocumentCodec;
import dev.nexcraft.r2d1.integration.support.IntegrationDocuments.PersistenceDocument;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionConfig;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionMode;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the same collection contract against the supported adapter pairings. */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CrossAdapterPersistenceIntegrationTest {

  private static final IntegrationDocumentCodec CODEC = new IntegrationDocumentCodec();

  @TempDir Path filesystemRoot;

  @Test
  void filesystemAndD1UseTheGlobalPersistenceContract() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try (D1IndexStore indexes = config.openD1()) {
      FileSystemDocumentStore documents = new FileSystemDocumentStore(filesystemRoot, executor);
      clearD1Collection(indexes, PersistenceDocument.class, PERSISTENCE_COLLECTION);
      try {
        exercise(documents, indexes, indexes::initialize);
      } finally {
        await(indexes.clear(PERSISTENCE_COLLECTION));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void r2AndJdbcUseTheGlobalPersistenceContract() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    try (var documents = config.openR2();
        JdbcExecution execution = jdbcExecution()) {
      JdbcIndexStore indexes = new JdbcIndexStore(h2DataSource("r2_jdbc"), execution);
      clearR2Collection(documents, PERSISTENCE_COLLECTION);
      await(indexes.initialize(PersistenceDocument.class));
      await(indexes.clear(PERSISTENCE_COLLECTION));
      try {
        exercise(documents, indexes, indexes::initialize);
      } finally {
        clearR2Collection(documents, PERSISTENCE_COLLECTION);
        await(indexes.clear(PERSISTENCE_COLLECTION));
      }
    }
  }

  @Test
  void filesystemAndJdbcUseTheGlobalPersistenceContract() {
    ExecutorService filesystemExecutor = Executors.newFixedThreadPool(4);
    try (JdbcExecution execution = jdbcExecution()) {
      FileSystemDocumentStore documents =
          new FileSystemDocumentStore(filesystemRoot, filesystemExecutor);
      JdbcIndexStore indexes = new JdbcIndexStore(h2DataSource("filesystem_jdbc"), execution);
      await(indexes.initialize(PersistenceDocument.class));
      await(indexes.clear(PERSISTENCE_COLLECTION));
      try {
        exercise(documents, indexes, indexes::initialize);
      } finally {
        await(indexes.clear(PERSISTENCE_COLLECTION));
      }
    } finally {
      filesystemExecutor.shutdownNow();
    }
  }

  private static void exercise(
      DocumentStore documents,
      IndexStore indexes,
      PersistenceCollectionFactory.CollectionInitializer initializer) {
    R2D1Collection<PersistenceDocument> collection =
        R2D1.builder()
            .collectionFactory(
                new PersistenceCollectionFactory(documents, indexes, CODEC, initializer))
            .build()
            .collection(PersistenceDocument.class);
    PersistenceDocument document =
        new PersistenceDocument("cross-adapter", "NZ", 1L, "contract-payload");

    collection.put(document);
    assertThat(collection.get(document.id())).contains(document);
    Page<PersistenceDocument> page =
        collection
            .query()
            .where("country")
            .eq("NZ")
            .sortBy("rank", SortDirection.ASC)
            .limit(10)
            .fetch();
    assertThat(page.items()).containsExactly(document);

    collection.delete(document.id());

    assertThat(collection.get(document.id())).isEmpty();
    assertThat(
            collection
                .query()
                .where("country")
                .eq("NZ")
                .sortBy("rank", SortDirection.ASC)
                .limit(10)
                .fetch()
                .items())
        .isEmpty();
  }

  private static JdbcExecution jdbcExecution() {
    return JdbcExecution.create(new JdbcExecutionConfig(JdbcExecutionMode.PLATFORM_THREAD, 4, 64));
  }

  private static DataSource h2DataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
