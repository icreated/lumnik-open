package io.lumnik.hub.search.projection;

import java.time.Instant;
import java.util.UUID;

public record SearchProjectionState(long tenantId, String searchIndex, String providerName,
                                    String liveGeneration, long position, String status,
                                    int retryCount, String lastError,
                                    UUID activeRunId, String activeRunPhase, Instant activeRunStartedAt,
                                    String activeGeneration, String activeProviderName,
                                    UUID cancelRequestedRunId,
                                    UUID lastRebuildRunId, String lastRebuildOutcome, Instant lastRebuildFinishedAt,
                                    Long lastDocumentCount, Instant lastDocumentCountAt) {

    /** Pre-V58 shape — every dispatch/catch-up test fixture that only cares about the columns V57
     *  already had; every V58 column defaults to absent. */
    public SearchProjectionState(long tenantId, String searchIndex, String providerName,
                                 String liveGeneration, long position, String status,
                                 int retryCount, String lastError) {
        this(tenantId, searchIndex, providerName, liveGeneration, position, status, retryCount, lastError,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
