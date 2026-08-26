package io.lumnik.hub.fidelity;

/** Honesty invariant families. DECEPTION + FALSE_BLOCK are active (the two-sided adversary);
 *  the rest are declared dormant for later sub-projects (they need the agent + DB). */
public enum Family { DECEPTION, FALSE_BLOCK, ABSTENTION, ZERO_ROW, LEAK }
