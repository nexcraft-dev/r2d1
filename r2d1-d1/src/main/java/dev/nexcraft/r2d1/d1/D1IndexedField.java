package dev.nexcraft.r2d1.d1;

import java.util.Objects;

/** Java-to-D1 type mapping for one indexed field. */
record D1IndexedField(String name, D1ValueType type) {

  D1IndexedField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
  }
}
