package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AggregatorHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void emits_group_on_key_change() throws Exception {
    var hook = catalog.row("aggregator");
    var params = yaml("""
      group-by: [incident_id]
      emit-on-change: incident_id
      keep-first: [incident_id, title]
      merge:
        assignees: { collect: assignee, into: array }
      """);
    MappingState s = new MappingState();
    assertThat(hook.apply(new HashMap<>(Map.of("incident_id","1","title","T1","assignee","alice")), params, s)).isEmpty();
    assertThat(hook.apply(new HashMap<>(Map.of("incident_id","1","title","T1","assignee","bob")), params, s)).isEmpty();
    var emitted = hook.apply(new HashMap<>(Map.of("incident_id","2","title","T2","assignee","carol")), params, s);
    assertThat(emitted).hasSize(1);
    assertThat(emitted.get(0)).containsEntry("incident_id","1").containsEntry("title","T1");
    assertThat((List<Object>) emitted.get(0).get("assignees")).containsExactly("alice","bob");
  }

  @Test
  void flush_emits_last_buffered_group() throws Exception {
    var hook = catalog.row("aggregator");
    var params = yaml("""
      group-by: [k]
      emit-on-change: k
      merge:
        vals: { collect: v, into: array }
      """);
    MappingState s = new MappingState();
    hook.apply(new HashMap<>(Map.of("k","a","v","1")), params, s);
    hook.apply(new HashMap<>(Map.of("k","a","v","2")), params, s);
    var emitted = hook.flush(params, s);
    assertThat(emitted).hasSize(1);
    assertThat((List<Object>) emitted.get(0).get("vals")).containsExactly("1","2");
  }

  @Test
  void buffer_persists_across_chunks_via_state() throws Exception {
    var hook = catalog.row("aggregator");
    var params = yaml("""
      group-by: [k]
      emit-on-change: k
      merge:
        v: { collect: v, into: array }
      """);
    MappingState s = new MappingState();
    // Chunk 1
    hook.apply(new HashMap<>(Map.of("k","a","v","1")), params, s);
    // Chunk 2 (same state instance — simulates next chunk)
    hook.apply(new HashMap<>(Map.of("k","a","v","2")), params, s);
    // Flush
    var emitted = hook.flush(params, s);
    assertThat((List<Object>) emitted.get(0).get("v")).containsExactly("1","2");
  }

  @Test
  void merge_into_csv_with_separator() throws Exception {
    var hook = catalog.row("aggregator");
    var params = yaml("""
      group-by: [k]
      emit-on-change: k
      merge:
        tags: { collect: t, into: csv, sep: '|' }
      """);
    MappingState s = new MappingState();
    hook.apply(new HashMap<>(Map.of("k","a","t","x")), params, s);
    hook.apply(new HashMap<>(Map.of("k","a","t","y")), params, s);
    var emitted = hook.flush(params, s);
    assertThat(emitted.get(0)).containsEntry("tags", "x|y");
  }

  @Test
  void merge_with_dedup_removes_duplicates() throws Exception {
    var hook = catalog.row("aggregator");
    var params = yaml("""
      group-by: [k]
      emit-on-change: k
      merge:
        vals: { collect: v, into: array, dedup: true }
      """);
    MappingState s = new MappingState();
    hook.apply(new HashMap<>(Map.of("k","a","v","x")), params, s);
    hook.apply(new HashMap<>(Map.of("k","a","v","x")), params, s);
    hook.apply(new HashMap<>(Map.of("k","a","v","y")), params, s);
    var emitted = hook.flush(params, s);
    assertThat((List<Object>) emitted.get(0).get("vals")).containsExactly("x","y");
  }
}
