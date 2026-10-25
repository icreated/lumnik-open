package io.lumnik.hub.api.run;

import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.process.RunEvaluation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunAdminDto(
    UUID id,
    UUID connectorId,
    String connectorName,
    UUID endpointId,
    String endpointName,
    String status,
    Instant startedAt,
    Instant endedAt,
    long recordsIn,
    long recordsOut,
    long recordsSkipped,
    long cellErrors,
    /**
     * Rows removed because the source no longer holds them (#1195), or older versions of a key (#1196):
     * outside the conservation identity.
     */
    long recordsRemoved,
    /** Files not read because they had not changed since they were read to the end (#1371). */
    long filesUnchanged,
    /** Files read and not moved or removed as after_process declares: still in the source folder (#1382). */
    long filesNotArchived,
    /** Each version of a Kotlin script the run's rows went through, in the order first run; empty when none (#1430). */
    List<ScriptVersion> scripts,
    String triggeredBy,
    int chunkCount,
    String errorMessage,
    long deadLetterCount,
    /**
     * Whether kind:Process has evaluated the rows this run wrote (#1351): null when the tenant has
     * no enabled Process, when one changed after the run started, or when the run ended before the
     * mark was recorded; see {@code RunEvaluation}.
     */
    Boolean evaluated
) {
  /**
   * connectorName may be null (connector since deleted) — the ledger is for human
   * diagnosis, so the name rides along whenever it exists; clients fall back to
   * the id when it doesn't.
   *
   * <p>Same rule one level down for the endpoint, which is what tells two rows of the same run
   * apart: a connector fans out over its endpoints and writes one row per endpoint, so a two-table
   * jdbc connector logs two rows that differ only in their counters until the endpoint is named.
   * Both endpoint fields are null together for a run that has no endpoint at all — a row predating
   * endpoints, or one whose endpoint was hard-deleted ({@code ON DELETE SET NULL}, V13).
   */
  public static RunAdminDto from(ConnectorRun r, long deadLetterCount,
                                 String connectorName, String endpointName, RunEvaluation.Snapshot evaluation) {
    return new RunAdminDto(
        r.getId(), r.getConnectorId(), connectorName,
        r.getEndpointId(), endpointName, r.getStatus(),
        r.getStartedAt(), r.getEndedAt(),
        r.getRecordsIn(), r.getRecordsOut(), r.getRecordsSkipped(), r.getCellErrors(),
        r.getRecordsRemoved(), r.getFilesUnchanged(), r.getFilesNotArchived(), r.scriptVersions(), r.getTriggeredBy(), r.getChunkCount(),
        r.getErrorMessage(), deadLetterCount,
        evaluation.evaluated(r)
    );
  }
}
