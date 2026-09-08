package dev.nexcraft.r2d1.d1;

import java.util.List;
import java.util.Objects;

/** A single D1 SQL statement that uses parameter binding. */
record D1Statement(String sql, List<D1Parameter> parameters) {

  D1Statement {
    Objects.requireNonNull(sql, "sql");
    if (sql.isBlank()) {
      throw new IllegalArgumentException("sql must not be blank");
    }
    parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
  }

  static D1Statement of(String sql) {
    return new D1Statement(sql, List.of());
  }
}
