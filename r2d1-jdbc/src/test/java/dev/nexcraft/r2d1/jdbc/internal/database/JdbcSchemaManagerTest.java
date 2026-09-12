package dev.nexcraft.r2d1.jdbc.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcSchemaManagerTest {

  @Test
  void acceptsOnlyNonUniqueUnfilteredSingleColumnRequiredIndexes() {
    assertThat(
            JdbcSchemaManager.compatibleIndex(
                new JdbcSchemaManager.IndexInfo(List.of("rank"), true, false), "rank"))
        .isTrue();
    assertThat(
            JdbcSchemaManager.compatibleIndex(
                new JdbcSchemaManager.IndexInfo(List.of("rank"), false, false), "rank"))
        .isFalse();
    assertThat(
            JdbcSchemaManager.compatibleIndex(
                new JdbcSchemaManager.IndexInfo(List.of("rank"), true, true), "rank"))
        .isFalse();
    assertThat(
            JdbcSchemaManager.compatibleIndex(
                new JdbcSchemaManager.IndexInfo(List.of("country"), true, false), "rank"))
        .isFalse();
    assertThat(JdbcSchemaManager.compatibleIndex(null, "rank")).isFalse();
  }
}
