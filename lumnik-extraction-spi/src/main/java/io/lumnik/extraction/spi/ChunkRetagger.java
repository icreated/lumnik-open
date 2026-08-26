package io.lumnik.extraction.spi;

import java.util.List;
import java.util.UUID;

/**
 * Propagates a connector's tags onto the chunks it produced — the chunk store's side of the
 * retag operation. Implemented on the closed side; the open hub injects it as {@code Instance<>}.
 * Absent implementation = no chunk store = zero chunks to retag — the caller answers
 * {@code chunksUpdated: 0}, which is the truth, never a schema error. (Same Act 2 finding
 * family as {@link CitationLocator}: the update used to be a native-SQL string on
 * {@code rag.chunk} inside the open hub.)
 */
public interface ChunkRetagger {

    /** Set {@code tags} on every chunk of this connector; returns the number updated. */
    int retagByConnector(UUID connectorId, List<String> tags);
}
