package io.lumnik.hub.connector.api.types;

import java.util.function.Supplier;

/**
 * What a run keeps open between its chunks. A connector type asks for a resource under a key it chooses
 * ({@code ctx.scope().resource(key, factory)}): the first ask builds it, every later ask in the same run — in
 * {@link io.lumnik.hub.connector.api.ConnectorType#runStart} and in every chunk — gets the same instance, and the
 * ENGINE closes it when the run ends, whatever the ending.
 *
 * <p>This exists because a reader lives one chunk ({@code ChunkExecutor} builds {@code type.reader(ctx)} for
 * each) and a transport that is costly to set up (a whole SSH handshake, a Kafka client) used to be rebuilt every
 * 500 rows. The reader stays fresh and stateless; only the resource outlives it (#898).
 *
 * <p><b>Not here: recovery.</b> A cached session can die between two chunks. Whether a failed call may be
 * retried on a new one depends on whether the chunk has already published anything, which only the transport
 * knows — so the transport calls {@link #invalidate} and rebuilds once itself. This type does not retry.
 *
 * <p>Keys are compared with {@code equals}: use a record or a string that names the transport and its target,
 * not the raw config map. Not {@link AutoCloseable} on purpose: the closing belongs to the engine alone.
 */
public interface RunScope {

  /**
   * The resource held under {@code key}, built by {@code factory} on first use; never null. The factory builds a
   * resource fully or throws: nothing is kept for a key whose factory threw, and the next ask may try again.
   */
  <T extends AutoCloseable> T resource(Object key, Supplier<T> factory);

  /**
   * Closes the resource under {@code key} now and forgets it, so the next {@link #resource} builds a new one. A
   * failure of the close is logged, not thrown, a {@code LinkageError} included; only a {@code VirtualMachineError}
   * (out of memory, stack overflow, internal error) propagates. Absent key: nothing happens.
   */
  void invalidate(Object key);
}
