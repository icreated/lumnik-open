package io.lumnik.hub.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)   // its committed Queued rows must not meet the export dispatcher's poll
class RunQueueClaimIT {

    private static final long TENANT = 9521L;
    private static final String NEXT =
        "SELECT id FROM connector.export_run WHERE tenant_id = 9521 AND status = 'Queued' "
      + "AND NOT (id = ANY (?)) ORDER BY queued_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @AfterEach
    void clean() {
        Jdbc.update(adminDs, "clean 952 claim", "DELETE FROM connector.export_run WHERE tenant_id = ?", ps -> ps.setLong(1, TENANT));
    }

    private UUID queued(String export, String queuedAt) {
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "seed", "INSERT INTO connector.export_run (id, tenant_id, export_id, export_name, scope, "
            + "entity_name, destination_kind, manifest_sha256, trigger, artifact_time, status, started_at, queued_at) "
            + "VALUES (?, ?, ?, ?, 's', 'e', 'sftp', 'x', 'manual', now(), 'Queued', NULL, ?::timestamptz)",
            ps -> { ps.setObject(1, id); ps.setLong(2, TENANT); ps.setObject(3, UUID.randomUUID());
                    ps.setString(4, export); ps.setString(5, queuedAt); });
        return id;
    }

    private Optional<RunQueueClaim<UUID>> next(Collection<UUID> tried) throws Exception {
        return RunQueueClaim.next(adminDs, NEXT, tried, rs -> rs.getObject(1, UUID.class));
    }

    private String status(UUID id) {
        return Jdbc.queryOne(adminDs, "status", "SELECT status FROM connector.export_run WHERE id = ?",
            ps -> ps.setObject(1, id), rs -> rs.getString(1)).orElseThrow();
    }

    @Test
    void the_oldest_queued_row_comes_first_and_a_tried_one_is_skipped() throws Exception {
        UUID newer = queued("a", "2026-02-01T00:00:00Z");
        UUID older = queued("b", "2026-01-01T00:00:00Z");

        try (var first = next(Set.of()).orElseThrow()) {
            assertThat(first.row()).isEqualTo(older);
        }
        try (var second = next(Set.of(older)).orElseThrow()) {
            assertThat(second.row()).as("the next poll attempt, not the same head row again").isEqualTo(newer);
        }
        assertThat(next(Set.of(older, newer))).isEmpty();
    }

    @Test
    void a_row_another_claim_holds_is_skipped_not_waited_for() throws Exception {
        UUID only = queued("a", "2026-01-01T00:00:00Z");
        try (var held = next(Set.of()).orElseThrow()) {
            assertThat(held.row()).isEqualTo(only);
            assertThat(next(Set.of())).as("a second dispatcher finds nothing, and does not block").isEmpty();
        }
        assertThat(next(Set.of())).as("released on close: it is claimable again").isPresent().get().satisfies(RunQueueClaim::close);
    }

    @Test
    void closing_without_a_commit_leaves_the_row_untouched_and_a_commit_keeps_the_write() throws Exception {
        UUID run = queued("a", "2026-01-01T00:00:00Z");
        try (var claim = next(Set.of()).orElseThrow()) {
            try (PreparedStatement ps = claim.connection().prepareStatement(
                    "UPDATE connector.export_run SET status = 'Running', started_at = now() WHERE id = ?")) {
                ps.setObject(1, run);
                ps.executeUpdate();
            }
        }
        assertThat(status(run)).as("no commit: rolled back").isEqualTo("Queued");

        try (var claim = next(Set.of()).orElseThrow()) {
            try (PreparedStatement ps = claim.connection().prepareStatement(
                    "UPDATE connector.export_run SET status = 'Running', started_at = now() WHERE id = ?")) {
                ps.setObject(1, run);
                ps.executeUpdate();
            }
            claim.commit();
        }
        assertThat(status(run)).isEqualTo("Running");
    }

    @Test
    void an_empty_queue_answers_empty_and_does_not_keep_a_connection() throws Exception {
        for (int i = 0; i < 200; i++) assertThat(next(Set.of())).isEmpty();   // would exhaust the 20-slot pool if each leaked
    }
}
