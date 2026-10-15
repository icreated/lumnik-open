package io.lumnik.hub.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #907: each of the scope's connectors answers with ONE probe of {@code idx_run_connector_ended}
 * instead of the scope reading every run it ever had.
 *
 * <p>Planned as lumnik_admin, the role {@link ScopeFreshness} runs as (BYPASSRLS: no policy stands
 * between its predicates and the index), with the real SQL and its real parameters.
 * {@code enable_seqscan = off} takes away the scan a test-sized table rightly gets, so this proves
 * the indexed path EXISTS; on a run history it is the only sane one.
 */
@QuarkusTest
class ScopeFreshnessPlanIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void each_connector_answers_from_the_head_of_its_slice_of_the_landed_runs_index() throws Exception {
        List<JsonNode> plan = plan();

        assertThat(plan).as("each connector stops at the first entry of its slice of the index: %s", plan.get(0))
            .anyMatch(n -> "Limit".equals(n.path("Node Type").asText())
                && "idx_run_connector_ended".equals(n.path("Plans").path(0).path("Index Name").asText()));
    }

    /** Every node of {@code EXPLAIN (FORMAT JSON)} over {@link ScopeFreshness#SQL}. */
    private List<JsonNode> plan() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement();
                 PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + ScopeFreshness.SQL)) {
                st.execute("SET LOCAL enable_seqscan = off");
                ps.setLong(1, 1L);
                ps.setLong(2, 1L);
                ps.setString(3, "freshness-plan");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    List<JsonNode> nodes = new ArrayList<>();
                    collect(MAPPER.readTree(rs.getString(1)).get(0).path("Plan"), nodes);
                    return nodes;
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        for (JsonNode child : node.path("Plans")) collect(child, out);
    }
}
