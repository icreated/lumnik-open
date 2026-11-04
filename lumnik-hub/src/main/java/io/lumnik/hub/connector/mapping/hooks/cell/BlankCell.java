package io.lumnik.hub.connector.mapping.hooks.cell;

import java.util.Map;

/**
 * A CSV's empty cell arrives blank, not null (#1762): every parse-* hook reads it as empty — the cell stays empty and
 * nothing is counted in cell_errors, which is for a value a hook could not read, not for a value that is not there.
 */
final class BlankCell {

  private BlankCell() {}

  /** True — and the cell set to null — when {@code v} is text holding only whitespace, or nothing. */
  static boolean emptied(Map<String, Object> row, String col, Object v) {
    if (!(v instanceof CharSequence text) || !text.toString().isBlank()) return false;
    row.put(col, null);
    return true;
  }
}
