package dev.nexcraft.r2d1.d1.internal.sql;

import java.util.List;
import java.util.Objects;

/** A single D1 SQL statement that uses parameter binding. */
public record D1Statement(String sql, List<D1Parameter> parameters) {

  public D1Statement {
    Objects.requireNonNull(sql, "sql");
    if (sql.isBlank()) {
      throw new IllegalArgumentException("sql must not be blank");
    }
    parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
  }

  public static D1Statement of(String sql) {
    return new D1Statement(sql, List.of());
  }
}
