package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * How a hook changes a row's columns (#1500), declared by the hook from its params: an ORDERED list of steps — read a
 * column (with the parameter path that names it), read the first present of several, remove one, add one — replayed in
 * that order, as the hook runs them: {@code map-columns} renames one after another, then drops, so a column a rename
 * adds can be dropped and a rename can follow another. {@code unknownAfter}: the hook may add, change or remove any key
 * (a Kotlin script), so nothing is known after it. Read by the csv-file validator, which walks a mapping before any row
 * exists; the run never reads it.
 */
public record ColumnEffect(List<Step> steps, boolean unknownAfter) {

  /** One change to the row's columns, in the hook's order. */
  public sealed interface Step permits Read, AnyOf, Remove, Add {}

  /** A column the hook reads, and the parameter path that names it ({@code columns[1]}, {@code reset-when.column}). */
  public record Read(String path, String column) implements Step {}

  /**
   * Candidates the hook reads the first present of ({@code coalesce.from}, {@code require-headers.any-of}): one absent
   * candidate is the hook's normal use; all of them absent, and it certainly does nothing.
   */
  public record AnyOf(String path, List<String> columns) implements Step {}

  public record Remove(String column) implements Step {}

  public record Add(String column) implements Step {}

  public static final ColumnEffect NONE = new ColumnEffect(List.of(), false);
  public static final ColumnEffect UNKNOWN_AFTER = new ColumnEffect(List.of(), true);

  public static Builder builder() {
    return new Builder();
  }

  /** The hooks that read the one column named by {@code column}. */
  public static ColumnEffect readsColumn(JsonNode params) {
    return builder().reads("column", params.path("column")).build();
  }

  /**
   * The hooks whose {@code columns} names the columns they work on — read as {@code CleanHook.columnList} reads it:
   * absent, or exactly {@code ["*"]}, is every column, none named.
   */
  public static ColumnEffect readsColumns(JsonNode params) {
    JsonNode columns = params.path("columns");
    if (columns.size() == 1 && "*".equals(columns.get(0).asText())) return NONE;
    return builder().readsEach("columns", columns).build();
  }

  /** The hooks whose keys are the columns they fill ({@code defaults}, {@code enrich-context}). */
  public static ColumnEffect addsKeys(JsonNode params) {
    Builder b = builder();
    params.fieldNames().forEachRemaining(b::adds);
    return b.build();
  }

  /** Steps are kept in the order they are declared: declare them in the order the hook runs them. */
  public static final class Builder {
    private final List<Step> steps = new ArrayList<>();

    /** Reads the column {@code value} names, when it names one; a malformed value is the hook's own refusal. */
    public Builder reads(String path, JsonNode value) {
      if (value.isTextual() && !value.asText().isBlank()) steps.add(new Read(path, value.asText()));
      return this;
    }

    public Builder reads(String path, String column) {
      steps.add(new Read(path, column));
      return this;
    }

    /** Reads each column of the list {@code value}, element {@code i} at {@code key[i]}. */
    public Builder readsEach(String key, JsonNode list) {
      for (int i = 0; i < list.size(); i++) reads(key + "[" + i + "]", list.get(i));
      return this;
    }

    /** Reads the first present column of the list {@code value}, at {@code key}. */
    public Builder readsAnyOf(String key, JsonNode list) {
      List<String> columns = new ArrayList<>();
      for (JsonNode c : list) {
        if (c.isTextual() && !c.asText().isBlank()) columns.add(c.asText());
      }
      if (!columns.isEmpty()) steps.add(new AnyOf(key, List.copyOf(columns)));
      return this;
    }

    public Builder removes(String column) {
      steps.add(new Remove(column));
      return this;
    }

    /** Adds the column {@code value} names, when it names one. */
    public Builder adds(JsonNode value) {
      if (value.isTextual() && !value.asText().isBlank()) steps.add(new Add(value.asText()));
      return this;
    }

    public Builder adds(String column) {
      steps.add(new Add(column));
      return this;
    }

    public ColumnEffect build() {
      return new ColumnEffect(List.copyOf(steps), false);
    }
  }
}
