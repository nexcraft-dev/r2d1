package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndexValueTest {

  @Test
  void representsEverySupportedIndexValue() {
    Instant timestamp = Instant.parse("2026-09-07T00:00:00Z");

    assertThat(new IndexValue.StringValue("NZ").value()).isEqualTo("NZ");
    assertThat(new IndexValue.LongValue(42).value()).isEqualTo(42);
    assertThat(new IndexValue.DoubleValue(42.5).value()).isEqualTo(42.5);
    assertThat(new IndexValue.BooleanValue(true).value()).isTrue();
    assertThat(new IndexValue.TimestampValue(timestamp).value()).isEqualTo(timestamp);
  }

  @Test
  void restrictsTheModelToTheFiveSupportedVariants() {
    assertThat(IndexValue.class.isSealed()).isTrue();
    assertThat(IndexValue.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            IndexValue.StringValue.class,
            IndexValue.LongValue.class,
            IndexValue.DoubleValue.class,
            IndexValue.BooleanValue.class,
            IndexValue.TimestampValue.class);
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullValuesInsteadOfRepresentingThem() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexValue.StringValue(null))
        .withMessage("value");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexValue.TimestampValue(null))
        .withMessage("value");
  }
}
