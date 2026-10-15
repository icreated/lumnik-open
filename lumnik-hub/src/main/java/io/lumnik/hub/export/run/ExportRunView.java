package io.lumnik.hub.export.run;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code connector.export_run} row as the runs endpoints return it. Receipt fields are null
 * until {@code Completed}; {@code failureCode}/{@code failureReason} are null unless {@code Failed}.
 */
public record ExportRunView(UUID id, String export, String scope, String entity,
                            String destinationKind, ExportTrigger trigger, Long triggeredBy,
                            Instant occurrence, int attempt, UUID retryOf, ExportRunStatus status,
                            Instant startedAt, Instant endedAt, Long rowCount, Long byteCount,
                            String sha256, String location, String versionId, String etag,
                            ExportFailureCode failureCode, String failureReason) {}
