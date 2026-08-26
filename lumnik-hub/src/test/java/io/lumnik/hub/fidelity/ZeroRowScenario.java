package io.lumnik.hub.fidelity;

/** A generated 0-row probe: a SQL over the seeded connector.t_zero table, and whether it returns 0 rows
 *  (in which case the system must reply with the honest caveat, not a phrased "none"). */
public record ZeroRowScenario(long seed, String sql, boolean expectEmpty) {}
