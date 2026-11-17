package io.lumnik.hub.connector.csv;

import io.lumnik.hub.validator.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mapping that types a column the table already holds as another type (#1882). A table keeps the types it was
 * created with: a {@code parse-date} added once the column landed as text makes the next run write instants into it as
 * text, and an entity over the column stays text. The hub does not retype a table in place — {@code t_*} tables are
 * shared between tenants — so the apply says it, and says the gesture.
 */
public final class MappingTypeDrift {

  private MappingTypeDrift() {}

  private static final Set<String> NUMBERS = Set.of("int2", "int4", "int8", "numeric", "float4", "float8");

  /** The hooks that type a column, with the types (PostgreSQL's {@code udt_name}) the column may hold for them. */
  private static final Map<String, Set<String>> TYPED_BY = Map.of(
      "parse-date", Set.of("date", "timestamptz", "timestamp"),
      "parse-number", NUMBERS,
      "parse-coordinates", NUMBERS,
      "parse-boolean", Set.of("bool"));

  /** The parameters that name the columns a hook types: {@code column}, unless the hook types a pair (#1966). */
  private static final Map<String, List<String>> COLUMN_KEYS = Map.of("parse-coordinates", List.of("lat", "lon"));
  private static final List<String> ONE_COLUMN = List.of("column");

  /**
   * One warning per column a typing hook of the mapping names and that {@code held} (column → {@code udt_name}, what
   * the table has) holds as another type; a column typed by two hooks is told twice. A column the table does not hold yet is
   * not drift: its first write creates it typed.
   */
  public static List<ValidationError> warnings(String table, Map<String, Object> mapping, Map<String, String> held) {
    List<ValidationError> out = new ArrayList<>();
    if (mapping == null || !(mapping.get("cell") instanceof List<?> cell)) return out;
    for (Object entry : cell) {
      if (!(entry instanceof Map<?, ?> hook)) continue;
      hook.forEach((name, params) -> {
        Set<String> types = TYPED_BY.get(String.valueOf(name));
        if (types == null || !(params instanceof Map<?, ?> p)) return;
        for (String key : COLUMN_KEYS.getOrDefault(String.valueOf(name), ONE_COLUMN)) {
          if (p.get(key) == null) continue;
          String column = String.valueOf(p.get(key));
          String type = held.get(column);
          if (type == null || types.contains(type)) continue;
          out.add(new ValidationError("spec.mapping.cell", ValidationError.Severity.WARNING,
              "column \"" + column + "\" is " + type + " in " + table + ", and `" + name + "` types it: a table keeps"
                  + " the types it was created with, so the next run writes the values into a " + type + " column",
              List.of("to retype it, delete the entities over this table, then `lm connector delete <connector>"
                  + " --force` (drops the table and its rows), apply the connector again and run it")));
        }
      });
    }
    return out;
  }
}
