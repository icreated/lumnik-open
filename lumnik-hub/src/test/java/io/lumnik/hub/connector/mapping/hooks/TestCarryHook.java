package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/**
 * Test-only carrying cell hook (#1452 A): numbers the rows it sees in file order ({@code seq}) and copies the previous
 * row's {@code column} value onto each row ({@code prev}), carrying both across chunks and resumes. {@link #failWhen}
 * fails the row whose {@code row_no} equals it, as a crash would mid-run.
 */
@ApplicationScoped
@HookId("test-carry")
public class TestCarryHook implements CellHook {

  public static volatile String failWhen;

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    if (failWhen != null && failWhen.equals(String.valueOf(row.get("row_no")))) {
      throw new IllegalStateException("test-carry: failing on row_no " + failWhen);
    }
    Map<String, Object> carry = state.carry();
    long seq = (Long) carry.getOrDefault("seq", 0L) + 1;
    row.put("seq", seq);
    row.put("prev", carry.get("last"));
    carry.put("seq", seq);
    carry.put("last", row.get(params.path("column").asText()));
  }

  @Override
  public Set<String> params() {
    return Set.of("column");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().reads("column", params.path("column")).adds("seq").adds("prev").build();
  }


  @Override
  public boolean carriesState() {
    return true;
  }
}
