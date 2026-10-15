package io.lumnik.hub.export.run;

import java.time.Instant;
import java.util.UUID;

/**
 * One request to run an export — what a manual trigger and a due cron slot both build before
 * calling the same {@link ExportRunner#start}. {@code occurrence} is non-null iff the trigger is
 * {@link ExportTrigger#SCHEDULE}; a null {@code artifactTime} means "now", which is what a manual
 * run resolves its filename tokens against. A retry carries its original's occurrence and
 * artifact time, so it publishes under the same name.
 */
public record ExportRunRequest(long tenantId, String exportName, ExportTrigger trigger, Long actorId,
                               Instant occurrence, Instant artifactTime, int attempt, UUID retryOf) {

    /** A first manual attempt, resolved against the moment it was asked for. */
    public static ExportRunRequest manual(long tenantId, String exportName, Long actorId) {
        return new ExportRunRequest(tenantId, exportName, ExportTrigger.MANUAL, actorId, null,
            null, 1, null);
    }
}
