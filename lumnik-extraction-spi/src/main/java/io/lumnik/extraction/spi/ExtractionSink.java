package io.lumnik.extraction.spi;

import java.util.List;

public interface ExtractionSink {

    /** Unique sink identifier ("rag", "elk", "ionic", ...). */
    String name();

    /**
     * Validate config and prepare resources. Called once before first index/clear.
     * Throws ExtractionSinkException if the sink cannot operate.
     */
    void init();

    /**
     * Replace existing chunks for (tenantId, source) with this batch. Tags apply
     * to all chunks in the batch (scope-level). Implementations must be idempotent.
     */
    void index(long tenantId, String source, List<String> tags, List<ExtractedChunk> chunks);

    /** Remove all chunks for (tenantId, source). */
    void clear(long tenantId, String source);

    /** Release resources. Called once at shutdown. */
    void close();
}
