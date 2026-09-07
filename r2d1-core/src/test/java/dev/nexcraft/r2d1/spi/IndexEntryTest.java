package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexEntryTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user-123");

  @Test
  void defensivelyCopiesIndexedValues() {
    Map<String, IndexValue> values = new HashMap<>();
    values.put("country", new IndexValue.StringValue("NZ"));

    IndexEntry entry = new IndexEntry(KEY, values);
    values.clear();

    assertThat(entry.values())
        .containsExactly(Map.entry("country", new IndexValue.StringValue("NZ")));
    assertThatThrownBy(() -> entry.values().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void permitsAnEmptyEntryForDocumentsWithoutIndexedFields() {
    assertThat(new IndexEntry(KEY, Map.of()).values()).isEmpty();
  }

  @Test
  void rejectsInvalidFieldNames() {
    assertThatThrownBy(() -> new IndexEntry(KEY, Map.of(" ", new IndexValue.StringValue("value"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexedField must not be blank");

    Map<String, IndexValue> values = new HashMap<>();
    values.put(null, new IndexValue.StringValue("value"));

    assertThatNullPointerException()
        .isThrownBy(() -> new IndexEntry(KEY, values))
        .withMessage("indexedField");
  }

  @Test
  void rejectsNullValues() {
    Map<String, IndexValue> values = new HashMap<>();
    values.put("country", null);

    assertThatNullPointerException()
        .isThrownBy(() -> new IndexEntry(KEY, values))
        .withMessage("index value for country");
  }

  @Test
  void rejectsNullComponents() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexEntry(null, Map.of()))
        .withMessage("documentKey");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexEntry(KEY, null))
        .withMessage("values");
  }
}
