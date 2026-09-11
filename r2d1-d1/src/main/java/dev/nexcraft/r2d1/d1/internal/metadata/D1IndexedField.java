package dev.nexcraft.r2d1.d1.internal.metadata;

import java.util.Objects;

/** Java-to-D1 type mapping for one indexed field. */
public record D1IndexedField(String name, D1ValueType type) {

  public D1IndexedField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
  }
}
