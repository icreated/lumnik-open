package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CsvMappingE2EIT {

  @Inject MappingEngine engine;

  private static final String MAPPING_YAML = """
    id: servicenow-incidents-test
    version: 1
    target: support.Incident
    mode: sorted-stream

    dataset:
      - { require-headers: { columns: [incident_id, assignee, tag] } }

    row:
      - aggregator:
          group-by: [incident_id]
          emit-on-change: incident_id
          keep-first: [incident_id, title]
          merge:
            assignees: { collect: assignee, into: array, dedup: true }
            tags:      { collect: tag, into: csv, sep: ",", dedup: true }

    cell:
      - { clean: { columns: ['*'], strip: true } }
    """;

  @Test
  void servicenow_aggregation_produces_3_grouped_rows() throws Exception {
    // 1. Load mapping
    MappingSpec spec = MappingLoader.load(MAPPING_YAML);

    // 2. Read CSV rows
    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/servicenow-incidents.csv").toURI());
    List<Map<String, Object>> input = new ArrayList<>();
    reader.readAll(path, null, input::add);
    assertThat(input).hasSize(10);

    // 3. Transform: pass all 10 rows as a single chunk with endOfStream=true to flush aggregator
    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, true);

    // 4. Assert: 3 incident groups
    assertThat(output).hasSize(3);

    // INC001: 3 assignees (alice, bob, carol), 2 unique tags (critical, db)
    var inc001 = output.stream()
        .filter(r -> "INC001".equals(r.get("incident_id")))
        .findFirst().orElseThrow();
    assertThat(inc001).containsEntry("title", "Database down");
    @SuppressWarnings("unchecked")
    List<Object> inc001Assignees = (List<Object>) inc001.get("assignees");
    assertThat(inc001Assignees).containsExactlyInAnyOrder("alice", "bob", "carol");
    assertThat((String) inc001.get("tags")).contains("critical").contains("db");

    // INC002: 2 assignees, dedup auth → "auth" once
    var inc002 = output.stream()
        .filter(r -> "INC002".equals(r.get("incident_id")))
        .findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    List<Object> inc002Assignees = (List<Object>) inc002.get("assignees");
    assertThat(inc002Assignees).containsExactlyInAnyOrder("alice", "dave");
    assertThat((String) inc002.get("tags")).isEqualTo("auth");

    // INC003: 5 assignees, all "perf" dedup → "perf" once
    var inc003 = output.stream()
        .filter(r -> "INC003".equals(r.get("incident_id")))
        .findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    List<Object> inc003Assignees = (List<Object>) inc003.get("assignees");
    assertThat(inc003Assignees).hasSize(5);
    assertThat((String) inc003.get("tags")).isEqualTo("perf");

    // 5. Verify counters
    assertThat(state.counters().rowsIn().get()).isEqualTo(10);
    assertThat(state.counters().rowsOut().get()).isEqualTo(3);
    assertThat(state.counters().aggregatorEmissions().get()).isEqualTo(3);
  }

  @Test
  void servicenow_split_across_chunks_emits_correctly() throws Exception {
    MappingSpec spec = MappingLoader.load(MAPPING_YAML);

    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/servicenow-incidents.csv").toURI());
    List<Map<String, Object>> all = new ArrayList<>();
    reader.readAll(path, null, all::add);

    // Split into 3 chunks
    List<Map<String, Object>> chunk1 = all.subList(0, 4);  // INC001 x 3 + INC002 x 1
    List<Map<String, Object>> chunk2 = all.subList(4, 7);  // INC002 x 1 + INC003 x 2
    List<Map<String, Object>> chunk3 = all.subList(7, 10); // INC003 x 3

    MappingState state = new MappingState();
    var out1 = engine.transform(spec, chunk1, state, false);
    var out2 = engine.transform(spec, chunk2, state, false);
    var out3 = engine.transform(spec, chunk3, state, true); // last chunk: flush

    int total = out1.size() + out2.size() + out3.size();
    assertThat(total).isEqualTo(3);
    assertThat(state.counters().aggregatorEmissions().get()).isEqualTo(3);
  }
}
