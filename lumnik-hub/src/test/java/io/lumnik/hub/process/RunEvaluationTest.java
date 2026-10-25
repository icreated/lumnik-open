package io.lumnik.hub.process;

import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.event.EventOutboxRow.Position;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether kind:Process has evaluated a run's rows (#1351). */
class RunEvaluationTest {

    @ParameterizedTest(name = "enabled={0} setNow={1} started={2} setAtStart={3} ended={4} highWater=({5},{6}) cursor=({7},{8}) → {9}")
    @CsvSource(nullValues = "null", value = {
        // No enabled Process: nothing to evaluate, never a claimed evaluation.
        "false, a, true,  a,    true,  null, 5,    1, 99, null",
        // A queued run, then a run still writing: rows the consumer cannot have passed yet.
        "true,  a, false, null, false, null, null, 1, 99, false",
        "true,  a, true,  a,    false, null, null, 1, 99, false",
        // The Process set changed since the run started (applied, re-applied, enabled, disabled,
        // removed): one of them may never have seen its rows, whatever the cursor says.
        "true,  b, true,  a,    true,  null, 5,    1, 99, null",
        // A run that started or ended before V76: unknown.
        "true,  a, true,  null, true,  null, 5,    1, 99, null",
        "true,  a, true,  a,    true,  null, null, 1, 99, null",
        // A run that ended before V85: its rows sit at xact 1, where the cursor's id decides.
        "true,  a, true,  a,    true,  null, 5,    1, 4,  false",
        "true,  a, true,  a,    true,  null, 5,    1, 5,  true",
        "true,  a, true,  a,    true,  null, 5,    7, 0,  true",
        // Since V85 the outbox is read in (xact_id, id) order (#1570): a higher id from an older
        // transaction is read BEFORE a lower id from a newer one, so the id alone cannot answer.
        "true,  a, true,  a,    true,  40,   9,    40, 9,  true",
        "true,  a, true,  a,    true,  40,   9,    40, 8,  false",
        "true,  a, true,  a,    true,  40,   9,    39, 99, false",
        "true,  a, true,  a,    true,  40,   9,    41, 1,  true",
    })
    void evaluated(boolean anyEnabled, String setNow, boolean started, String setAtStart, boolean ended,
                   Long highWaterXact, Long highWater, long cursorXact, long cursorId, Boolean expected) {
        ConnectorRun run = new ConnectorRun();
        run.setStartedAt(started ? Instant.EPOCH : null);   // the entity starts a new run now
        if (ended) run.setEndedAt(Instant.EPOCH);
        run.setProcessSet(setAtStart);
        run.setOutboxHighWaterXact(highWaterXact);
        run.setOutboxHighWater(highWater);
        var snapshot = new RunEvaluation.Snapshot(new ProcessManifestStore.ProcessSet(anyEnabled, setNow),
                new Position(cursorXact, cursorId));
        assertThat(snapshot.evaluated(run)).isEqualTo(expected);
    }
}
