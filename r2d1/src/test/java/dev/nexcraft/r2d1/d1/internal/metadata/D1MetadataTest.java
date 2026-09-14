package dev.nexcraft.r2d1.d1.internal.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class D1MetadataTest {

  @Test
  void discoversSupportedPrimitiveBoxedAndRecordFieldsInStableOrder() {
    D1CollectionMetadata classMetadata = D1Metadata.inspect(SupportedDocument.class);
    D1CollectionMetadata recordMetadata = D1Metadata.inspect(SupportedRecord.class);

    assertThat(classMetadata.collection()).isEqualTo("UserProfiles");
    assertThat(classMetadata.indexedFields())
        .extracting(D1IndexedField::name, D1IndexedField::type)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("active", D1ValueType.BOOLEAN),
            org.assertj.core.groups.Tuple.tuple("count", D1ValueType.LONG),
            org.assertj.core.groups.Tuple.tuple("name", D1ValueType.STRING),
            org.assertj.core.groups.Tuple.tuple("score", D1ValueType.DOUBLE));
    assertThat(recordMetadata.indexedFields())
        .extracting(D1IndexedField::name, D1IndexedField::type)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("active", D1ValueType.BOOLEAN),
            org.assertj.core.groups.Tuple.tuple("name", D1ValueType.STRING));
  }

  @Test
  void treatsEveryIndexAsSortableRegardlessOfLegacyFlag() {
    D1CollectionMetadata metadata = D1Metadata.inspect(SupportedDocument.class);

    assertThat(metadata.requireIndexedField("name")).isNotNull();
    assertThat(metadata.requireIndexedField("score")).isNotNull();
  }

  @Test
  void validatesSqlIdentifiersAndAlwaysQuotesAcceptedNames() {
    assertThat(D1Metadata.quoteIdentifier("users")).isEqualTo("\"users\"");
    assertThat(D1Metadata.quoteIdentifier("user_profiles")).isEqualTo("\"user_profiles\"");
    assertThat(D1Metadata.quoteIdentifier("UserProfiles")).isEqualTo("\"UserProfiles\"");

    for (String invalid :
        new String[] {"user-profile", "user profile", "123users", "users\";DROP_TABLE"}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> D1Metadata.quoteIdentifier(invalid))
          .withMessageContaining("must match");
    }
  }

  @Test
  void rejectsInvalidCollectionAndReservedOrSqlShapedFields() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(InvalidCollection.class))
        .withMessageContaining("document collection must match");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(ReservedField.class))
        .withMessage("indexed field name is reserved by R2D1: document_id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(SqlShapedField.class))
        .withMessageContaining("indexed field must match");
  }

  @Test
  void rejectsUnsupportedTypesAndMoreThanFiveIndexes() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(IntegerIndex.class))
        .withMessageContaining("uses unsupported type: java.lang.Integer");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(InstantIndex.class))
        .withMessageContaining("uses unsupported type: java.time.Instant");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(SixIndexes.class))
        .withMessageContaining("at most 5 indexed fields");
  }

  @Test
  void requiresDocumentMetadata() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> D1Metadata.inspect(String.class))
        .withMessageContaining("must be annotated with @Document");
  }

  @Document("UserProfiles")
  private static final class SupportedDocument {
    @Index private Long count;

    @Index(sortable = true)
    private double score;

    @Index private String name;
    @Index private boolean active;
  }

  @Document("records")
  private record SupportedRecord(@Index String name, @Index Boolean active) {}

  @Document("user-profile")
  private static final class InvalidCollection {}

  @Document("users")
  private static final class ReservedField {
    @Index private String document_id;
  }

  @Document("users")
  private static final class SqlShapedField {
    @Index private String $malicious;
  }

  @Document("users")
  private static final class IntegerIndex {
    @Index private Integer value;
  }

  @Document("users")
  private static final class InstantIndex {
    @Index private Instant value;
  }

  @Document("users")
  private static final class SixIndexes {
    @Index private String one;
    @Index private String two;
    @Index private String three;
    @Index private String four;
    @Index private String five;
    @Index private String six;
  }
}
