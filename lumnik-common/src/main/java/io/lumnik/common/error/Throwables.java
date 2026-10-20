package io.lumnik.common.error;

import java.util.ArrayList;
import java.util.List;

/**
 * The cause walk every failure reader shares — SQL text, export text, the outbound classifier, the
 * ask guards. It has nothing SQL-specific; it lived in {@code SqlFailureText}, where its first
 * readers were, until the readers that are not about SQL outnumbered them (#1166), and left the hub
 * for lumnik-common when {@code Jdbc} became one of them (#1299).
 */
public final class Throwables {

    private Throwables() {}

    /**
     * How far to walk a cause chain. A bound rather than a seen-set because it is cycle-proof
     * without a second collection to track the links already visited (the chain itself is still a
     * list), and because a chain deeper than this is pathological either way: missing
     * a SQLState buried that far down costs the disclosure, never a wrong answer.
     */
    public static final int MAX_CAUSE_DEPTH = 32;

    /**
     * The cause chain, depth-bounded — the ONE walk every reader shares.
     *
     * <p>Bounded rather than self-reference-guarded: {@code Throwable.initCause} refuses only
     * self-causation, so A→B→A is constructible in plain Java and an identity check leaves it
     * spinning on a request thread. Two hand-written walks lived here for one round and diverged
     * on exactly that point; one walk cannot.
     */
    public static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            chain.add(t);
            Throwable next = t.getCause();
            if (next == t) break;
            t = next;
        }
        return chain;
    }
}
