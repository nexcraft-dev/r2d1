package dev.nexcraft.r2d1.integration.support;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;

public final class IntegrationDocuments {

  public static final String D1_COLLECTION = "r2d1_it_d1_v1";
  public static final String PERSISTENCE_COLLECTION = "r2d1_it_persistence_v1";
  public static final String RECOVERY_COLLECTION = "r2d1_it_recovery_v1";
  public static final String FAILURE_COLLECTION = "r2d1_it_failure_v1";

  private IntegrationDocuments() {}

  public interface Value {

    String id();

    String country();

    Long rank();

    String payload();
  }

  @Document(D1_COLLECTION)
  public record D1Document(@Id String id, @Index String country, @Index Long rank, String payload)
      implements Value {}

  @Document(PERSISTENCE_COLLECTION)
  public record PersistenceDocument(
      @Id String id, @Index String country, @Index Long rank, String payload) implements Value {}

  @Document(RECOVERY_COLLECTION)
  public record RecoveryDocument(
      @Id String id, @Index String country, @Index Long rank, String payload) implements Value {}

  @Document(FAILURE_COLLECTION)
  public record FailureDocument(
      @Id String id, @Index String country, @Index Long rank, String payload) implements Value {}
}
