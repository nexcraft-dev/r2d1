package dev.nexcraft.r2d1.integration.cloudflare;

import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.await;
import static dev.nexcraft.r2d1.integration.support.IntegrationSupport.clearR2Collection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class R2DocumentStoreIntegrationTest {

  private static final String COLLECTION = "r2d1-it-r2-v1";
  private static final String NEIGHBOR_COLLECTION = "r2d1-it-r2-v1-shadow";

  @Test
  void performsCrudAndPaginatedCollectionListingAgainstR2() {
    CloudflareIntegrationConfig config = CloudflareIntegrationConfig.load();
    try (var store = config.openR2()) {
      clearR2Collection(store, COLLECTION);
      clearR2Collection(store, NEIGHBOR_COLLECTION);
      try {
        DocumentKey plain = new DocumentKey(COLLECTION, "plain");
        DocumentKey special = new DocumentKey(COLLECTION, "slash/percent% space");
        DocumentKey unicode = new DocumentKey(COLLECTION, "unicode-café-東京-✓");
        DocumentKey neighbor = new DocumentKey(NEIGHBOR_COLLECTION, "outside");

        StoredDocument plainContent = content("plain-content");
        StoredDocument specialContent = content("special-content");
        StoredDocument unicodeContent = content("unicode-content");
        await(store.put(plain, plainContent));
        await(store.put(special, specialContent));
        await(store.put(unicode, unicodeContent));
        await(store.put(neighbor, content("neighbor-content")));

        assertThat(await(store.get(special))).isEqualTo(specialContent);

        DocumentPage first = await(store.list(COLLECTION, null, 2));
        assertThat(first.documentKeys()).hasSize(2);
        assertThat(first.nextCursor()).isPresent();

        DocumentPage second = await(store.list(COLLECTION, first.nextCursor().orElseThrow(), 2));
        assertThat(second.nextCursor()).isEmpty();

        List<DocumentKey> listed = new ArrayList<>(first.documentKeys());
        listed.addAll(second.documentKeys());
        assertThat(listed).containsExactlyInAnyOrder(plain, special, unicode);
        assertThat(listed).allSatisfy(key -> assertThat(key.collection()).isEqualTo(COLLECTION));

        await(store.delete(unicode));
        assertThatThrownBy(() -> await(store.get(unicode)))
            .isInstanceOf(DocumentNotFoundException.class);
      } finally {
        clearR2Collection(store, COLLECTION);
        clearR2Collection(store, NEIGHBOR_COLLECTION);
      }
    }
  }

  private static StoredDocument content(String value) {
    return new StoredDocument(value.getBytes(StandardCharsets.UTF_8));
  }
}
