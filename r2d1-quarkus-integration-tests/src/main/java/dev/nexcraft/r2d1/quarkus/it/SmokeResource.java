package dev.nexcraft.r2d1.quarkus.it;

import dev.nexcraft.r2d1.R2D1;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

/** Test-only endpoint exercises persisted documents and derived H2 indexes in a running process. */
@Path("/smoke")
public final class SmokeResource {
  private final R2D1 facade;

  /** Uses the extension-provided facade via constructor injection. */
  public SmokeResource(final R2D1 facade) {
    this.facade = facade;
  }

  /** Performs write, point read, query, update and delete against real local storage. */
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String smoke() {
    final var collection = facade.collection(SmokeDocument.class);
    final var document = new SmokeDocument(UUID.randomUUID().toString(), "NZ", "Smoke");
    collection.put(document);
    if (!collection.get(document.id()).orElseThrow().equals(document))
      throw new IllegalStateException("Read mismatch");
    if (!collection.query().where("country").eq("NZ").limit(100).fetch().items().contains(document))
      throw new IllegalStateException("Query mismatch");
    final var updated = new SmokeDocument(document.id(), "AU", "Updated");
    collection.put(updated);
    if (!collection.get(document.id()).orElseThrow().equals(updated))
      throw new IllegalStateException("Update mismatch");
    collection.delete(document.id());
    if (collection.get(document.id()).isPresent())
      throw new IllegalStateException("Delete mismatch");
    return "ok";
  }
}
