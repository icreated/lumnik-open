package io.lumnik.hub.search.projection;

/** Thrown by the between-batch cancellation check (design doc §3.2) — runBuild's own catch block
 *  treats this distinctly from a real failure: the staging generation is still abandoned, but the
 *  state write is cancelled, never degraded. */
class RebuildCancelledException extends RuntimeException {
}
