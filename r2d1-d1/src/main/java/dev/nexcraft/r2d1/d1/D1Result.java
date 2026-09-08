package dev.nexcraft.r2d1.d1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A normalized D1 execution result that does not expose the REST protocol. */
record D1Result(List<Map<String, @Nullable Object>> rows, long changes) {

  D1Result {
    Objects.requireNonNull(rows, "rows");
    List<Map<String, @Nullable Object>> copiedRows = new ArrayList<>(rows.size());
    for (Map<String, @Nullable Object> row : rows) {
      Objects.requireNonNull(row, "row");
      copiedRows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
    }
    rows = List.copyOf(copiedRows);
  }
}
