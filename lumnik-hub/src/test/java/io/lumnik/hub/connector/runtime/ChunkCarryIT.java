package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1452: what a stateful mapping carries is stored on the cursor row with the position it stands for, in the chunk's
 * transaction — handed back to the writer on the next chunk, with whether that chunk starts a file; never moved by a
 * batch the writer refused.
 */
@QuarkusTest
class ChunkCarryIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode STORED = node("{\"stored\":true}");
    private static final JsonNode OUT = node("{\"out\":true}");
    private static final JsonNode POSITION = node("{\"pos\":1}");

    @Inject ChunkExecutor exec;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;

    /** What the writer's prepare received. */
    static final class Seen {
        JsonNode carryIn;
        Boolean startsAFile;
    }

    @Test
    @TestTransaction
    void the_carry_is_stored_with_the_position() {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            exec.executeChunk(run, type(rows(), false, new WriteOutcome.AllWritten(3), new Seen()), endpointOf(run),
                ConnectorCursor.empty(), null);

            ConnectorCursorEntity stored = cursors.findByEndpoint(run.getEndpointId());
            assertThat(stored.getCursorValue()).isEqualTo(POSITION);
            assertThat(stored.getMappingCarry()).isEqualTo(OUT);
            assertThat(stored.getVersion()).as("one INSERT with its values, not an INSERT of a null then an UPDATE")
                .isZero();
        });
    }

    @Test
    @TestTransaction
    void the_stored_carry_is_handed_over_with_whether_the_chunk_starts_a_file() {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            seedCursor(run, STORED);
            Seen mid = new Seen();
            exec.executeChunk(run, type(rows(), false, new WriteOutcome.AllWritten(3), mid), endpointOf(run),
                new ConnectorCursor(POSITION, Instant.now(), STORED), null);
            assertThat(mid.carryIn).isEqualTo(STORED);
            assertThat(mid.startsAFile).isFalse();

            seedCursor(run, STORED);
            Seen start = new Seen();
            exec.executeChunk(run, type(rows(), true, new WriteOutcome.AllWritten(3), start), endpointOf(run),
                new ConnectorCursor(POSITION, Instant.now(), STORED), null);
            assertThat(start.startsAFile).isTrue();
        });
    }

    @Test
    @TestTransaction
    void a_refused_batch_moves_neither_the_position_nor_the_carry() {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            seedCursor(run, STORED);
            exec.executeChunk(run, type(rows(), false, new WriteOutcome.AllFailed("boom", null), new Seen()),
                endpointOf(run), new ConnectorCursor(POSITION, Instant.now(), STORED), null);

            ConnectorCursorEntity stored = cursors.findByEndpoint(run.getEndpointId());
            assertThat(stored.getCursorValue()).isEqualTo(node("{\"pos\":0}"));
            assertThat(stored.getMappingCarry()).isEqualTo(STORED);
        });
    }

    /** A window that yields no row maps nothing: the stored carry stays, unless the window began a file. */
    @Test
    @TestTransaction
    void a_chunk_that_maps_nothing_keeps_the_stored_carry() {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            seedCursor(run, STORED);
            exec.executeChunk(run, type(List.of(), false, new WriteOutcome.AllWritten(0), new Seen()), endpointOf(run),
                new ConnectorCursor(POSITION, Instant.now(), STORED), null);
            assertThat(cursors.findByEndpoint(run.getEndpointId()).getMappingCarry()).isEqualTo(STORED);

            exec.executeChunk(run, type(List.of(), true, new WriteOutcome.AllWritten(0), new Seen()), endpointOf(run),
                new ConnectorCursor(POSITION, Instant.now(), STORED), null);
            JsonNode afterStart = cursors.findByEndpoint(run.getEndpointId()).getMappingCarry();
            assertThat(afterStart == null || afterStart.isNull()).as("a file start carries nothing over").isTrue();
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static List<String> rows() {
        return List.of("r1", "r2", "r3");
    }

    private ConnectorRun fixture() {
        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("chunk-carry");
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", 3));
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint e = new ConnectorEndpoint();
        e.setTenantId(1L);
        e.setConnectorId(c.getId());
        e.setName("default");
        e.setMethod("GET");
        e.setPath("/default");
        e.setEnabled(true);
        e.setConfig(Map.of("count", 3));
        endpoints.persist(e);
        endpoints.flush();

        ConnectorRun r = new ConnectorRun();
        r.setTenantId(1L);
        r.setConnectorId(c.getId());
        r.setEndpointId(e.getId());
        r.setStatus("Running");
        r.setSyncMode("Incremental");
        r.setStartedAt(Instant.now());
        r.setTriggeredBy("MANUAL");
        runs.persist(r);
        runs.flush();
        return r;
    }

    private ConnectorEndpoint endpointOf(ConnectorRun run) {
        return endpoints.findById(run.getEndpointId());
    }

    /** The endpoint's cursor row, at a position other than POSITION, carrying {@code carry}. */
    private void seedCursor(ConnectorRun run, JsonNode carry) {
        ConnectorCursorEntity ent = cursors.findByEndpoint(run.getEndpointId());
        if (ent == null) {
            ent = new ConnectorCursorEntity();
            ent.setTenantId(1L);
            ent.setConnectorId(run.getConnectorId());
            ent.setEndpointId(run.getEndpointId());
            ent.setCursorValue(node("{\"pos\":0}"));
            ent.setMappingCarry(carry);
            cursors.persist(ent);
        } else {
            ent.setCursorValue(node("{\"pos\":0}"));
            ent.setMappingCarry(carry);
        }
        cursors.flush();
    }

    /**
     * A reader yielding {@code records} as HasMore at POSITION (EmptyButOpen when there are none) that says whether it
     * starts a file, and a writer recording what prepare received, returning {@code outcome}, handing OUT back.
     */
    private static ConnectorType type(List<String> records, boolean startsAFile, WriteOutcome outcome, Seen seen) {
        ItemReader<String> reader = new ItemReader<>() {
            @Override public ReadResult<String> read(int chunkSize, ConnectorCursor cur, ConnectorContext c) {
                ConnectorCursor next = new ConnectorCursor(POSITION, Instant.now());
                return records.isEmpty() ? new ReadResult.EmptyButOpen<>(next) : new ReadResult.HasMore<>(records, next);
            }
            @Override public boolean startsAFile() { return startsAFile; }
        };
        ItemWriter<String> writer = new ItemWriter<>() {
            @Override public JsonNode prepare(List<String> r, ConnectorContext c, JsonNode carryIn, boolean starts) {
                seen.carryIn = carryIn;
                seen.startsAFile = starts;
                return OUT;
            }
            @Override public WriteOutcome write(List<String> items, ConnectorContext c) { return outcome; }
        };
        return new ConnectorType() {
            @Override public String id() { return "test-carry"; }
            @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
            @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
            @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }
            @Override public ItemReader<String> reader(ConnectorContext ctx) { return reader; }
            @Override public ItemWriter<String> writer(ConnectorContext ctx) { return writer; }
        };
    }

    private static JsonNode node(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
