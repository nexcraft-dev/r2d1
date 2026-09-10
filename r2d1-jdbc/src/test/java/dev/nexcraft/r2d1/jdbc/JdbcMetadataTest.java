package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.jdbc.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.jdbc.JdbcMetadata.ValueType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcMetadataTest {

  @Test
  void inspectsEveryCoreIndexValueTypeWithoutAD1FieldLimit() {
    JdbcMetadata.CollectionMetadata metadata = JdbcMetadata.inspect(AllValues.class);

    assertThat(metadata.collection()).isEqualTo("events");
    assertThat(metadata.indexedFields())
        .containsExactly(
            new IndexedField("active", ValueType.BOOLEAN),
            new IndexedField("createdAt", ValueType.TIMESTAMP),
            new IndexedField("description", ValueType.STRING),
            new IndexedField("extraOne", ValueType.STRING),
            new IndexedField("extraTwo", ValueType.LONG),
            new IndexedField("rank", ValueType.LONG),
            new IndexedField("score", ValueType.DOUBLE));
  }

  @Test
  void includesInheritedIndexedFieldsAndComparesSchemasByShape() {
    JdbcMetadata.CollectionMetadata first = JdbcMetadata.inspect(InheritedValues.class);
    JdbcMetadata.CollectionMetadata equivalent = JdbcMetadata.inspect(EquivalentValues.class);

    assertThat(first.indexedFields())
        .containsExactly(
            new IndexedField("active", ValueType.BOOLEAN),
            new IndexedField("description", ValueType.STRING));
    assertThat(first.hasSameSchema(equivalent)).isTrue();
  }

  @Test
  void rejectsInvalidDocumentMetadata() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcMetadata.inspect(NotADocument.class))
        .withMessageContaining("must be annotated with @Document");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcMetadata.inspect(BlankCollection.class))
        .withMessageContaining("collection must not be blank");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcMetadata.inspect(UnsupportedValue.class))
        .withMessageContaining("uses unsupported type: java.lang.Integer");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcMetadata.inspect(ReservedField.class))
        .withMessage("indexed field name is reserved by R2D1: document_id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcMetadata.inspect(DuplicateField.class))
        .withMessage("duplicate indexed field: description");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullDocumentType() {
    assertThatNullPointerException()
        .isThrownBy(() -> JdbcMetadata.inspect(null))
        .withMessage("documentType");
  }

  @Document("events")
  private static final class AllValues {
    @Index private String description;
    @Index private Long rank;
    @Index private double score;
    @Index private Boolean active;
    @Index private Instant createdAt;
    @Index private String extraOne;
    @Index private long extraTwo;
  }

  private static class BaseValues {
    @Index private String description;
  }

  @Document("events")
  private static final class InheritedValues extends BaseValues {
    @Index private boolean active;
  }

  @Document("events")
  private static final class EquivalentValues {
    @Index private boolean active;
    @Index private String description;
  }

  private static final class NotADocument {}

  @Document(" ")
  private static final class BlankCollection {}

  @Document("events")
  private static final class UnsupportedValue {
    @Index private Integer value;
  }

  @Document("events")
  private static final class ReservedField {
    @Index private String document_id;
  }

  private static class DuplicateBase {
    @Index private String description;
  }

  @Document("events")
  private static final class DuplicateField extends DuplicateBase {
    @Index private String description;
  }
}
